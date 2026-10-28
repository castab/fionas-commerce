# Email conversations

Operations and integration guide for Fiona's customer email conversations. The Resend
Conversation Service owns email: transport, delivery, threading, Reply-To routing, message
content and provider conversation state. Fiona owns what that communication means to an
inquiry: the topic relationship, communication facts and attention, staff permissions and
provenance, staff send orchestration, and the unified staff timeline. The architectural
principle is in [`ARCHITECTURE.md`](../ARCHITECTURE.md#email-conversations); the concrete
contract is in [`AGENTS.md`](../AGENTS.md#email-conversations).

## Verified provider contract

Verified against `castab/resend-service` **0.7.2-rc.2** (`public/openapi.json`,
`public/asyncapi.json`, `docs/api-consumer-guide.md` at that version). The older `fionas-ui`
integration pinned `0.7.2-rc.1`; rc.2 adds attachment downloads only, which Fiona does not use.
Fiona uses exactly:

| Provider operation | Fiona use |
|---|---|
| `GET /api/conversations/v2/topics/booking/{inquiryId}` (`limit`, `before`) | Timeline read (all pages, bounded) and the pre-send lookup (`limit=1`) |
| `POST /api/conversations/v2` | The first staff reply, which opens the topic conversation |
| `POST /api/conversations/v2/{conversationId}/messages` | Every later staff reply (synchronous) |
| NATS `conversation.events.v1` schema version 1 | Inbound customer email facts and establishment evidence |

Fiona never calls the state, assignment, delete, outbox or drain routes, never sets a provider
conversation `concluded` or `terminated`, and never sends HTML or attachments.

## Conversation identity

Every inquiry conversation is the provider topic (`booking`, *inquiry UUID*). The inquiry UUID
is the only correlation: never the customer's email, since several inquiries may share one
address. Fiona persists no provider conversation ID as authority; it resolves the topic on each
read and send. It records only:

- `fionas.inquiry_conversation_events`: provider event IDs it processed for an inquiry
  (deduplication), with conversation ID and sequence. Their existence is *establishment
  evidence*: proof that a conversation existed for the inquiry.
- `fionas.inquiry_customer_email_sources`: provider inbound message ID → the one
  `CUSTOMER_EMAIL_RECEIVED` communication fact it produced.
- `fionas.inquiry_staff_replies`: one staff reply command per `Idempotency-Key`; accepted rows
  are establishment evidence too.

No table holds a subject, address, body, HTML, header, routing token or delivery state.

### Missing versus unavailable

| Provider answer | Meaning to Fiona |
|---|---|
| `200` for the topic | The conversation exists (`READY`) |
| `404`, no establishment evidence | A legitimate inquiry with no conversation yet (`EMPTY`) |
| `404`, with establishment evidence | An established conversation unexpectedly missing (`MISSING`): deleted, or a provider data problem |
| `401`/`403` | The provider refused Fiona's credential (`FORBIDDEN` on the timeline) |
| anything else, timeout, malformed body, wrong topic | `UNAVAILABLE` |

Only `EMPTY` lets a staff reply open a new conversation. A timeout, credential error or
ambiguous lookup is never taken as absence, and `MISSING` never opens a replacement.

### Reply-To routing and the initial limitation

Fiona configures only the untagged Reply-To *base* (`FIONAS_CONVERSATION_REPLY_TO_ADDRESS`);
the provider generates and keeps the per-conversation tagged routing address on the first
outbound intent. Fiona never constructs, stores, logs or returns that address.

Automatic acknowledgement emails are deliberately deferred, so a conversation exists only after
a staff member's first reply. Until then a customer has no routing address, and an unsolicited
email from them reaches the provider unthreaded: the provider cannot associate it with the
booking topic, and Fiona never guesses an inquiry from a sender address. Such mail is handled
in the provider (its unassigned-conversation tools), not in commerce. Once staff reply, the
customer's replies thread through RFC ancestry and the routing address, and arrive on the topic.

## Conversation event consumer

An optional managed consumer inside `fionas-commerce` reads the provider's lifecycle events
directly from JetStream. There is no HTTP relay, service principal, recording endpoint or
separate worker deployment.

### Broker resources (operator-provisioned)

| Resource | Value |
|---|---|
| Stream | `RESEND_CONVERSATIONS`, the stream the provider's `CONVERSATION_EVENTS_NATS_STREAM` names |
| Subject | `conversation.events.v1` (the provider's `CONVERSATION_EVENTS_NATS_SUBJECT`) |
| Durable pull consumer | `fionas-commerce-conversations` |

The consumer's configuration is verified before every pull and must match the inquiry intake
shape exactly: name and durable `fionas-commerce-conversations`, the single filter subject
above, explicit ack, deliver all, instant replay, ack wait 90s, max deliver 10, max ack pending
1, max waiting 2, max batch 1, max expires 5s, no push subject, backoff or inactivity
threshold. A mismatch halts the consumer. Fiona never creates or mutates the stream or
consumer. Delivering all from the stream start is safe: processed event IDs are deduplicated.

Least-privilege grant for the dedicated identity (no stream INFO or administration):

```text
publish:   $JS.API.CONSUMER.INFO.RESEND_CONVERSATIONS.fionas-commerce-conversations
           $JS.API.CONSUMER.MSG.NEXT.RESEND_CONVERSATIONS.fionas-commerce-conversations
           $JS.ACK.RESEND_CONVERSATIONS.fionas-commerce-conversations.>
subscribe: _INBOX.>
```

### Configuration

| Variable | Meaning | Default |
|---|---|---|
| `FIONAS_CONVERSATION_INTAKE_ENABLED` | Enables the consumer | `false` |
| `FIONAS_CONVERSATION_INTAKE_NATS_URL` | Broker URL | required when enabled |
| `FIONAS_CONVERSATION_INTAKE_NATS_CREDS_FILE`, or `..._NATS_USER` + `..._NATS_PASSWORD`, or `..._NATS_TOKEN` | Exactly one authentication method; prefer a per-identity creds file | required when enabled |

These are parsed by the same secret-safe scoped parser as `FIONAS_INTAKE_*` and are
independent of it: either consumer, and the outbox publisher, may run without the others.
Enabling it requires the same bounded database settings as inquiry intake (see
[inquiry intake](inquiry-intake.md#bounded-database-shutdown)).

### Startup, shutdown and status

The consumer is contributed through the runtime's `backgroundServices`, starts with the
runtime and constructs without network I/O. It processes one delivery at a time on one
virtual thread (`fionas-conversation-intake`) using the existing runtime pool. Shutdown stops
pulls, lets the current delivery complete within the existing grace, and closes the transport
before the pool. A halt affects only this consumer: HTTP, inquiry intake and the outbox keep
running. Logs carry `worker=fionas-conversation-intake` with `event=intake_retrying`,
`intake_halted` or `intake_stopped` and a code, never payloads.

### Validation and disposition

Every payload is strict UTF-8 JSON of at most 64 KiB, validated against the closed v1 schema:
`schemaVersion` exactly `1`, UUID `id` and `conversationId`, positive integer `sequence`, known
`type` with its exact property set (unknown properties are a contract mismatch), the allowed
`actor`/`cause` pair for the type, `occurredAt`, topic (`null` or `{type, externalId}`), and
`messageId`, `state`, `deliveryState` where the type requires them.

| Event | Disposition |
|---|---|
| Unsupported version, unknown type, extra/missing property, malformed | **Halt**, unacknowledged, for operator repair; never interpreted |
| Topic `null` or a type other than `booking` | Ignored and acknowledged |
| `booking` topic naming no Fiona inquiry | Ignored and acknowledged (`event=conversation_ignored code=unknown_inquiry`); no inquiry or customer is created |
| Already processed event ID, identical payload | Acknowledged; no new fact |
| Reused event ID with a different payload, or a reused `(conversationId, sequence)` | Halt (contract violation) |
| `conversation.message.received` | One `CUSTOMER_EMAIL_RECEIVED` fact per provider message ID, at the email's `occurredAt` |
| Any other booking event | Recorded as processed (establishment evidence); no communication fact |

Outbound intent and delivery events never create communication facts: the provider actor is a
service, not a Fiona staff user, so `STAFF_EMAIL_SENT` comes only from Fiona's own send
operation with the authenticated USER. A delivery update can therefore never fabricate a second
sent fact or regress attention.

Database unavailability, serialization or lock conflicts and unconfirmed acknowledgements are
retried (NAK 10s/30s/2m/5m/15m); delivery attempt 10 halts. Acknowledgement happens only after
the transaction commits, or after an explicit ignore.

### Ordering, idempotency and freshness

The event ID is the delivery identity; the message ID is the semantic identity of an inbound
email. Duplicate deliveries of one event, and distinct events naming the same message, converge
on the one fact. Provider `sequence` is checked for uniqueness only. Fiona keeps no projection
of provider state that depends on event order, so there is nothing to regress: attention uses
Fiona's own `recorded_order`, assigned under the inquiry row lock when the fact is appended.
A message ingested after an acknowledgement stays outstanding even if its `occurredAt` is
older. Streams may begin above sequence 1, have gaps, or replay after restart; none of this
matters to the facts recorded.

**No backfill.** Events published while the provider sink or this consumer was disabled are
not reconstructed. Customer emails received before enablement do not create attention. The
timeline still shows them, because it reads messages from the provider.

## Attention and acknowledgement

`needsReply` means *unresolved customer communication under Fiona's recorded policy*, not the
provider's `awaiting_us`: inbound recorded after the latest staff reply or acknowledgement
(by `recorded_order`), on an inquiry that is not DECLINED. It is exposed, never stored, on
`GET /staff/requests` (`needsReply`, `awaitingReplySince`), `GET /staff/requests/{id}`
(`communication`), the dashboard and the timeline.

`POST /inquiries/{id}/communications/acknowledge` requires `{"throughRecordedOrder": n}`, the
`communication.throughRecordedOrder` the staff member saw (`0` for an empty history). Under the
inquiry append lock, any inbound fact recorded after `n` is `409 conversation_changed`, and a
watermark that is not one of the inquiry's facts is `422`. Acknowledgement only records a
commerce fact; it never changes the provider conversation state.

## Staff timeline

`GET /staff/requests/{id}/timeline` returns provider messages and commerce milestones in one
list. Commerce history comes from one REPEATABLE READ snapshot; the provider read follows it
and is not atomic with it. Items sort by `occurredAt`, then by their stable `id` (lexical).
This is presentation order: provider timestamps and Fiona's clocks are different domains, so it
claims no causal order.

Milestones reuse authoritative history: `QUOTE_PUBLISHED` from each proposal with its exact
Quote snapshot and deposit revision, `INVOICE_AMENDED` for each later Invoice snapshot,
`PAYMENT_RECORDED` and `REFUND_RECORDED` from the canonical lineage's payment histories,
`COMMUNICATION_ACKNOWLEDGED`, `SERVED`, `CLOSED` and `DECLINED` from their facts. Historical
snapshots never show today's amounts. A financial milestone never implies an email was sent.

`conversation.state` is `READY`, `EMPTY`, `MISSING`, `UNAVAILABLE` (also when reads are
disabled) or `FORBIDDEN` (the caller lacks `fionas.communications.read`, or the provider refused
Fiona). Commerce milestones are present in every state; message content only for `READY`.
Messages carry plain `text` only, with whitespace preserved; provider HTML, routing tokens and
diagnostic text never cross the boundary.

## Staff replies

`POST /staff/requests/{id}/conversation/replies` requires `fionas.communications.send`, a staff
USER, the trusted Origin for cookies, an `Idempotency-Key`, and exactly one of
`expectedLatestMessageId` (the timeline's `conversation.latestMessageId`) or
`expectEmptyConversation: true`. The recipient is the inquiry's customer; From, Reply-To and the
signature come from configuration and the authenticated user's runtime display name.

1. Validate (body trimmed, 1–5,000 characters, no control characters except newline, carriage
   return and tab). An existing record for the key short-circuits to step 6.
2. Refuse when sending is disabled (`503 email_sending_disabled`) or the customer's address is
   not allowed (`422 email_recipient_not_allowed`).
3. Look up the topic. Unavailable or refused: `503 email_provider_unavailable`, nothing
   written. A newer message than expected: `409 conversation_changed`. Missing with evidence:
   `409 conversation_missing`.
4. Commit the command record: key, fingerprint (inquiry, sender, body, expectation), mode,
   digest of the exact provider request, signature name. No body.
5. Send outside any transaction with provider key
   `fionas-commerce:inquiry:{inquiryId}:staff-reply:{key}:v1`.
6. Classify: `pending`/`accepted` is durable success and records `STAFF_EMAIL_SENT` (USER,
   server time) with the provider message ID, once; `400`/`401`/`404`/`413`/`415` or a `502`
   `failed` result is a definitive rejection (`502 email_send_rejected`), recorded so replays
   repeat it; `409`, other `5xx`, timeouts and malformed success are ambiguous and replayed once
   with the identical request and key; still ambiguous, or `502 indeterminate`, is `202 UNKNOWN`.

| Hazard | Behavior |
|---|---|
| Double submission / same key again | Same record; exact provider replay or the stored result. One email. |
| Same key, different content | `409 IDEMPOTENCY_KEY_REUSED` before any provider call |
| Provider success, local database failure | The record has no outcome; the same-key retry replays the request, the provider returns the stored message, and the fact is recorded once |
| Timeout, `5xx`, conflict | One exact replay; then `UNKNOWN`, never failure |
| Restart during a send | The committed record survives; the same-key retry reconciles |
| Staff retry after `UNKNOWN` | Retry with the same key and body. A new key with the old expectation is refused with `conversation_changed` if the first reply landed |
| Configuration changed before a replay | The rebuilt request no longer matches the stored digest: internal failure, nothing sent; reconcile manually |
| Duplicate outbound-intent events | Ignored for facts; no second `STAFF_EMAIL_SENT` |

No PostgreSQL transaction spans the provider call. Remaining race: a customer email can arrive
between the lookup and provider acceptance; the reply is then sent without staff having seen it,
and the new inbound (recorded after the reply) correctly stays outstanding. Acceptance is never
delivery: delivery appears later on the timeline from the provider's own message state.

An abandoned `UNKNOWN` record (never retried) leaves no `STAFF_EMAIL_SENT` even if the provider
sent the email; the timeline shows the provider truth. Retry the key to repair commerce.

### Configuration

| Variable | Meaning | Default |
|---|---|---|
| `FIONAS_CONVERSATION_READ_ENABLED` | Enables timeline message reads | `false` |
| `FIONAS_CONVERSATION_SEND_ENABLED` | Enables staff replies | `false` |
| `FIONAS_CONVERSATION_BASE_URL` | Provider origin; HTTPS (HTTP only for loopback), no path or credentials | required when either is enabled |
| `FIONAS_CONVERSATION_API_KEY` | Provider `EMAIL_v2_API_KEY` | required when either is enabled |
| `FIONAS_CONVERSATION_READ_TIMEOUT_MS` | Total timeline read budget (100–10000) | `5000` |
| `FIONAS_CONVERSATION_SEND_TIMEOUT_MS` | Per provider request (1000–30000) | `10000` |
| `FIONAS_CONVERSATION_FROM_ADDRESS` | Sender; needs a provider `FROM` allowlist row | required for sending |
| `FIONAS_CONVERSATION_FROM_NAME` | Sender and Reply-To display name | `Fiona's Ice Cream` |
| `FIONAS_CONVERSATION_REPLY_TO_ADDRESS` | Untagged Reply-To base; needs a `REPLY_TO` row; fixed per conversation by the provider | required for sending |
| `FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST` | Comma-separated exact customer addresses, or `*` for any (production only) | required for sending |

Reads and sends are independent: with sending disabled, staff still read conversations. Never
log the API key, bodies, HTML, payloads or routing addresses; Fiona's logs carry only event
codes and inquiry IDs.

## Permissions

| Permission | Grants |
|---|---|
| `fionas.communications.read` | Message content on the timeline (USER or SERVICE) |
| `fionas.communications.send` | Staff replies (USER only) |
| `fionas.communications.acknowledge` | Acknowledgement (USER or SERVICE), unchanged |

The timeline also requires `fionas.inquiries.read` and `commerce.financial-document.read`. New
bootstrap Administrators receive all three communication permissions; existing roles are never
changed by startup: add the grants through `/admin/access` with read-modify-replace.

## Deployment and cutover

1. Deploy with everything disabled; migration `V5` adds the three tables.
2. Grant `fionas.communications.read`/`.send` to staff roles that should have them.
3. In the provider: add `FROM` and `REPLY_TO` allowlist rows; enable the NATS sink.
4. Provision the stream, consumer and dedicated identity above; enable
   `FIONAS_CONVERSATION_INTAKE_*`. Confirm `intake_consumer_status` logs and no halt.
5. Enable reads; check a timeline shows `EMPTY` or `READY`.
6. Enable sending with a pre-production allow-list of test addresses; send a first reply to a
   test inquiry, reply from the test mailbox, and verify the inbound fact (`needsReply`), the
   acknowledgement conflict on a stale watermark, and the timeline's delivery state.
7. Widen the allow-list (`*`) only for production.

Rollback: disable the flags. Recorded facts and command records remain valid history.

## Deferred and upstream

- **Change signal.** There is no `timeline/head` or ETag yet. Commerce-side changes (inbound,
  sent, acknowledgement, financial and lifecycle milestones) are cheap to detect in Fiona, but
  delivery-state changes are not recorded in commerce, and computing them would need a provider
  read on every poll. A trustworthy head needs either a Fiona-owned record of delivery-update
  events or a cheap provider revision; until then clients refresh the timeline explicitly.
  `GET /staff/requests/{id}` `communication.throughRecordedOrder` is a cheap signal for new
  communication facts only.
- Automatic acknowledgement, Quote or Invoice emails; attachments; HTML; provider state
  changes from Fiona; unassigned-conversation assignment; historical backfill.
- No commerce-domain or commerce-runtime change was needed.
