# Customer payments: fionas-web handoff

The frontend owns `/pay/{publicId}`. Commerce owns access, authorizations and reconciliation;
the runtime ledger owns all received money and settlement. No frontend implementation is
included here. Generate clients from the backend's `/openapi.json` or
`build/openapi/fionas-commerce-openapi.json`, never from the examples alone.

## Staff link management

All endpoints require a real staff USER session, trusted Origin for POST, and all of
`fionas.payment-access.manage`, `fionas.inquiries.read`, and
`commerce.financial-document.read`. The new permission is registered but **not granted to
any role**, including new Administrators. An authorized administrator must explicitly
grant it through existing role administration. A SERVICE cannot manage links even with
every permission. The BFF forwards the actual staff cookie, never a service identity.

| Method and path | operationId | Behavior |
| --- | --- | --- |
| GET `/staff/requests/{inquiryId}/payment-access` | `getInquiryPaymentAccess` | Read existing link; no mutation |
| POST same path | `issueInquiryPaymentAccess` | Issue, reactivate revoked access, or return the existing stable link |
| POST same path + `/rotate` | `rotateInquiryPaymentAccess` | Replace bearer secret immediately, keeping publicId |
| POST same path + `/revoke` | `revokeInquiryPaymentAccess` | Immediately reject future public reads/checkout; no effect on reconciliation |
| GET `/staff/requests/{inquiryId}/payment-attempts` | `listInquiryPaymentAttempts` | Provider diagnostics and outcomes |
| POST `/staff/requests/{inquiryId}/payment-attempts/{attemptId}/reconcile` | `reconcileInquiryPaymentAttempt` | Recover an existing attempt from Stripe; never authorize new money |

POSTs have no body. Issue/rotate is allowed for QUOTED, BOOKED and SERVED only. CLOSED
permits retrieval of an existing active link and revocation, but no new or rotated access.
Reissuing an already active CLOSED link just returns it without mutation. REQUESTED and
DECLINED cannot acquire access. No automatic email is sent.

Example staff response (the fragment below is illustrative):

```json
{
  "publicId": "b8251ea0-c07c-4aad-bceb-644ed075c0a1",
  "revoked": false,
  "url": "https://fionas.example/pay/b8251ea0-c07c-4aad-bceb-644ed075c0a1#<43-character-secret>"
}
```

Before issuance: `{"revoked":false}`. After revocation: `publicId` and `revoked:true`,
with no `url`. Nullable response properties are omitted, following CommerceJson. Treat
staff URLs as credentials: copy only on a deliberate staff action; never log, instrument,
store in analytics, or include in an error report. Rotation invalidates every old copy.

## Stable page and bearer transport

The URL fragment carries the secret, so normal HTTP access logs and referrers never carry
it. On the browser page, read the fragment locally, save it in tab-scoped `sessionStorage`
under the publicId, then remove it with `history.replaceState`. Do not use localStorage,
cross-tab synchronization, analytics, server-rendered props, or customer-email lookups.
The public ID alone is not authorization. An invalid, unknown, rotated or revoked bearer
returns the same `404 not_found` response. A missing/repeated header is malformed (`400`).

Proxy same-origin browser requests through the fionas-web BFF to commerce. Send the secret
in exactly one `X-Fionas-Payment-Token` header. Do not add a staff cookie or SERVICE token.
Do not enable broad cross-origin access to commerce. Filter this header from BFF, CDN and
APM logs. Never send it to Stripe. A copied link works in a new tab because it includes
the fragment; Stripe redirects rely on the original tab's sessionStorage. If that storage
is lost, ask the customer to reopen the original link; do not invent a credential recovery
endpoint.

Every response is `Cache-Control: no-store`. Serve the page and BFF with the same policy,
`Referrer-Policy: no-referrer`, and `X-Robots-Tag: noindex, nofollow`; include a robots
meta tag. Do not prefetch these routes, cache them in a service worker, or load third-party
scripts on the payment page. Backend URLs contain only nonsecret public IDs.

## Current summary

`GET /public/payments/{publicId}` (`readCustomerPaymentSummary`), with the bearer header,
reads one unlocked REPEATABLE READ snapshot. It returns no name, email, message, staff
note, service-plan note, conversation, decline reason, provider credential or bearer.
Example:

```json
{
  "inquiryId": "f693da9b-bca4-4107-99dc-c1e68b922af6",
  "eventDate": "2027-05-01",
  "lifecycle": "QUOTED",
  "document": {
    "id": "f79d5d1c-cc9e-4e08-a231-a1ea1696330e",
    "version": 3,
    "stage": "QUOTE",
    "businessRevision": 4,
    "currency": "USD",
    "totalAmount": "1200.00"
  },
  "mode": "DEPOSIT",
  "requiredAmount": "240.00",
  "paidAmount": "0.00",
  "balance": "1200.00",
  "canPay": true,
  "paymentInProgress": false,
  "reconciliationPending": false,
  "history": []
}
```

Amounts in the summary are exact **major-unit decimal strings**, never floats. Display
them using exact decimal arithmetic. `paidAmount` is ledger net applied to this canonical
lineage; `balance` is its authoritative total minus net applied and can be negative after
manual overpayments/amendments. History contains only `amount`, `refundedAmount` and
`receivedAt` for each canonical-lineage receipt. For split manual receipts these amounts
include only the allocations and refund unwinds belonging to this inquiry's lineage;
amounts funding unrelated inquiries are never exposed. Outstanding unapplied provider money is signaled by
`reconciliationPending`, not counted as applied or paid obligations. After staff verifies
and records a full refund or deliberately allocates it through existing ledger operations,
that derived block clears; the historical attempt outcome remains visible to staff.

Show “Quote v{businessRevision}” or “Invoice v{businessRevision}”. `document.version`
is the immutable ledger version used for concurrency, never the customer-facing label.
After booking the latest Invoice defines obligations; `document.acceptedQuoteRevision`
identifies the unchanged accepted Quote evidence. The same public link works through
amendments, SERVED and CLOSED. CLOSED is read-only; payment never closes an inquiry.

`mode` is DEPOSIT while QUOTED, otherwise INVOICE. `requiredAmount` is the exact positive
approved deposit or current Invoice balance. Never accept an amount field on a deposit
form. For Invoices, allow an optional partial payment, in integer cents, at least 50 cents
and no greater than the current positive balance or 99,999,999 cents. If a whole Invoice
balance exceeds Stripe's maximum, require a smaller partial payment. The backend
independently checks it.

When `canPay:false`, show `unavailableReason`: CLOSED, NOT_PUBLISHED,
UNSUPPORTED_CURRENCY, RECONCILIATION_PENDING, PAYMENT_IN_PROGRESS, NO_PAYMENT_DUE,
BELOW_STRIPE_MINIMUM or ABOVE_STRIPE_MAXIMUM. Treat unknown reasons as unavailable.
There is no “paid” fact derived from the browser redirect.

## Checkout creation

`POST /public/payments/{publicId}/checkout-sessions` (`createCustomerCheckoutSession`).
Send the bearer, `Content-Type: application/json`, and exactly one `Idempotency-Key`:
1–128 ASCII letters/digits/underscore/hyphen, case-sensitive and untrimmed. Generate one
UUID **per logical payment click**, persist it with the exact body in sessionStorage, and
reuse both for double clicks and all ambiguous retries. Disabling a button is insufficient.

```json
{"expectedDocumentVersion":3,"expectedBusinessRevision":4}
```

For a partial Invoice payment only:

```json
{"expectedDocumentVersion":6,"expectedBusinessRevision":8,"amountMinor":10000}
```

Omitted/null `amountMinor` selects the current complete deposit or Invoice balance. A
deposit amount, if supplied, must exactly equal the approved deposit. Amounts below 50
cents or above 99,999,999 cents fail; there is no rounding. Prices, currency, purpose,
proposal and approved deposit all come from commerce. The expected versions must match
the summary the customer reviewed; a new Quote/service/deposit/Invoice revision requires
a fresh read and deliberate payment action.

```json
{
  "attemptId": "8cf42464-739b-43ca-b684-d0f802245c55",
  "url": "https://checkout.stripe.com/c/pay/...",
  "state": "OPEN",
  "outcome": "PENDING"
}
```

Navigate the same tab to `url` only when state is OPEN and a URL is present. This is a
short-lived credential; never persist it beyond the current operation or send it to
analytics. There is one active attempt per inquiry across all instances and keys.
Same-key retries recover the original authorization, including after terms change; they
never silently substitute new terms. A response without a URL requires refreshing the
summary and handling the returned state.

Stripe uses one card-only hosted line item, not a mirrored catalog. No subscriptions,
saved card setup, discounts, tax recalculation, BNPL or delayed bank methods. Card wallets
are available according to Stripe/browser eligibility. Adaptive pricing is disabled.

## Returns, pending work and errors

Trusted redirects are exactly `{configured-origin}/pay/{publicId}?checkout=returned`
or `?checkout=cancelled`. They contain no token or client-chosen origin. Restore the secret
from the same tab's sessionStorage. A success or cancel parameter is only navigation:
fetch the summary; never declare a payment received from a query parameter. Cancel does
not expire the session or release its reservation. Keep the pending key/body and resume
with that same key until Stripe confirms completion/expiry.

While a payment is in progress, show “Payment confirmation pending” and suppress another
payment. Poll the summary with bounded backoff while visible. If it remains pending after
an interrupted checkout, retry the saved checkout command to retrieve/reconcile that
session. A confirmed expired/failed attempt releases the reservation; only then create a
new key for a new deliberate payment. An ambiguous creation never releases merely because
time passed. Creation replay is limited to the first 25 minutes; later recovery discovers
the original provider session and never recreates it after Stripe idempotency retention.

When `reconciliationPending:true`, show “We received a payment that needs staff review.
Please contact Fiona's before paying again.” Never show BOOKED unless the backend says
BOOKED. A captured stale deposit can remain QUOTED, with its money safely unapplied.

All errors use runtime `ErrorResponse` (or its validation response): `code`, `message`.

| Status/code | UI behavior |
| --- | --- |
| 400 `malformed_request` | Fix body/header shape; do not automatically invent a new key |
| 404 `not_found` | Link unavailable; ask staff for a current link |
| 409 `conflict`, message `PAYMENT_TERMS_CHANGED` | Refresh; require review before a new key/payment |
| 409 `conflict`, message `CHECKOUT_KEY_REUSED` | Programming error: changed input reused a durable key |
| 409 `conflict`, message `PAYMENT_IN_PROGRESS` | Resume saved command, otherwise await confirmation/contact staff |
| 409 `conflict`, message `RECONCILIATION_PENDING` | Stop collection and contact staff |
| 409 `conflict`, message `CHECKOUT_CREATION_UNKNOWN_REQUIRES_RECONCILIATION` or `STRIPE_DISCOVERY_LIMIT_REQUIRES_RECONCILIATION` | Preserve key; staff recovery needed |
| 422 `validation_failed` | Show safe validation; never reduce a deposit to a partial payment |
| 503 `stripe_disabled` | Online payment unavailable; preserve all other staff/manual workflows |
| 500 `internal_failure`, timeout, lost response | Outcome unknown: retry SAME key/body with bounded backoff |

Do not interpret arbitrary internal failure messages. New unavailable reasons/codes must
fail closed for collection. Staff 401/403 is an authentication/permission problem, not a
reason to substitute a service credential.

## Staff visibility and operational handoff

Payment-attempt responses contain IDs, purpose, authorized cents, reviewed versions,
created/expiry times, provider state, outcome, optional Session/PaymentIntent/ledger IDs,
and a static diagnostic. They omit provider idempotency keys and link secrets. APPLIED
means a committed ledger allocation; RECONCILIATION_REQUIRED means the receipt is in
the ledger but was not allocated automatically. Never calculate settlement from attempts.

Existing timeline/SSE stays the refresh mechanism. An applied payment uses the existing
payment invalidation path atomically with booking/receipt. A stale capture advances its
inquiry revision so the staff workspace can refresh payment attempts and the existing
unapplied queue. There is no new financial lifecycle event or fake timeline payment
allocation for unapplied money. Public customers do not subscribe to staff SSE.

Implement staff copy/revoke/rotate controls separately from send-email controls; no automatic
email is introduced. Refresh both authoritative staff detail and attempt diagnostics after
`timeline.changed`. The operational manual for external refunds/disputes, webhook setup,
recovery and production acceptance is [stripe-payments-operations.md](stripe-payments-operations.md).
