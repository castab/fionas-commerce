# Financial emails: fionas-web handoff

Staff manually preview and send the current published Quote or a balance-due Invoice in
the inquiry's existing Conversation Service thread. Publishing, revising, amending,
serving and receiving payment never send email. There is no frontend implementation here.
Generate clients from the executable `/openapi.json` contract.
Quote, revised Quote and Invoice greetings use the authoritative customer's first name,
with Unicode whitespace handling and a blank fallback of `there`. HTML escapes that name;
plain text uses the same name. Full names in customer records and event facts are unchanged.

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
Upgrade every serving instance to the secret-safe reader before asserting true; do not
overlap financial sending with old timeline readers during a rolling deployment.
Once intents exist, retain this version's timeline body-withholding policy during rollback:
older binaries do not know that provider bodies contain payment credentials. Disabling
sending alone does not make an old timeline reader safe.

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
USD, no historical allocation and permitted online deposit collection. Fiona currently
publishes only positive approved deposits; a payment-oriented zero/no-deposit Quote is
ineligible, and this feature does not introduce zero-deposit booking. The email shows the
customer-facing Quote revision, event facts, requested guests, approved service when
present, ordered exact ledger lines/tax/totals, required deposit and total less that deposit.

INVOICE requires SERVED, the current canonical Invoice/business revision, USD and a strictly
positive authoritative reconciled balance. BOOKED, CLOSED, DECLINED, fully paid, overpaid,
active Checkout and outstanding reconciliation-required money refuse collection email.
The email shows the current Invoice revision, itemized charges, total, net applied payments
and remaining balance. No due date, overdue status, penalties, refund/expiry promises or
event-day balance deadline is introduced. Reconciliation errors fail closed.

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
service, payment, customer, access generation, template or provider-configuration changes
can make reconstruction impossible. Recovery then fails closed; it never renders a new
replacement under an uncertain key. An unresolved intent reserves further financial sends
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
| `READABLE` | Ordinary provider text, unchanged. Applies with no financial commands or only definitive first-dispatch rejections. |
| `FINANCIAL_EMAIL_PROTECTED` | ID matches a financial command, including UNKNOWN. Original body is withheld; safe `financialEmail` metadata remains. |
| `PAYMENT_CONTENT_UNVERIFIED` | Other raw correspondence when any financial command may have sent payment content. Quoted/pasted credentials cannot be excluded. |
| `PROVIDER_CONTENT_UNAVAILABLE` | Provider supplied no text. This is distinct from a withheld body or an empty message. |

The two protected states have `text:null` (omitted under CommerceJson) and
`textOmittedForPaymentSecurity:true`. Provider HTML is always excluded. Only a command in
REJECTED with exactly one dispatch proves no send; an UNKNOWN intent without message identity
also enforces protection. Unavailable text has the omission flag false unless its ID is a
known financial send. Classification does not depend on direction, timestamps or acceptance
alone. Message IDs, chronological ordering, send/delivery state, safe purpose metadata,
conversation availability and financial milestones remain unchanged.

**Readability remains incomplete with the current provider contract.** Its
[Message schema at the verified revision](https://github.com/castab/resend-conversation-service/blob/a5a77f378d34178efb8534eaf40009f4c3c9ade3/public/openapi.json)
exposes only raw nullable `text` and `html`; there is no trusted credential-free reply field.
Even a simple customer question after a Quote or Invoice and an ordinary outbound staff
reply therefore remain PAYMENT_CONTENT_UNVERIFIED. A body cannot be declared safe merely
because it lacks `/pay/` or resembles ordinary prose. Credentials may be pasted, forwarded,
encoded as entities/percent escapes, or fragmented by wrapping. A Gmail/Outlook quote splitter
cannot establish confidentiality, so no best-effort extraction is presented as secure.

The security boundary is denial of unverified raw content at the server read boundary,
using durable financial command provenance read **after provider I/O**. This preserves the
snapshot-before-send race protection, including newly committed UNKNOWN intents. A stronger
upstream content/provenance contract establishing credential exclusion is required to restore
those bodies. No new raw-message endpoint, permission bypass or secret/body persistence is
introduced. Existing payment-access authority does not implicitly grant original-message access.

Frontend changes are needed to label the new reasons: render readable text normally, a
financial summary for FINANCIAL_EMAIL_PROTECTED, an explicit security placeholder for
PAYMENT_CONTENT_UNVERIFIED, and an unavailable placeholder for PROVIDER_CONTENT_UNAVAILABLE.
Older clients may continue using the omission flag, but must not treat withheld content as
an empty customer message. Keep the normal reply operation and acknowledgement watermarks;
do not offer “View exact original.” No frontend implementation is included here.

Quote/Invoice publication/amendment history remains independent. Accepted sends append one
STAFF_EMAIL_SENT with authenticated USER and a zero clearing boundary, so no customer mail
is acknowledged on their behalf. V12 command changes and the existing communication/provider
triggers invalidate SSE; refresh both detail and timeline. Preserve the existing
acknowledgement watermark semantics. Apply no-store and exclude response bodies, tokens,
rendered emails and payment URLs from tracing, logs and analytics.

Before production enablement, exercise both multipart alternatives in desktop/mobile mail,
verify the fragment survives without tracking redirects, reply to the same thread, confirm
body withholding, and open an old email after payment/amendment/revocation to verify the
authoritative payment-page state. Normal tests use a local deterministic provider and real
throwaway PostgreSQL, with no live provider credentials.
