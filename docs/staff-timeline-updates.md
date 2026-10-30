# Staff timeline invalidation: foundation and runtime dependency

## Availability

**SSE is not available in this change.** `GET /staff/requests/{inquiryId}/updates`
is not mounted, advertised in OpenAPI, or enabled by a flag. No stream, background
poller, connection, executor, or provider request is started by this foundation.
The existing timeline, replies, acknowledgement and attention contracts are unchanged.

The independent Fiona work is durable communication revisions, a bounded batch read,
and a transport-independent subscription registry. Native transport integration,
authentication of streams, keepalives and connection lifetime enforcement await a
released runtime composition capability. Do not deploy a frontend subscription yet.

## Implemented revision contract

Fiona migration V7 adds `fionas.inquiry_change_revisions`: inquiry FK/primary key and
positive BIGINT revision, with no customer, email or financial content. Existing
inquiries start at the implicit baseline `0`; historical facts are not backfilled.
No timestamp, provider sequence, message count or financial version acts as a revision.

Two AFTER INSERT triggers advance the counter in the source transaction:

| Source | Changes covered |
|---|---|
| `inquiry_conversation_events` | Every newly accepted provider event, including outbound intent, delivery updates and conversation state; no provider state is copied |
| `inquiry_communications` | Inbound communication facts, accepted staff replies, acknowledgements and existing direct communication adapters |

`ON CONFLICT DO NOTHING` on an identical provider event does not fire an insert
trigger. Replayed accepted replies do not append a communication and do not advance
the counter. A rejected/unknown reply command alone does not advance it. A genuinely
new event ID naming an already recorded inbound message still adds establishment
evidence and advances the token; it does not add a second communication. An inbound
event that creates a fact advances twice. Tokens are opaque invalidation identities,
not business-event counts; multiple changes can be coalesced into one notification.

Counter UPSERTs serialize on the inquiry's revision row and roll back with the source
facts. Existing operations already take the inquiry communication lock before these
writes. No new lock order, transaction boundary, retry loop or runtime-table access is
introduced. Overflow fails and rolls back rather than wrapping. Do not reset counters.
Triggers cover old application instances after V7 is applied, including rolling deploys.

`ReadInquiryTimelineRevisions` performs one short transaction with one indexed batch
query, bounded to 256 inquiry IDs. It joins only inquiry existence and the counter;
it never loads the timeline or calls Resend. Unknown inquiries are omitted; existing
inquiries without counters return zero. This is an internal operation, not an endpoint.

## Implemented subscription core

`InquiryTimelineSubscriptions` has at most 256 subscriptions, at most 16 for one
inquiry, and exactly one pending revision slot per subscription. Capacity exhaustion
returns no subscription. Each `refresh()` reads distinct active inquiry IDs once,
then replaces pending tokens with newer ones. It never calls a client or writes a
socket. A slow consumer does not delay another consumer or accumulate history.

Every new subscription gets the current revision on its first refresh, including
zero. Reconnecting always gets a fresh synchronization token. Subscribe during an
in-flight scan is picked up by the next scan. Committing during a read is likewise
observed by the next scan. No ephemeral wakeup or LISTEN registration can be lost.
Concurrent scans are suppressed. Duplicate or lower observed revisions are ignored
within a subscription; tokens need no ordering logic in the frontend.

Closing removes subscriptions and clears their slots, even while a read is in flight;
late results cannot resurrect them. A database read exception closes affected
subscriptions and propagates to the future transport owner, without an error payload.
The registry owns no threads and cannot cancel the caller's database operation.
The eventual managed dispatcher must enforce the existing bounded pool/JDBC settings
and join its worker before pool shutdown. An idle registry performs no query.

The intended distribution is one server-side scan per second per instance, only for
active inquiries. It works across commerce instances against the same PostgreSQL
database without sticky sessions, per-browser database connections or LISTEN/NOTIFY.
This is bounded counter sampling on the server; the browser uses SSE. The scanner is
not composed in this change.

## Exact upstream boundary

Audited against [commerce-runtime v0.0.26](https://github.com/castab/commerce-domain/blob/v0.0.26/runtime/src/main/kotlin/io/github/castab/commerce/runtime/CommerceRuntime.kt)
and the resolved http4k **6.58.0.0** artifacts. `ApplicationContributions.routes`
returns `List<RoutingHttpHandler>`. Runtime constructs
`CommerceErrorHandling.then(routes(...))` and calls `http.asServer(JettyLoom(port))`.
There is no native SSE contribution point. Fiona cannot replace that server or
upgrade http4k independently.

The smallest proposed reusable addition, to implement and release upstream first:

```kotlin
// New optional member of ApplicationContributions; retain routes unchanged.
val sseRoutes: (CommerceRuntimeContext) -> List<RoutingSseHandler> = { emptyList() }
```

Runtime composes one `org.http4k.core.PolyHandler(http = http, sse = ...)` and starts
that through its existing JettyLoom server. Keep `CommerceRuntime.http`, health/ready,
HTTP filters, migrations, context construction and managed-service ownership intact.
Expose native SSE types through the runtime's supported dependency surface. Add no
inquiry, revision, topic or email concepts upstream.

**A new field alone is insufficient acceptance evidence.** The pinned native
`SseResponse` has status, headers and consumer, but no ordinary HTTP error body.
Runtime must preserve `ErrorResponse` for pre-stream rejection, including exceptions,
without committing a 200 first. A maintainable candidate is a shared application
handshake used by the SSE matcher and the executable HTTP contract: rejected native
matches fall through (`handled = false`) to the HTTP contract and runtime error filter.
Verify this fallback on real Jetty; do not recreate error serialization inside Fiona.
Unhandled SSE paths must still fall through to ordinary HTTP, including `/health`.

The pinned `JettyEventStreamHandler` is a nonblocking handler and invokes its SSE
consumer after response commitment. Handshake I/O, the consumer, keepalives and slow
socket writes need explicit execution/lifetime tests: native APIs alone do not prove
freedom from starvation. Consumers must register bounded work and return promptly.
Runtime must stop accepting streams and close active native connections with bounded
shutdown, including partially failed startup, before its shared pool is released.

No upstream artifact was changed or released here. The dependency remains 0.0.26.

## Remaining Fiona integration and financial coverage

Before mounting the route, implement a managed dispatcher and native SSE writer with
bounded write deadlines, disconnect cleanup and shutdown. Reuse runtime authentication
and live AccessControl for a human USER with **all three** read permissions:
`fionas.inquiries.read`, `commerce.financial-document.read`,
`fionas.communications.read`. Check inquiry existence only after authorization.
Return existing 400/401/403/404/error conventions before opening the stream.

Revalidate the original credential and current permissions, not the cached
`authenticatedPrincipal` on the original request. The runtime authentication filter
deliberately reuses an established principal. A proposed bound is a 60-second maximum
connection lifetime, also capped by session expiry, with revalidation before each
notification and at least every 20 seconds. Revocation, failure to revalidate and
permission removal close the stream without a private error event. Verify expiry and
revocation with actual sessions before adopting these bounds.

The implemented revision currently covers **communication only**. Complete timeline
coverage remains an integration requirement:

| Milestone | Correct future revision write boundary |
|---|---|
| Quote publication | Fiona proposal append, in its existing transaction |
| Invoice amendment | Fiona orchestration after the supported ledger mutation, in the same transaction, including amendments without a service-plan write |
| Served, closed, declined | Fiona operational-fact repository mutations in their existing transactions |
| Payments, allocations, refunds | Fiona orchestration using supported ledger reads to identify every affected inquiry, in the same transaction; preserve lock ordering, including cross-lineage histories |

Do not add triggers or queries on `commerce` tables, copy financial state, or infer
that every ledger write produces a Fiona row. Direct upstream ledger writes cannot
be observed without a supported runtime change notification capability; decide that
requirement explicitly if such writers must be supported. These financial changes
are not silently claimed as covered by this foundation.

## Proposed frontend and operations contract (not yet exposed)

After the above release and integration gates, the target is:

```text
GET /staff/requests/{inquiryId}/updates
Content-Type: text/event-stream
Cache-Control: no-store
X-Accel-Buffering: no

event: timeline.changed
id: 42
data: {"inquiryId":"<uuid>","revision":"42"}
```

Use native SSE event/comment primitives. Emit only inquiry UUID and decimal revision
string; no personal information, message content, provider IDs, amounts or diagnostics.
Send a synchronization event on every connection even if Last-Event-ID equals the
current token. Last-Event-ID is not replay: the server stores no event replay buffer.
The BFF must forward the staff credential and stream through the admin origin; the
browser receives no commerce credentials, NATS access or Resend access.

On each synchronization/change event, fetch the existing authoritative timeline.
Coalesce refreshes, but if another notification arrives while a fetch is in flight,
schedule another read after it completes. Never infer reply acceptance, delivery,
acknowledgement, needs-reply or booking state from a token. Reconnection always
refreshes, even after database restoration or a token lower than a prior connection.
Provider outage can still yield an unavailable timeline; a revision does not guarantee
provider availability or cross-system atomicity. Restore/recovery must disconnect old
streams rather than resume their in-memory comparison against restored counters.

The eventual route should send keepalive comments about every 20 seconds. Disable
buffering and response compression for the stream at the BFF and reverse proxy; flush
frames promptly and set idle timeouts above the heartbeat interval. Enforce a bounded
absolute connection lifetime and allow reconnect during deployment. Verify these
settings end to end; a healthy `/health` alone says nothing about notification freshness.

Deploy V7 before any new stream-capable binary. Keep provider event intake enabled and
monitor its existing retry/halt status; an event never ingested cannot advance a
revision. Release the runtime, consume that released version, complete transport and
financial integration, then deploy the BFF. No browser subscription is safe before
those gates. Counter rows have one row per changed inquiry and no independent retention
job; do not purge them while retaining the inquiry.

## OpenAPI and verification gates

No endpoint metadata is added for an endpoint that does not exist. On integration,
retain a single executable route description: its HTTP handshake contract should own
operationId `subscribeStaffRequestUpdates`, read security and error statuses, with
200 media type **text/event-stream**, not a JSON DTO. Native matching and documentation
must share the path/handshake rather than maintain a standalone spec. Any routing
exception needs a narrow ArchitectureSpec guard and served/generated OpenAPI parity
tests. Confirm the native fallback mechanism upstream before choosing this shape.

Foundation tests cover PostgreSQL source/counter rollback, commit visibility,
same-inquiry contention, deduplication, out-of-order delivery events, staff reply replay,
acknowledgement conflicts, isolated registries, subscription capacity/coalescing,
subscribe/read races, disconnect/reconnect, failed reads and close during a stalled read.

**Release-blocking tests still required:** stream auth/permission/session expiry and
revocation, real native disconnects, blocked socket writers, keepalive and maximum
lifetime, runtime shutdown, deployed proxy behavior, and at least two simultaneous SSE
connections to the **actual commerceRuntime JettyLoom composition** while repeatedly
checking ordinary reads and `/health` with deadlines. This foundation does not run or
claim that test. The prior [starvation incident](https://github.com/castab/fionas-ui/blob/main/docs/incidents/2026-09-04-admin-live-updates-event-loop-starvation.md)
is why no pipe, InputStream response, independent production server or in-memory-only
claim of working SSE is acceptable.
