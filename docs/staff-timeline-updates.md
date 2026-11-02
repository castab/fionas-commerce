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
baseline `0`. Each newly admitted backend subscription synchronizes again. `Last-Event-ID` is ignored: there
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
facts (served insert, changed closed timestamp). V9 adds an AFTER INSERT trigger for
AMENDED facts in `inquiry_invoice_amendments`, covering both financial and service-only
amendments through `InquiryInvoiceRevisions` and canonical Invoice `CreateChangeOrder`.
Their previous explicit financial increments are removed, so each amendment has one
authoritative invalidation source. INITIAL issuance uses its booking payment's existing
increment. RELATED snapshots create no canonical amendment or invalidation. Quote
publications, including deposit/service republication, keep their proposal trigger.

Quote items expose `proposal.quoteRevision`. Invoice items expose `amendment.invoiceRevision`,
the exact `financial` snapshot, service approval reference and staff `actor`. Service-only
amendments retain the ledger version and still appear once as INVOICE_AMENDED. Invoice IDs
include inquiry, zero-padded business revision and immutable amendment UUID, preserving
revision order for equal approval timestamps. Business and ledger revisions are unrelated
to the opaque SSE revision: keep the `timeline.changed` protocol unchanged. Refresh staff
detail and timeline after a hint; never derive a business revision from the hint or item count.

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
notifications must use Fiona operations. V9 is a pre-production cutover requiring old
instances to stop; booked development databases require an explicit operator reset.
There is no mixed-version rollout for the new business revision contract.
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

### Three responsibilities

Commerce reports the truthful result of a subscription attempt: 200 for a successful
SSE response, 400 for malformed requests, 401 for missing/invalid authentication, 403
for insufficient permissions, 404 for a missing inquiry, 500 for unexpected failures,
and 503 for disabled capability or temporary unavailability according to the actual
response. Preserve the backend's runtime `ErrorResponse` contract. Commerce must not
mask its failures as successful streams.

The admin-origin `fionas-web` BFF preserves the real upstream result internally and
translates it into browser-facing reconnect or terminal behavior. Native browser
`EventSource` consumes the same-origin BFF stream; its normal callbacks, including
`onerror`, cannot inspect commerce HTTP status codes. Do not implement status-based
browser decisions in `onerror`. To explain unavailability, use an existing ordinary
authenticated application request or a documented application state transition.

The [historical incident](https://github.com/castab/fionas-ui/blob/main/docs/incidents/2026-09-04-admin-live-updates-event-loop-starvation.md)
and [PR #238's Chromium evidence](https://github.com/castab/fionas-ui/pull/238)
showed no automatic reconnect after HTTP 503, but reconnect after a successful short
200 SSE response containing a retry directive and ending normally. Apply that transport
lesson here; do not copy the obsolete frontend implementation or its event names.

### Failure handling at the BFF

Before commitment, the BFF chooses the browser-facing response. After commitment,
only stream termination is available, as distinguished below:

| Commerce/backend condition | BFF/browser-facing behavior |
| --- | --- |
| Successful 200 `text/event-stream` | Proxy the stream, preserving framing and flushing. |
| Temporary 503 | Short, valid 200 SSE retry placeholder, then clean close. |
| Temporary upstream network/connection failure | Same retry placeholder, with bounded retry policy. |
| Temporary 500 or 502 | Placeholder only when appropriately classified as transient; not every 5xx is recoverable. |
| 401 unauthenticated | Terminal response, normally browser-facing 204. |
| 403 forbidden | Terminal response, normally browser-facing 204. |
| 404 missing inquiry | Terminal response, normally browser-facing 204. |
| Permanently disabled SSE capability | Stop automatic reconnection, normally with 204. |
| Unexpected invalid request | Terminal failure; never create an automatic retry loop. |
| Established SSE connection closes normally | Allow native EventSource reconnection. |
| Established SSE connection closes for revocation, permission loss or expiry | Reconnection must pass authentication again; the later handshake decides whether to terminate. |

In this implementation, **503 `timeline_updates_unavailable` explicitly means the
feature is disabled**. It is terminal, not a recoverable infrastructure interruption.
Classify the actual upstream response, not just its status. A later enablement can
create a new EventSource through a deliberate application state change or page lifecycle;
intentional disablement must not cause infinite reconnect attempts.

Once a real SSE response has been committed, the BFF cannot change its HTTP status.
When upstream ends or fails, terminate the stream cleanly, without inventing an SSE
error event or transmitting internal diagnostics. Normal stream termination permits
native EventSource reconnection, including deployment and the backend's 60s maximum
lifetime. Closure for revocation, permission loss or expiry does not bypass authentication:
the next backend handshake rechecks the credential and permissions, and its rejection
must be translated into a terminal response as above.

### Retryable placeholder

For a classified temporary upstream failure before response commitment, the BFF may send:

```text
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-store, no-transform

retry: 5000


```

End this response cleanly after the retry directive and its terminating blank line.
The 5000ms interval is illustrative; document the chosen frontend retry policy. The
backend's normal live stream advertises 3000ms. A valid HTTP 200, SSE content type and
retry directive allow native reconnection after EOF. This is solely a browser-facing
BFF response, not a change to commerce failure semantics.

The placeholder emits no business event and **no `timeline.changed`**. It makes no
claim that a real commerce subscription was established. Opening it must not trigger
an authoritative timeline fetch. For repeated temporary failures or connections that
end before synchronization, use bounded backoff and jitter where the BFF/frontend
controls retry policy; document interval caps and any retry budget/resumption policy.
Avoid immediate reconnect loops and repeated work during outages. A post-commit backend
admission refusal can close without an initial event or retry directive; do not assume
every opened transport receives the normal retry hint.

### Synchronization and refresh correctness

`EventSource.onopen` means only that an SSE transport response opened, not that the
subscription synchronized. Every successfully admitted backend subscription sends an
initial `timeline.changed`, including revision zero:

```text
event: timeline.changed
id: 42
data: {"inquiryId":"<uuid>","revision":"42"}


```

Both the initial notification and subsequent `timeline.changed` notifications trigger
`GET /staff/requests/{inquiryId}/timeline`. A fresh subscription's initial notification
must refresh even if its revision repeats the previous connection's token. Neither
`onopen`, a retry placeholder nor a transport heartbeat refreshes the timeline. Normal
reconnection does not imply event replay: `Last-Event-ID` is ignored and is neither an
authoritative business version nor a replay contract.

Coalesce redundant refresh requests, but remember a hint received during an in-flight
fetch and run another refresh after it completes when necessary. Preserve unsent
composer text, original message-send expectations and idempotency keys, unknown-outcome
recovery, and user disclosure/expansion state. Revisions never establish email delivery,
payment or booking outcomes. Keep the manual **Refresh conversation** control and show
disconnected/stale state when synchronization has not occurred. Unavailable SSE must
not prevent staff from using existing request-management functionality. SSE never
acknowledges, sends, books or edits.

### Authentication and successful-stream proxying

The browser connects to the same-origin BFF with EventSource. The BFF forwards the
authorized staff request with the actual `__Host-fionas_session` cookie and
`Accept: text/event-stream`. Never replace it with a SERVICE token or invented actor
header. Commerce service tokens, Resend credentials and NATS credentials stay out of
the browser. The BFF must not duplicate business permissions or become a second
authorization authority; a retry placeholder must never circumvent failed authorization.
Terminal responses transmit no private errors via SSE. Distinguish session expiry,
insufficient permissions or a missing inquiry through an existing authenticated ordinary
HTTP request, or a narrowly justified status mechanism, when the UI needs that detail.

For successful upstream streams, preserve `text/event-stream`, appropriate cache control
(`no-store, no-transform`) and `X-Accel-Buffering: no`. Disable response buffering,
compression and transformations that delay delivery. Flush frames promptly, preserve
ordering/framing, propagate browser cancellation upstream, and never buffer the complete
response. Set idle timeouts above the backend's 20s heartbeat interval and allow streams
to end/reconnect during deployment or maximum-lifetime expiration. Verify these settings
on the deployed proxy; local Jetty tests cannot prove them.

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
