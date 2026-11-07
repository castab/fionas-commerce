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

Staff can separately [preview/send a financial email](financial-emails-frontend-handoff.md).
That operation resolves the stable link on the server, reuses active access and issues only
missing access atomically with send intent. Revoked access still needs deliberate authorized
reactivation. Preview/send command responses never return its bearer. Authorized conversation
readers can see it in original or quoted provider text; exclude those bodies from observability.
The explicit link-management endpoints retain their permission boundary and behavior.

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
meta tag. Do not prefetch these routes, cache them in a service worker, or load unrelated
scripts on the payment page. Use official Stripe.js for Embedded Checkout and Checkout
Elements. Backend URLs contain only nonsecret public IDs.

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
  "checkoutRecoveryAvailable": false,
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

Show “Quote” or “Invoice” without numeric revisions; remove the “Booked from Quote vN”
caption. Retain `businessRevision`, `document.version` and `acceptedQuoteRevision` internally
for concurrency and accepted-publication identity. `document.version` remains the immutable ledger version.
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
  "uiMode": "HOSTED",
  "url": "https://checkout.stripe.com/c/pay/...",
  "state": "OPEN",
  "outcome": "PENDING"
}
```

For an open embedded Session:

```json
{
  "attemptId": "8cf42464-739b-43ca-b684-d0f802245c55",
  "uiMode": "EMBEDDED",
  "clientSecret": "<Checkout Session client secret>",
  "state": "OPEN",
  "outcome": "PENDING"
}
```

For an open Elements Session:

```json
{
  "attemptId": "8cf42464-739b-43ca-b684-d0f802245c55",
  "uiMode": "ELEMENTS",
  "clientSecret": "<Checkout Session client secret>",
  "state": "OPEN",
  "outcome": "PENDING"
}
```

When OPEN, dispatch on the response's `uiMode`. HOSTED navigates the same tab to `url`.
EMBEDDED initializes official Stripe.js Embedded Checkout with `clientSecret`. ELEMENTS
mounts the custom form described below. Treat unknown modes or invalid credential shapes
as a checkout error and retain the original key/body for recovery. Configure the frontend
with the Stripe publishable key for the same merchant and environment. The customer bearer
still authenticates every BFF-to-commerce request. Existing links and fragment/sessionStorage
bearer handling remain unchanged; client secrets never replace the payment bearer.

Only OPEN customer responses carry launch credentials, exactly one of `url` or `clientSecret`;
nullable fields are omitted. PROCESSING, SUCCEEDED, EXPIRED and FAILED carry neither.
Refresh the summary and handle the returned state. Missing open-session launch credentials
fail with `409 checkout_reconciliation_required`, preserving the attempt for same-key
recovery or investigation. Never replace an authorization to obtain a client secret.
Staff reconciliation has a separate status-only response (`attemptId`, `uiMode`, `state`,
`outcome`). Staff attempts and customer summaries contain no launch credentials.

Do not log, persist (including sessionStorage), trace, instrument, or place client secrets
in URLs, analytics, server-rendered props or error reports. Hold them only in memory for
Stripe.js; retrieve them again through authenticated Checkout using the same key/body.
Treat hosted URLs as short-lived credentials too.

There is one active attempt per inquiry across all instances, modes and keys. Same-key
retries retain the original authorization and presentation across configuration or terms
changes. Stripe uses one card-only line item, no mirrored catalog, subscriptions, saved
card setup, discounts, tax recalculation, BNPL or delayed bank methods. Adaptive pricing
is disabled; card wallets depend on Stripe/browser eligibility.

Embedded uses `redirect_on_completion=if_required`. On Stripe.js completion, refresh the
authoritative commerce summary with bounded polling while settlement is pending. Completion
callbacks cannot mark Invoices paid or Quotes BOOKED: verified reconciliation and the runtime
ledger establish received money. Staff still control SERVED/CLOSED. Required authentication
redirects return to the trusted payment page with no bearer or client secret in the URL.

### Implementing the Elements form

1. In the ELEMENTS branch, use Stripe.js for Checkout Sessions with the in-memory
   Session secret. With the JavaScript SDK, call
   `stripe.initCheckoutElementsSdk({ clientSecret, elementsOptions: { appearance } })`
   and load the Checkout actions. In React, import `CheckoutElementsProvider`,
   `useCheckoutElements`, `PaymentElement`, and `ContactDetailsElement` from
   `@stripe/react-stripe-js/checkout`. Pass `clientSecret` and `elementsOptions.appearance`
   in the provider options. Mount the form per attempt and release it when the attempt
   changes or leaves OPEN. See [Stripe's Checkout Elements integration](https://docs.stripe.com/payments/accept-a-payment?payment-ui=elements&api-integration=checkout).
2. Own the page layout, custom Pay button, and Elements Appearance API configuration in
   fionas-web. Match Fiona's Poppins typography, cream `#F6F1E4` and olive `#5F5B40`.
   Load Poppins through `elementsOptions.fonts` so it can render inside
   Elements. Apply page typography and controls outside Elements in the site itself.
   Commerce pins the Elements branding profile to NONE. See the
   [Appearance API](https://docs.stripe.com/elements/appearance-api).
3. Collect a valid email for Stripe Session confirmation. Mount Contact Details Element
   alongside Payment Element, or validate a customer-entered email and pass it with the
   Checkout SDK's `updateEmail` or `confirm`. The commerce summary carries no customer
   contact data. Checkout email collection does not update the Fiona customer record.
4. Enable the custom Pay button when Checkout is ready and `canConfirm` is true.
   Prevent repeated clicks while confirmation is pending. Use Checkout's
   `actions.confirm({ redirect: "if_required" })` in JavaScript or the equivalent
   `checkout.confirm` from `useCheckoutElements` in React. Use the Session's configured
   return URL. Handle loading and recoverable confirmation errors in the form and keep
   sensitive request/response data out of telemetry. See
   [Checkout confirmation options](https://docs.stripe.com/js/custom_checkout/confirm).
5. On a successful confirmation or return from authentication, show pending settlement and
   refresh the bearer-authenticated commerce summary with bounded polling. Only commerce
   reconciliation establishes received money. If mounting fails, recover with the same
   key and request body. On EXPIRED or FAILED, refresh the summary before presenting a new
   deliberate payment action.

Deploy the backend with the existing `embedded` or `hosted` setting. Deploy frontend
handling for all three variants, then select `elements` in test mode and exercise a fresh
authorization before production activation. Switching back to the previous mode applies
to future authorizations. Keep all three frontend branches available for existing Sessions.
See the [rollout and test-mode checklist](stripe-payments-operations.md#verify-elements-in-test-mode).

## Returns, pending work and errors

Trusted redirects are exactly `{configured-origin}/pay/{publicId}?checkout=returned`
or `?checkout=cancelled`. They contain no token or client-chosen origin. Restore the secret
from the same tab's sessionStorage. A success or cancel parameter is only navigation:
fetch the summary; never declare a payment received from a query parameter. Cancel does
not expire the session or release its reservation. Keep the pending key/body and resume
with that same key until Stripe confirms completion/expiry.

`checkoutRecoveryAvailable` (optional, default false) is a local hint that an active attempt
can be checked by an explicit POST. It does **not** mean Stripe has expired it. GET remains
lightweight and side-effect-free, and performs no provider I/O. Do not hide all actions
solely because `canPay:false` / `PAYMENT_IN_PROGRESS`: when recovery is available, offer
“Check previous payment and continue”. Keep the ordinary Pay button disabled.

If the original key/body are available, retry them to recover exactly that authorization.
A new key without restart intent can check one previous Session and proceed after
confirmed expiration or failure. An open Session keeps its reservation under this flow.

### Restart Checkout

When the summary includes `checkoutRestartToken`, offer **Restart checkout**, even
with `canPay:false` / `PAYMENT_IN_PROGRESS`. Show the current deposit or let the
customer select a permitted Invoice amount. Explain that restarting closes the old
unpaid Checkout. On a deliberate click, send a fresh UUID key and the reviewed body
to the same POST, with the opaque token copied as `restartToken`:

```json
{
  "expectedDocumentVersion": 6,
  "expectedBusinessRevision": 8,
  "amountMinor": 25000,
  "restartToken": "<checkoutRestartToken from the summary>"
}
```

Omit `amountMinor` for a full deposit. The token targets the reviewed attempt and
financial state. Keep the same key and exact body, including the token, for retries
after an ambiguous response. The old browser key is unnecessary. Page load, summary
polling, mounting failures and normal retries should never trigger a restart.

```json
{
  "attemptId": "8cf42464-739b-43ca-b684-d0f802245c55",
  "replacedAttemptId": "99ba495c-4747-4dc4-87ed-69357de3cba9",
  "uiMode": "ELEMENTS",
  "clientSecret": "<fresh Checkout Session client secret>",
  "state": "OPEN",
  "outcome": "PENDING"
}
```

`replacedAttemptId` identifies the expired attempt. For an OPEN response, mount
against the new attempt ID and launch credential using the existing mode handling.
If the summary no longer offers restart and shows `canPay:true`, let the customer
review current terms and start ordinary Checkout. Use the pending state when the old
payment is processing. If payment already succeeded, fetch the summary before offering
any further payment.

Recovery conflicts below create no new authorization and claim no new key. They must not
start a fresh-key retry loop. Poll the summary with bounded backoff while visible; another
recovery POST is a deliberate check. An ambiguous transport/500 result can still have
committed a new authorization: preserve and retry the SAME key/body. Once a key did create
an authorization, all later identical retries still target it, even after a replacement
exists; changed input under that key conflicts. A successful old-key replay of EXPIRED or
FAILED is historical and does not create a replacement—use a fresh deliberate command.

Creation replay is limited to the first 25 minutes; later recovery discovers the original
provider Session and never recreates it after Stripe idempotency retention. Discovery
failure, truncation or absence without conclusive identity stays blocked for staff review.

Example recovery conflict (runtime ErrorResponse, no provider credentials):

```json
{"code":"payment_processing","message":"An existing payment is awaiting provider confirmation; do not start another payment"}
```

When `reconciliationPending:true`, show “We received a payment that needs staff review.
Please contact Fiona's before paying again.” Never show BOOKED unless the backend says
BOOKED. A captured stale deposit can remain QUOTED, with its money safely unapplied.

All errors use runtime `ErrorResponse` (or its validation response): `code`, `message`.

| Status/code | UI behavior |
| --- | --- |
| 400 `malformed_request` | Fix body/header shape; do not automatically invent a new key |
| 404 `not_found` | Link unavailable; ask staff for a current link |
| 409 `conflict`, message `PAYMENT_TERMS_CHANGED` | Refresh; require review before a new key/payment |
| 409 `payment_terms_changed` | Restart review is stale. Fetch the summary and require a fresh deliberate decision |
| 409 `conflict`, message `CHECKOUT_KEY_REUSED` | Programming error: changed input reused a durable key |
| 409 `checkout_key_reused` | A restart and another command used the same key with different input. Preserve the original body for replay |
| 409 `checkout_in_progress` | Original Session is still open. Offer deliberate restart if the refreshed summary provides a token |
| 409 `payment_processing` | Provider payment is unsettled. Show pending and suppress new collection |
| 409 `payment_state_changed` | Recovery recorded a payment. Fetch summary and require a fresh decision before any further payment |
| 409 `checkout_recovery_conflict` | A competing command changed attempts. Refresh; do not follow it in an automatic recovery loop |
| 409 `checkout_reconciliation_required` | Provider identity/outcome remains uncertain. Retry the same key/body with bounded backoff, then offer support |
| 409 `conflict`, message `RECONCILIATION_PENDING` | Stop collection and contact staff |
| 422 `validation_failed` | Show safe validation; never reduce a deposit to a partial payment |
| 503 `stripe_disabled` | Online payment unavailable (disabled, or a deployment merchant mismatch; no attempt was authorized); preserve all other staff/manual workflows |
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

Stripe is a direct merchant integration. Commerce verifies the API key's own account;
the configured merchant ID does not become a `Stripe-Account`/Connect header. The BFF
continues to send only Fiona's customer bearer header, never Stripe credentials or account
routing headers. Real merchant Checkout/wallet and signed webhook connectivity still need
the operations runbook's Stripe test-mode acceptance exercise.
