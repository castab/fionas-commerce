# Staff timeline updates

## Availability and ownership

`GET /staff/requests/{inquiryId}/updates` is a native SSE route on commerce-runtime
**0.0.27**'s existing JettyLoom listener. http4k remains **6.58.0.0**. Runtime owns
transport, framing, flushing, pre-stream errors and server lifecycle; Fiona owns
revision detection, admission, credentials and subscription lifetime. Resend owns email.
The existing timeline API remains authoritative. No frontend changes are included here.

Enable with `FIONAS_TIMELINE_UPDATES_ENABLED=true` (default **false**). The endpoint is
always documented and mounted; after authorization and resource validation a disabled
instance returns `503` with runtime `ErrorResponse`, code `timeline_updates_unavailable`.
There is no scanner or writer when disabled. Invalid flag values fail startup.

Enablement requires the existing shared pool's connection timeout in 1–5000ms and
`DATABASE_JDBC_URL` containing `socketTimeout=1..5` plus URL-encoded
`options=-c transaction_timeout=50000 -c statement_timeout=5000`. These are the same
strictly validated PostgreSQL 18 bounds as [intake](inquiry-intake.md#bounded-database-shutdown).
They apply to the whole pool, including HTTP and migrations. No separate pool or
transaction/cancellation infrastructure is introduced.

## Request and response contract

The caller must be an authenticated human USER with all three permissions:
`fionas.inquiries.read`, `commerce.financial-document.read`, `fionas.communications.read`.
Forward the actual staff session cookie (`__Host-fionas_session`), and send
`Accept: text/event-stream`. Fiona's existing session-first authentication and
AccessControl filters run through runtime `sseHandshake`. A SERVICE is forbidden,
even with all permissions. Authorization precedes UUID parsing and inquiry lookup.
No email provider request occurs during preparation or revision scanning.

Pre-stream errors retain runtime JSON and never activate a consumer: 400 malformed
UUID or missing SSE Accept header, 401 missing/invalid/expired session, 403 wrong
principal kind or missing permission, 404 unknown inquiry, sanitized 500 unexpected
failure, 503 disabled (or runtime shutting down). A non-SSE GET is rejected rather
than returning a fake streaming HTTP body.

A successful response has:

```text
200 OK
Content-Type: text/event-stream
Cache-Control: no-store, no-transform
X-Accel-Buffering: no

retry:3000

event:timeline.changed
id:42
data:{"inquiryId":"<canonical-uuid>","revision":"42"}
```

Data contains exactly the canonical inquiry UUID and the opaque decimal revision
string. No message, customer, provider, financial or diagnostic content is emitted.
Every admitted connection synchronizes from the next authoritative scan, including
baseline `0`. Reconnect always synchronizes again. `Last-Event-ID` is ignored: there
is no replay log. Changes may coalesce and duplicate hints are harmless. Never use
revision numbers as business state or compare them across connections.

## Durable revision coverage

V7 is preserved unchanged. `fionas.inquiry_change_revisions` has one positive BIGINT
counter per changed inquiry; no row means zero. Existing histories are not backfilled.
V7 AFTER INSERT triggers cover communication facts and processed provider events,
including delivery updates without a new message. Duplicate source inserts do not
advance. An inbound event plus its new communication can advance twice; revisions
are tokens, not event counts. Accepted-reply replay writes no new communication.

V8 adds triggers only on Fiona-owned proposal publications, declines and fulfillment
facts (served insert, changed closed timestamp). Invoice financial amendments through
`InquiryInvoiceRevisions` and canonical Invoice `CreateChangeOrder` explicitly advance
in the caller transaction, even without a service-plan write. Service-only Invoice
amendments and RELATED document snapshots do not change timeline items and do not
advance. Canonical Quote publications, including deposit/service republication, do.

`RecordDocumentPayment`, `AllocatePayment` and `RecordRefund` use
`AdvancePaymentTimelines` after their successful ledger mutation and business locks.
The supported runtime payment history includes every historical allocation, including
fully unwound allocations. Fiona resolves their owners through its own associations,
selects every inquiry whose canonical lineage appears, and advances distinct inquiry
IDs in deterministic UUID order. A later allocation to another lineage or refund of
unapplied money also changes every timeline that displays that receipt's whole history.
Standalone unallocated receipts and unrelated RELATED-only receipts have no timeline
to invalidate. Exact deposit acceptance's payment and Invoice promotion commit together.

All increments commit/roll back with their business facts. Rejected and rolled-back
commands advance nothing. No triggers or SQL target runtime-owned tables, and no
financial state is copied. Direct callers of the shared ledger bypass Fiona's financial
notification orchestration; they are **not covered**. All production writes requiring
notifications must use Fiona operations. During mixed-version rollout, V7/V8 triggers
cover old writers of Fiona facts, but old binaries do not emit financial increments.
Finish rollout and reconnect before relying on complete financial notifications.

## Dispatcher, authorization and failures

Each instance admits at most **256** writers and **16** per inquiry, with one pending
revision slot each. One scanner reads distinct active inquiry IDs in a single bounded
query/short transaction every second. No active subscriptions means no database query.
There are no provider calls, per-client transactions, local event bus, NATS channel,
Redis or sticky-session requirement. Other instances discover commits in PostgreSQL.
New subscriptions or commits during a scan are observed on the following scan.
Duplicate/regressing observations are ignored within a live subscription.

Each admitted client has one isolated virtual writer; native consumers register it
and return promptly. There is no event executor or unbounded queue. Writer admission
remains reserved until cleanup completes, even if the registry closes the subscription.
A separate watchdog checks deadlines every 100ms, interrupting writes after 5s and
connections at **60s**. Keepalive comments are sent approximately every **20s**; the
pinned adapter also has its own transport heartbeat. Comments are not revision changes.

Writers re-run the same AccessControl chain on a fresh request carrying the original
credential headers, without cached authenticatedPrincipal, every second and before
every change notification. The principal must still match. Revocation, expiry, disabled
user, permission removal or revalidation failure closes the stream without private
error details. Session expiry is discovered through runtime SessionManager resolution
on this cadence (plus bounded database latency); its public API does not expose the
original session expiry timestamp. No separate identity authority is maintained.

A failed revision scan closes affected subscriptions, logged as `timeline_scan_failed`.
Reconnect after recovery obtains current authority. A failed/stalled writer affects
only itself. Capacity exhaustion or shutdown racing post-commit admission closes that
connection without an initial synchronization; `timeline_subscription_refused` is logged.
Clients must not treat HTTP 200 alone as successful synchronization. Writer failures
log `timeline_writer_stopped` at DEBUG. Logs contain no inquiry IDs or content.

Shutdown rejects registrations, closes slots, interrupts scanner/watchdog/writers and
joins them before runtime stops Jetty and then the pool. The common shutdown deadline
is 60s (database transactions are bounded at 50s); failure to join is surfaced, never
silently declared successful. Use deployment grace **at least 100s** when combined with
intake. Startup/close are idempotent; construction starts no work. Reconnect on deploy.
Restore/recovery must disconnect existing streams before counters can regress.

Provider changes cannot advance a counter until communication intake processes them.
Disabled or halted intake therefore limits notification freshness; monitor its existing
status independently. A revision never proves provider availability, email delivery,
payment success or cross-system atomicity. Retain manual refresh. Counter rows have
no separate retention job and must not be purged while their inquiries are retained.

## OpenAPI

`subscribeStaffRequestUpdates` is declared once in the executable HTTP contract in
`StaffTimelineUpdateRoutes.kt`, with a native route using the same authorization and
preparation. Its 200 body lens is `text/event-stream`, not JSON. Ordinary HTTP GET
executes validation then rejects the missing native Accept negotiation; the native
adapter takes successful streaming requests first. This narrow native routing exception
is pinned in ArchitectureSpec. Generated and served OpenAPI remain the same rendering;
there is no standalone maintained spec or second business implementation.

## fionas-web handoff

1. Expose a streaming BFF route through the admin origin and use native browser
   `EventSource` there. Forward the actual staff session cookie server-side and set
   `Accept: text/event-stream`; do not substitute a service token or invented actor header.
   Commerce and provider credentials must never be sent to the browser.
2. Forward status and content type, preserve `no-store, no-transform` and
   `X-Accel-Buffering: no`. Disable response buffering and compression, flush frames
   promptly, propagate downstream cancellation upstream, and use idle timeouts above
   20s. Verify these settings on the actual proxy; local Jetty tests cannot prove them.
3. Listen for `timeline.changed`. Both initial synchronization and later changes refresh
   `GET /staff/requests/{inquiryId}/timeline`. Event IDs and payload revisions are opaque.
   Ignore Last-Event-ID for replay. Reconnection must refresh even if the token repeats.
4. Coalesce refreshes, but remember notifications received during a fetch and run another
   fetch after it finishes. Preserve composer text, original send expectations,
   unknown-outcome/same-key recovery and disclosure state across refreshes.
5. Expect backend disconnects at 60s, during deployments, on revocation and failures.
   Honor the 3s retry hint when received; apply bounded backoff/jitter to repeated
   pre-stream or pre-synchronization failures (no retry frame is guaranteed on refusal).
   Handle 401/403 as authentication/authorization changes, 404 as missing resource, and
   503/network failures as temporary unavailability. Avoid immediate retry loops.
6. Keep the manual **Refresh conversation** action and show disconnected/stale state
   when synchronization has not occurred. SSE never acknowledges, sends, books or edits.

## Verification

The existing V7 PostgreSQL/concurrency and registry suites remain. Added real JettyLoom
socket tests cover simultaneous streams, exact headers/frames, delayed flushing,
communication and decline hints, reconnect, authorization errors, live session/permission
changes, scanner failure/recovery and active-stream shutdown. The backpressure test
amplifies a test-only native payload into a non-reading socket, observes the production
writer blocked inside Jetty, verifies another subscriber and HTTP, and joins that writer
before server stop. Managed-writer tests cover deadlines, keepalives, bounded concurrent
admission, failed writes, blocked scans and cleanup. Financial/lifecycle tests cover
publication/revision, booking, Invoice amendments, split payment histories and refunds,
terminal facts, rejected commands and rollback. Production proxy validation remains a
deployment check, not a claimed local test result.
