# Financial emails: fionas-web handoff

Staff manually preview and send the current published Quote or a balance-due Invoice in
the inquiry's existing Conversation Service thread. Publishing, revising, amending,
serving and receiving payment never send email. There is no frontend implementation here.
Generate clients from the executable `/openapi.json` contract.
Quote, revised Quote and Invoice greetings use the authoritative customer's first name,
with Unicode whitespace handling and a blank fallback of `there`. HTML escapes that name;
plain text uses the same name. Full names in customer records and event facts are unchanged.
Generated customer copy says `Hi Maria, here's your quote`, `Hi Maria, here's your updated
quote`, or `Thank you, Maria — here's your invoice`. The HTML heading is styled lowercase
(`text-transform`, so its text and screen readers keep that casing); plain text ends it with a
period. Section headings are `Your event`, `Your menu` (only with an approved service plan) and
`Your quote` (with a `Final price` chip) or `Your invoice`. No application-generated `vN` labels
are included in HTML, text or subjects. Staff-authored custom text remains exact, including
deliberate version references. Internal ledger/business/deposit revisions remain in commands,
previews, fingerprints and Activity metadata.

The event block shows date, event type, guests (approved count, else requested, `At least N`
for a minimum) and ZIP code; it no longer repeats the customer's full name. Each line shows its
description, its sub-description and/or exact `quantity × unit rate`, and its exact line total;
totals, required deposit, balance after deposit, net paid and remaining balance are unchanged
authoritative values. Quote emails say `Deposit to hold your date` / `Balance after deposit`
and link with `Review your quote` or `Review your updated quote`. Invoice emails say
`Paid so far` / `Remaining balance` and link with `Review your invoice`. The stable link
shows current terms and payment status. Both alternatives keep neutral payment wording
so the reviewed email remains accurate if checkout state changes before send or delivery.
Both end with `Sent from <staff display name> @ Fiona's Ice Cream`.
Layout is table-based with inline styles and one narrow-screen media query, so Gmail, Outlook
and phone clients render it. The HTML carries a hidden inbox preheader. The neutral wording
changes review tokens for unsent previews. UNKNOWN commands with the previous payment-oriented
wording can still recover when the original collection gate passes and both stored digests match.
For future template changes, resolve UNKNOWN sends first or retain exact reconstruction support.

## Authorization and deployment

Both POSTs require the actual staff USER session, a trusted Origin, and **all five**:
`fionas.inquiries.read`, `commerce.financial-document.read`,
`fionas.communications.read`, `fionas.communications.send`,
`fionas.payment-access.manage`. No roles receive new grants. Preview uses the same boundary
because it reviews a communication containing customer/financial data and payment-access
state for that send. Forward the staff cookie and trusted Origin through the BFF.

Existing Conversation Service send settings and customer payment configuration must be
enabled. Before enabling financial emails, disable click tracking for the actual Resend
sender domain and any downstream URL rewriting; verify a delivered test email's HTML
**and plain-text** `/pay/{publicId}#secret` link. Only then set
`FIONAS_FINANCIAL_EMAIL_LINKS_UNTRACKED=true`. Default false refuses financial preview/send
with `503 financial_email_tracking_not_verified`; ordinary text replies stay available.
This setting is an operator assertion, not an API query or a tracking override. Monitor
domain-setting drift; resetting the assertion immediately blocks new financial dispatch.

The verified Conversation Service **0.7.2-rc.2**
[MessageContentV2 contract](https://github.com/castab/resend-conversation-service/blob/a5a77f378d34178efb8534eaf40009f4c3c9ade3/public/openapi.json)
accepts both `text` and `html` on the existing synchronous create/reply routes. Its
[Resend adapter](https://github.com/castab/resend-conversation-service/blob/a5a77f378d34178efb8534eaf40009f4c3c9ade3/src/lib/email/resend-client.ts)
passes them through without local URL rewriting and offers no per-message tracking control.
[Resend tracking](https://resend.com/blog/open-and-click-tracking) is configured per domain
and rewrites links through redirects. Do not invent an unsupported HTML attribute or
request property to disable it. The deployment must enforce the untracked sender-domain
policy; this application does not call Resend directly.

Migration V12 adds only `fionas.inquiry_financial_emails`, its indexes, published identity
FKs and a Fiona-only timeline invalidation trigger. Deploy with the assertion false.
Conversation readers intentionally include payment links for authorized staff. Maintain
the existing conversation-read permission checks and exclude response bodies from logging,
tracing and analytics on every serving instance, including rollback versions. No new
permission, migration, provider contract or payment authentication is required by this correction.

## Preview

`POST /staff/requests/{inquiryId}/financial-emails/preview`
(`previewInquiryFinancialEmail`), no Idempotency-Key:

```json
{
  "kind": "QUOTE",
  "documentId": "<staff-detail financial.id>",
  "expectedDocumentVersion": 2,
  "expectedBusinessRevision": 2,
  "proposalId": "<staff-detail proposal.id>",
  "expectedDepositRequirementRevision": 1,
  "message": "We would love to scoop for your event.\n\nPlease reply with any questions."
}
```

For `INVOICE`, omit `proposalId` and `expectedDepositRequirementRevision`; use the current
canonical Invoice's ledger version and `currentInvoiceRevision`. For QUOTE use
`currentQuoteRevision`, the latest proposal and its deposit revision. Related documents
and historical publications are ineligible. Identities/versions are mandatory; the browser
supplies no amounts, addresses, HTML, sender or payment URL.

`message` is optional; normalized blank uses the default greeting. Otherwise it is trimmed
at the edges, at most 2,000 UTF-16 units, valid Unicode with no controls except CR/LF/tab.
Internal blank-line paragraphs are preserved. Text is escaped in HTML. A changed message
needs another email preview; it does not republish or re-review the Quote itself.

The `200` no-store response (`FinancialEmailPreviewResponse`) contains `kind`,
`recipientName`, `recipientAddress`, `subject`, `html`, `text`, the existing authoritative
`financial` DTO, `businessRevision`, exact decimal strings `requiredPayment`, `paidAmount`,
`balance`, QUOTE-only `balanceAfterDeposit`, and `reviewToken`. Both rendered alternatives
use `https://example.invalid/payment-preview`, never an issued bearer. Preview writes
nothing, including no payment link or command reservation. Render this HTML in an isolated
preview; block navigation to its inert destination and remote content as appropriate.

QUOTE requires QUOTED, the current published proposal and exact active deposit revision,
USD, no historical allocation and valid online deposit terms. Fiona currently
publishes only positive approved deposits; a payment-oriented zero/no-deposit Quote is
ineligible. The email shows the current Quote's event facts, requested guests, approved service when
present, ordered exact ledger lines/tax/totals, required deposit and total less that deposit.

INVOICE requires SERVED, the current canonical Invoice/business revision, USD and a strictly
positive authoritative balance eligible for online payment. BOOKED, CLOSED, DECLINED, fully paid
and overpaid Invoices remain ineligible. The email shows itemized charges, total, net applied
payments and remaining balance. Pending or unapplied Stripe money is not counted as payment
against the document. Review and send are available during open or processing Checkout, uncertain
provider outcomes and outstanding unapplied money. The email describes the ledger terms and
directs the customer to the payment page for current status. Checkout and reconciliation retain
their own rules. Revoked access and ledger read errors fail closed.

## Send and exact-command recovery

`POST /staff/requests/{inquiryId}/financial-emails` (`sendInquiryFinancialEmail`), with
exactly one `Idempotency-Key`: 1–128 ASCII letters/digits/underscore/hyphen, case-sensitive,
untrimmed and scoped to this inquiry:

```json
{
  "email": { "<all preview request properties>": "<exact reviewed values>" },
  "reviewToken": "<preview reviewToken>",
  "expectedLatestMessageId": "<timeline conversation.latestMessageId>"
}
```

For an EMPTY conversation omit `expectedLatestMessageId` and set
`expectEmptyConversation:true`. Exactly one expectation is required. READY replies preserve
the provider subject and conversation, resolving topic `booking` / inquiry UUID. EMPTY may
open with `Your quote from Fiona's Ice Cream` or `Your invoice from Fiona's Ice Cream`.
MISSING with establishment evidence never opens a replacement. A conversation identity
confirmed by a financial lookup/result is establishment evidence even without acceptance. Unavailable/refused lookup
is not absence. The provider participant must match the authoritative customer address.

Persist one key and the **complete original command** in the editor's recovery state before
POST. A new deliberate resend has a new key and a fresh review. Recovery repeats the same
key, email/message, review token and **original** conversation expectation, even if newer
messages or the email itself now appear. Do not replace the original expectation on retry.

| HTTP | Response meaning |
| --- | --- |
| 201 | New `ACCEPTED` provider intent |
| 200 | Confirmed `ACCEPTED` replay, `replayed:true`; no new send |
| 202 | `UNKNOWN`; may have been accepted. Keep exact command/key and reconcile |

`FinancialEmailSendResponse` carries `sendId`, `outcome`, `replayed`, and when known
`conversationId`, `messageId`, `sendState` (`pending`/`accepted`, or `indeterminate` with UNKNOWN). No bearer is returned.
**Acceptance is not delivery**; delivery is provider-owned and appears separately in history.

The server locks the existing canonical association and runtime payment histories,
rechecks the review, reuses an active link without rotation, and issues a first link
through the existing capability in the same transaction as the command intent. Revoked
access requires deliberate separate staff reactivation followed by a new preview. Network
sending occurs after commit, outside all database locks. Eligibility is the authorization
snapshot; money, terms, access and incoming mail may change while the network call is in
flight. The stable payment page always enforces its current authority; email cannot override it.
Financial emails never acknowledge inbound correspondence, including already recorded mail.

Only identities, staff provenance, references, digests and a dispatch counter are stored.
When an indeterminate provider result names a message, its identities are retained without
asserting acceptance or appending communication. A refused later/competing retry cannot
refute an earlier dispatch; it remains UNKNOWN. The counter distinguishes a sole first
dispatch from an already attempted command, including a crash before its response. The exact
provider request is reconstructed and its saved digest checked on UNKNOWN recovery. Document,
service, ledger payments, customer, access generation, template or provider-configuration changes
can make reconstruction impossible. Checkout state alone does not affect current neutral emails.
When exact reconstruction is unavailable, recovery fails closed and preserves the original key.
An unresolved intent reserves further financial sends
for that inquiry. No background retry, sweep, TTL, force-send or admin “mark failed” exists.

## Refusals

All use runtime `ErrorResponse` and no-store. Keep UNKNOWN recovery data after any ambiguous
network/500 result; a missing response does not prove absence of intent.

| Status/code | Client action |
| --- | --- |
| 400 malformed_request | Fix request shape, UUID or missing/repeated/invalid key |
| 401 / 403 | Reauthenticate or request the actual required permission; no SERVICE substitution |
| 404 not_found | Reload/request no longer exists |
| 422 validation_failed | Fix message, revisions or kind-specific identities/expectation |
| 422 email_recipient_not_allowed | Operator must review the outgoing allow-list |
| 409 financial_email_review_stale | Refresh authoritative detail, then preview again before a new send |
| 409 financial_email_ineligible | Refresh lifecycle/collection state; do not force or fabricate a balance |
| 409 payment_access_revoked | Deliberate authorized reactivation, then fresh preview |
| 409 conversation_changed | Refresh timeline and deliberately review before a **new** command |
| 409 conversation_missing / financial_email_recipient_mismatch | Reconcile the existing provider thread; never recreate it |
| 409 IDEMPOTENCY_KEY_REUSED | Used key carries another intent; recover the original, do not silently replace it |
| 409 financial_email_in_progress | Recover the earlier UNKNOWN command with its original key |
| 409 financial_email_reconciliation_required | Stop dispatch; exact reconstruction is unavailable. Investigate provider acceptance/delivery using safe send IDs; never infer non-delivery from a missing message |
| 502 email_send_rejected | Definitive recorded refusal; same key repeats it. A corrected explicit send needs a new review/key |
| 503 email_sending_disabled / email_provider_unavailable / stripe_disabled / financial_email_tracking_not_verified | Resolve configuration/provider availability, retaining any original command |
| 500 internal_failure | No diagnostics/content escape. Retain command and reconcile; never switch keys automatically |

The deposit-specific eligibility refusal `financial_email_deposit_required` denotes no
active approved deposit; healthy current canonical publications always have one.

If the original message/token is lost, or state drift prevents exact reconstruction, the
provider remains authoritative. Escalate with `sendId`, inquiry and provider identities.
Do not hand-edit command outcomes or fabricate acceptance/communication facts. This slice
does not provide an automatic or browser original-email inspection/reconciliation override.

## Activity and secrets

`GET /staff/requests/{id}/timeline` adds `financialEmails: []` (`FinancialEmailHistoryResponse`):
`sendId`, `kind`, `documentId`, `documentVersion`, `businessRevision`, optional `proposalId`,
`current`, `outcome` (`ACCEPTED`, `REJECTED`, `UNKNOWN`), `requestedAt`, optional `acceptedAt`,
USER `actor`, optional `conversationId`, `messageId`, `sendState`, `deliveryState`.
An outbound MESSAGE may carry the matching optional `financialEmail` metadata. This is the
same provider message, not an extra financial milestone.

Derive never emailed from no accepted/unknown command, current version emailed from an
ACCEPTED/current record, and previous-version-only from accepted records without a current
one. Keep rejected attempts visibly distinct from acceptance. Current business revision
matters for service/deposit-only republication, even when ledger versions match. Use the
provider `sendState`/`deliveryState` for pending, delivered or failed presentation; absent
provider state means unavailable, never successful delivery. UNKNOWN always needs recovery.

MESSAGE adds a static `textVisibility` classification:

| Value | Body handling |
|---|---|
| `READABLE` | Complete non-null provider text, including an empty string, financial originals, replies, payment links and quoted/forwarded history. |
| `PROVIDER_CONTENT_UNAVAILABLE` | Provider supplied no text (null/omitted). Show unavailable content rather than an empty customer message. |

`textOmittedForPaymentSecurity` is deprecated and always false (clients must tolerate it
being omitted as a default). The two protected/unverified enum states are removed. Provider
HTML is still excluded; display `text` as text, never raw HTML. No quote parser/redactor or
separate original-message operation is needed. Financial command existence or outcome,
including UNKNOWN, never suppresses provider content. The post-provider command-history
read remains for correlation with newly committed sends, not content authorization.

The existing timeline requires `fionas.inquiries.read` and `commerce.financial-document.read`;
provider content additionally requires `fionas.communications.read`. A caller lacking that
permission receives FORBIDDEN and no MESSAGE items, while commerce history and financial-email
command metadata remain available. No additional grant or payment-access permission is needed
to read correspondence. Preview/send retain all five existing permissions.

Payment URLs may intentionally appear in authorized staff messages and forwarded customer
mail. Possession grants only the existing limited payment view/initiation capability. Public
summary and Checkout still require the bearer; revocation and current-term validation are
unchanged. Public responses contain no customer/contact names, email, phone, addresses,
private notes, conversation text or detailed service descriptions, and never echo the bearer.
Keep all bodies, URLs and tokens out of logs, traces, analytics, errors and diagnostics.

Frontend clients should display complete provider text for READABLE, including financial
emails and customer replies after sending. Use an unavailable placeholder only for
PROVIDER_CONTENT_UNAVAILABLE. Keep financialEmail metadata, message IDs, ordering,
send/delivery states, current/historical document relationships, normal reply composition
and acknowledgement watermarks. EMPTY, READY, MISSING, UNAVAILABLE and FORBIDDEN remain distinct.

Quote/Invoice publication/amendment history remains independent. Accepted sends append one
STAFF_EMAIL_SENT with authenticated USER and a zero clearing boundary, so no customer mail
is acknowledged on their behalf. V12 command changes and the existing communication/provider
triggers invalidate SSE; refresh both detail and timeline. Preserve the existing
acknowledgement watermark semantics. Apply no-store and exclude response bodies, tokens,
rendered emails and payment URLs from tracing, logs and analytics.

Before production enablement, exercise both multipart alternatives in desktop/mobile mail,
verify the fragment survives without tracking redirects, reply to the same thread, confirm
authorized full-text visibility and unauthorized denial, and open an old email after payment/amendment/revocation to verify the
authoritative payment-page state. Normal tests use a local deterministic provider and real
throwaway PostgreSQL, with no live provider credentials.

## Subsequent fionas-web changes

In a separate `fionas-web` PR:

- Use financial-email preview success to establish email readiness. Keep current Quote or
  SERVED Invoice controls available while checkout is `OPEN`, `CREATING` or `PROCESSING`,
  subject to the existing permissions and review. Keep payment collection status separate.
- In the staff attempt list, compare `purpose`, `documentVersion` and `businessRevision` to
  current terms. For an `OPEN` attempt with superseded terms, offer **Release previous checkout**
  through `POST /staff/requests/{id}/payment-attempts/{attemptId}/reconcile`. Refresh detail and
  attempts after the response. A matching `OPEN` attempt can offer **Check payment status**.
- Show `EXPIRED` as confirmed release, `PROCESSING` as awaiting payment confirmation, and a
  failed reconcile request or unresolved `CREATING` as requiring another check or staff
  investigation. Route `RECONCILIATION_REQUIRED` to unapplied-payment review. Keep confirmed
  payment and financial-email delivery statuses distinct. See the
  [Stripe operations guide](stripe-payments-operations.md#transaction-and-recovery-contract).
- Use the supplied preview HTML/text and its neutral review wording. Keep using public `canPay`,
  `paymentInProgress`, `reconciliationPending` and the existing recovery flow for payment UI.

- In `apps/public/src/lib/payments/view.ts` and
  `apps/public/src/lib/components/payments/payment-overview.svelte`, replace `Quote vN`
  with `Quote` and `Invoice vN` with `Invoice`; remove the `Booked from Quote vN` caption.
- Retain ledger version, business revision, deposit revision and accepted-Quote references
  internally. Continue sending exact expected versions/business revisions for payment and
  Checkout concurrency; hiding a display label never relaxes stale-term rejection.
- Display full authorized staff timeline text. Handle only READABLE and
  PROVIDER_CONTENT_UNAVAILABLE; retire the old protected/unverified placeholders.
- Preserve the minimal public payment page. Do not add customer identity, contact details,
  addresses, private notes, full service descriptions or conversation text. Keep bearer-only
  access, no-store/no-referrer, fragment handling and observability exclusions unchanged.
