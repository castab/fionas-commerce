# Stripe Checkout operations

## Configuration and rollout

Stripe is disabled by default. Disabling it starts no Stripe client and preserves inquiry,
staff/session, manual payment, refund, amendment and SSE behavior. V10 still migrates the
Fiona integration tables. All payment-integration routes remain in OpenAPI and return
503 `stripe_disabled` when disabled (staff authorization runs first).

```text
FIONAS_STRIPE_ENABLED=true
FIONAS_STRIPE_LIVE_MODE=false
STRIPE_SECRET_KEY=<Stripe test secret key>
STRIPE_WEBHOOK_SECRET=<snapshot destination signing secret>
FIONAS_STRIPE_ACCOUNT_ID=<acct_... owning the payments>
FIONAS_PUBLIC_BASE_URL=https://<fionas-web-public-origin>
FIONAS_PAYMENT_ACCESS_KEY=<base64 of 32 cryptographically random bytes>
```

Use an HTTPS origin with no path, trailing slash, userinfo, query or fragment. Test mode
alone permits `http://localhost:<port>`. Live mode must explicitly be true and requires
an `sk_live_` key; false requires `sk_test_`. There is no fallback. Use Railway secret
variables, never Docker build arguments or a committed env file. All instances must use
the same public origin, account, environment and access derivation key. Configuration
validates before route composition; SDK construction performs no network calls.

The adapter uses official `stripe-java` **34.1.0**, pinned to **2026-10-28.endive**. It
enforces that SDK/API pairing at construction and rejects any other signed snapshot
version. Network calls use the configured Stripe account context, 5s connect / 10s read
timeouts, and zero implicit SDK retries; explicit command retries retain the durable key.
No live infrastructure, webhook registration or Stripe account setting is changed by this PR.

Railway checklist:

1. Deploy with Stripe disabled; confirm V10 and existing health/readiness/manual workflows.
2. Configure all test-mode variables, identical across replicas. Keep payment routes and
   the dedicated webhook on HTTPS. Do not put staff/session authentication in front of
   `/integrations/stripe/webhook`, or rewrite/parse its body at a proxy. Limit body size at
   the edge as well; the contract rejects bodies over 256 KiB and invalid UTF-8.
3. Configure a **your-account snapshot event destination** for the exact API version above,
   posting to `https://<commerce-host>/integrations/stripe/webhook`. Select:
   `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `checkout.session.async_payment_failed`, `checkout.session.expired`, `charge.refunded`,
   `refund.created`, `refund.updated`, `refund.failed`, and all `charge.dispute.*` events.
   The four Checkout events are supported even though delayed methods are excluded.
   PaymentIntent events alone never credit money; select the Checkout events.
4. Set the destination's `whsec_` secret. Enable the integration, confirm a signed delivery
   reaches it, and explicitly grant `fionas.payment-access.manage` to the intended staff
   role through runtime administration. Existing/new Administrator roles are not expanded.
5. Build the frontend according to the [handoff](stripe-payments-frontend-handoff.md).
   Exclude token headers, fragments, staff link responses and Checkout URLs from logs,
   traces, analytics, CDN caches and error reporters. Configure no-store/noindex/no-referrer.
6. Complete the test-mode acceptance exercise below, including duplicate delivery, lost
   response recovery, republication, manual payment races and external refund reporting.
7. Before live rollout, establish staffed exception monitoring and reconciliation ownership,
   verify the actual owning-account context, card/wallet eligibility and currency minimum,
   and separately provision live credentials/destination. Do not reuse test attempts or
   test data in a live deployment. A backend build is not a live payment acceptance test.

## Local Stripe CLI exercise

The official [webhook guide](https://docs.stripe.com/webhooks) covers CLI forwarding.
Authenticate the CLI to the intended **test account**, then:

```sh
stripe listen --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,charge.refunded,refund.created,refund.updated,refund.failed,charge.dispute.created,charge.dispute.updated,charge.dispute.closed,charge.dispute.funds_withdrawn,charge.dispute.funds_reinstated --forward-to localhost:8080/integrations/stripe/webhook
```

Use the listener's signing secret for this local process. Confirm that forwarded snapshots
have API version `2026-10-28.endive`; configure the destination/account or CLI version
appropriately rather than weakening backend verification. Generic `stripe trigger`
Checkout fixtures do not own a Fiona durable attempt and cannot book an inquiry.

Create a real priced inquiry via the existing test web authority, publish a canonical
Quote, issue its link, and create a session through the public endpoint. Open its hosted
URL and use a Stripe test card (for example the documented successful test card
`4242 4242 4242 4242`, future expiry, any test CVC). Verify the signed completion produces
one ledger receipt, one complete deposit allocation, Invoice/business revision and BOOKED.
Pay a partial and then final Invoice amount with the same public link. The inquiry remains
BOOKED/SERVED until staff explicitly closes it. Redeliver the same event and another
completion for the same session; neither adds money. Test only with documented test data:
[Stripe testing](https://docs.stripe.com/testing).

## Transaction and recovery contract

V10 creates only `fionas.inquiry_payment_access`, `fionas.stripe_payment_attempts`, and
`fionas.stripe_webhook_events`. It adds one FK to the published runtime USER key, no
runtime DDL and no local financial projection. Amount/currency in an attempt describe a
provider authorization, not settlement. A partial unique index permits one CREATING,
OPEN or PROCESSING attempt per inquiry. The existing canonical association lock serializes
staff terms, link authorization and receipt application across instances.

Checkout authorizes and commits an attempt first, calls Stripe outside PostgreSQL, then
commits the provider result. Its UUID supplies the permanent Stripe idempotency key.
Provider parameters are immutable, including one-hour expiration and trusted returns.
Replays during the first 25 minutes use the same provider key. After that, bounded
provider discovery (at most 1,000 sessions in the authorization's creation window) finds
the existing session. It never creates a replacement after the provider's
[idempotency retention](https://docs.stripe.com/api/idempotent_requests) may have elapsed.
If discovery is incomplete or cannot establish a session, the reservation remains held
for operator investigation; a clock or a missing local response is not proof of failure.

The session's client-reference UUID and amount alone do not establish ownership. A
server-generated HMAC proof binds the durable authorization, exact amount, expiry and
provider key. Reconciliation validates it, the session/PaymentIntent identities, account
context, environment, currency and amount. Paid Checkout must be complete; the SDK adapter
also retrieves a succeeded PaymentIntent with exactly the received amount. An unpaid
completed session is PROCESSING, never received money. Unknown or mismatched sessions
return non-success and require investigation; no metadata-only ledger writes occur.

Webhooks verify the original raw body with the official SDK, then retrieve authoritative
Stripe state outside a transaction. In one transaction they lock the canonical association,
validate the immutable attempt and current business terms, and either call the existing
transaction-taking `RecordDocumentPayment` core or record an unapplied ledger payment.
Canonical deposit validation/booking remains exactly the existing path. Invoice application
requires the authorized document/business revision and an adequate current balance.
Payment external references are provider `stripe`, reference `{acct_id}:{pi_id}`; runtime
uniqueness plus the durable attempt's unique PaymentIntent/ledger IDs guard double credit.

The event identity/disposition commits with the financial outcome. A crash or database
failure returns non-2xx, rolls back everything, and Stripe retries. Different events for
the same payment rediscover its committed ledger identity. Old failure/expiry events
cannot undo a receipt; authoritative current Stripe status decides. There is no volatile
queue and no success response before commit. Configure upstream HTTP timeouts to allow
provider reads plus database work; a lost webhook response is safe to redeliver.

Use `POST /staff/requests/{id}/payment-attempts/{attemptId}/reconcile` after a restart,
ambiguous response, or missed webhook; it works after access revocation. It retrieves or
discovers the original session, and expires an open session whose reviewed terms are now
obsolete where possible. Expiration does not prove that a capture race is impossible;
any later confirmed capture remains reconcilable. No automatic periodic provider sweep
or inbox worker is added. Operators must monitor pending attempts and Stripe delivery
failures and invoke recovery or redelivery before provider event retention expires.

## Exceptions, refunds and disputes

Logs contain static diagnostic codes and event/attempt/object IDs, never payment page
secrets, credentials, raw events or customer fields. Monitor non-2xx webhook deliveries,
CREATING/PROCESSING attempts without progress, and RECONCILIATION_REQUIRED outcomes. Staff
can inspect inquiry attempts via the authenticated endpoint and use `GET /payments/unapplied`
for runtime-derived receipts. Existing SSE invalidation tells the staff UI to refresh
both detail and attempts; unapplied money never fabricates an allocation/timeline receipt.

External refund/dispute notifications are deliberately **exceptions**, not automatic
refunds or allocation reversals. Their signed event/object/PaymentIntent IDs are durably
stored even if delivered before the original payment, and logged at ERROR. Inspect:

```sql
SELECT event_id, event_type, object_id, payment_intent_id, received_at
FROM fionas.stripe_webhook_events
WHERE disposition = 'EXTERNAL_ADJUSTMENT'
ORDER BY received_at, event_id;
```

This queries only Fiona-owned integration facts. Match PaymentIntent IDs to the attempt
and its ledger receipt; for dispute/charge records use Stripe's Dashboard to resolve the
charge if necessary. Independently verify the actual amount, environment and completed
refund or dispute disposition with Stripe. Do not infer a refund from an initiated refund,
an open dispute or a funds-withdrawn notification. An operator must record confirmed money
returned through the existing shared refund API with the Stripe refund reference and
explicit allocation unwinds. Those operations require their existing financial permissions;
link-management permission grants no refund authority. Never reverse allocations, change
BOOKED or close an inquiry speculatively. Track event IDs in the operator incident record.

For stale deposits, leave the receipt unapplied and the inquiry QUOTED. Do not force it
through a different proposal or split it into a canonical deposit. Staff can return the
actual money in Stripe and then record the verified refund through existing APIs, or
apply unapplied money to a valid Invoice using the existing allocation policy when appropriate.
The integration outcome remains historical evidence that automatic application failed; no
API silently acknowledges or erases it. The public collection block derives outstanding
unallocated value through the runtime payment history: a verified full refund or deliberate
allocation removing all unallocated value allows collection again without rewriting the
historical attempt. This and external adjustment monitoring must have an owner before
production use; there is no automatic refund/dispute reconciliation UI.

## Secret and retention tradeoff

Each inquiry has an immutable public UUID and a random 256-bit generation nonce. Its
256-bit bearer is HMAC-SHA256 under `FIONAS_PAYMENT_ACCESS_KEY`; only a SHA-256 hash of
that bearer is persisted. Staff GET derives it without modifying state. Rotation replaces
the nonce/hash under the association lock; old secrets fail immediately. This meets stable
retrieval without storing individual recoverable bearer strings, but the derivation key
is a high-value credential: compromise plus database access can reconstruct all links.
Protect it separately from the database, back it up securely, and use identical values
on all replicas. Do not casually replace it: existing staff link retrieval and provider
authorization proofs depend on it. Incident-driven global key rotation requires a deliberate
cutover/reconciliation plan for outstanding sessions. Ordinary per-inquiry secret rotation
does not affect in-flight receipt reconciliation.

Retain attempts, event identities and ledger links for durable deduplication and support;
there is no TTL/purger. Never delete attempts to retry a payment. Never automatically
delete an ambiguous reservation or hand-edit ledger/runtime tables. Back up both schemas
and the independently managed key. Reconciliation-required flags and integration receipts
are not a second credit ledger.

## Production limits

No live Stripe call is part of CI. Fake-gateway PostgreSQL tests and official SDK signature
tests establish local contracts; the owning-account/header behavior, actual snapshot
destination configuration, wallet availability and end-to-end browser return need the
test-mode exercise above. No frontend, payment emails, automatic refund/dispute mapping,
periodic recovery scheduler, exception-resolution UI, customer accounts or saved methods
are included. Ambiguous absence after the replay window fails closed and may require
operator/provider support. No additional commerce-runtime capability is required.
