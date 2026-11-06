package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.ConversationExpectation
import io.github.castab.fionas.commerce.conversation.StaffReplyKey
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financialemail.FinancialEmailIntent
import io.github.castab.fionas.commerce.financialemail.FinancialEmailKind
import io.github.castab.fionas.commerce.financialemail.FinancialEmailMessage
import io.github.castab.fionas.commerce.financialemail.FinancialEmailOutcome
import io.github.castab.fionas.commerce.financialemail.FinancialEmailPreview
import io.github.castab.fionas.commerce.financialemail.FinancialEmailRefused
import io.github.castab.fionas.commerce.financialemail.FinancialEmailResult
import io.github.castab.fionas.commerce.financialemail.SendFinancialEmail
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import java.util.UUID

private const val FINANCIAL_EMAIL_PERMISSIONS =
    "Requires ALL of `fionas.inquiries.read`, `commerce.financial-document.read`, " +
        "`fionas.communications.read`, `fionas.communications.send` and `fionas.payment-access.manage`. "

@Serializable
enum class FinancialEmailKindResponse { QUOTE, INVOICE }

@Serializable
data class PreviewFinancialEmailRequest(
    val kind: FinancialEmailKindResponse,
    @ApiProperty(format = "uuid", description = "Canonical lineage from staff detail; RELATED documents cannot be emailed.") val documentId:
        String,
    @ApiProperty(
        description = "Current ledger snapshot version, separate from the customer-facing business revision.",
    ) val expectedDocumentVersion: Int,
    @ApiProperty(
        description = "Current Quote or Invoice business revision; service-only changes also invalidate review.",
    ) val expectedBusinessRevision: Int,
    @ApiProperty(
        format = "uuid",
        description = "Required for QUOTE, forbidden for INVOICE. Current published proposal UUID.",
    ) val proposalId: String? = null,
    @ApiProperty(
        description = "Required for QUOTE, forbidden for INVOICE. Exact current approved deposit revision.",
    ) val expectedDepositRequirementRevision: Int? = null,
    @ApiProperty(
        maxLength = FinancialEmailMessage.MAX_LENGTH,
        description = "Optional plain staff message, trimmed; blank uses the default. Blank-line paragraphs are preserved.",
    ) val message: String? = null,
)

@Serializable
data class SendFinancialEmailRequest(
    val email: PreviewFinancialEmailRequest,
    @ApiProperty(
        description =
            "Exact preview token; binds content, recipient, ledger payment and payment-access state, and " +
                "template. Changed message requires a new preview.",
        pattern = "^[a-f0-9]{64}$",
    ) val reviewToken: String,
    @ApiProperty(
        format = "uuid",
        description = "Original newest provider message reviewed. Exactly one of this or expectEmptyConversation:true.",
    ) val expectedLatestMessageId: String? = null,
    val expectEmptyConversation: Boolean = false,
)

@Serializable
data class FinancialEmailPreviewResponse(
    val kind: FinancialEmailKindResponse,
    val recipientName: String,
    val recipientAddress: String,
    val subject: String,
    @ApiProperty(description = "Escaped email HTML with an inert example.invalid payment button. Never a live payment bearer.") val html:
        String,
    @ApiProperty(description = "Equivalent plain text, also containing only an inert payment destination.") val text: String,
    val financial: FinancialDocumentResponse,
    val businessRevision: Int,
    @ApiProperty(pattern = SIGNED_DECIMAL) val requiredPayment: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val paidAmount: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val balance: String,
    @ApiProperty(
        description = "QUOTE only: total less the approved deposit, without repricing or rounding.",
        pattern = SIGNED_DECIMAL,
    ) val balanceAfterDeposit: String? = null,
    val reviewToken: String,
)

@Serializable
data class FinancialEmailSendResponse(
    @ApiProperty(format = "uuid") val sendId: String,
    val outcome: StaffReplyOutcomeResponse,
    val replayed: Boolean,
    @ApiProperty(format = "uuid") val conversationId: String? = null,
    @ApiProperty(format = "uuid") val messageId: String? = null,
    @ApiProperty(
        description = "Provider state pending/accepted, or indeterminate with UNKNOWN. Never confirmation of customer delivery.",
    ) val sendState: String? = null,
)

private val emailPreviewBody = jsonBody(PreviewFinancialEmailRequest.serializer())
private val emailSendBody = jsonBody(SendFinancialEmailRequest.serializer())
private val emailPreviewResponse = jsonBody(FinancialEmailPreviewResponse.serializer())
private val emailSendResponse = jsonBody(FinancialEmailSendResponse.serializer())
private val emailError = jsonBody(ErrorResponse.serializer())
private val emailKey =
    Header.required(
        "Idempotency-Key",
        "Immutable identity of one financial email, scoped to this inquiry. Recovery repeats " +
            "the exact key, email, token and original expectation.",
        mapOf("schema" to mapOf("pattern" to StaffReplyKey.PATTERN, "maxLength" to StaffReplyKey.MAX_LENGTH, "minLength" to 1)),
    )

private val financialEmailErrors =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: FinancialEmailRefused) {
                val status =
                    when (failure.code) {
                        "not_found" -> Status.NOT_FOUND
                        "email_recipient_not_allowed" -> Status.UNPROCESSABLE_ENTITY
                        "email_send_rejected" -> Status.BAD_GATEWAY
                        "email_sending_disabled", "email_provider_unavailable", "stripe_disabled",
                        "financial_email_tracking_not_verified",
                        -> Status.SERVICE_UNAVAILABLE
                        else -> Status.CONFLICT
                    }
                Response(status)
                    .header("Cache-Control", "no-store")
                    .with(emailError of ErrorResponse(failure.code, checkNotNull(failure.message)))
            }
        }
    }

private fun AccessControl.financialEmailAccess(): Filter =
    requirePermission(FionaPermissions.InquiriesRead)
        .then(requirePermission(CommercePermissions.FinancialDocumentRead))
        .then(requirePermission(FionaPermissions.CommunicationsRead))
        .then(requirePermission(FionaPermissions.CommunicationsSend))
        .then(requirePermission(FionaPermissions.PaymentAccessManage))
        .then(requireStaffSender)
        .then(financialEmailErrors)

internal fun previewFinancialEmailRoute(
    preview: (FinancialEmailIntent, UserId) -> FinancialEmailPreview,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "financial-emails" / "preview" meta {
        operationId = "previewInquiryFinancialEmail"
        summary = "Review a current Quote or balance-due Invoice email"
        description =
            "Write-free authoritative review. QUOTE requires QUOTED and the current published " +
            "proposal/deposit; INVOICE requires SERVED, a current Invoice and a positive collectible ledger balance. " +
            "Active or unresolved Checkout does not block email review. The email links to current terms and payment status. " +
            "USD only. Revoked access, missing established " +
            "conversation and disabled/unavailable provider refuse review. " +
            "Returns existing conversation subject or a defined opening subject. All payment " +
            "links are inert. Requires staff USER and trusted cookie Origin. " +
            FINANCIAL_EMAIL_PERMISSIONS + "No role grants are changed. No-store."
        preFlightExtraction = PreFlightExtraction.None
        principalAuthentication()
        security = staffSessionSecurity
        tags += inquiries
        receiving(emailPreviewBody to exampleFinancialEmail)
        returning(
            Status.OK,
            emailPreviewResponse to
                FinancialEmailPreviewResponse(
                    FinancialEmailKindResponse.QUOTE,
                    "Maria",
                    "maria@example.com",
                    "Your quote from Fiona's Ice Cream",
                    "<p>Preview</p>",
                    "Preview",
                    exampleQuote,
                    2,
                    "150.00",
                    "0.00",
                    "750.00",
                    "600.00",
                    "a".repeat(64),
                ),
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "Unreadable body, identifiers or kind.", "Malformed request")
        returningError(ErrorCategory.FORBIDDEN, "A required permission, staff USER or trusted Origin is missing.", STAFF_SENDER_REQUIRED)
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returning(
            Status.CONFLICT,
            emailError to ErrorResponse("financial_email_review_stale", "Preview the current document"),
            EMAIL_CONFLICTS,
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "Invalid versions, message or kind-specific identities; recipient not allow-listed (email_recipient_not_allowed).",
            "Invalid financial email",
        )
        returning(
            Status.SERVICE_UNAVAILABLE,
            emailError to ErrorResponse("email_provider_unavailable", "Provider unavailable"),
            EMAIL_UNAVAILABLE,
        )
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "Unexpected persistence/reconciliation failure; no content or provider detail is disclosed.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.financialEmailAccess().then { request: Request ->
            val intent = emailPreviewBody(request).intent(inquiryId(id))
            val result = preview(intent, staffUser(request))
            Response(Status.OK).header("Cache-Control", "no-store").with(emailPreviewResponse of result.response())
        }
    }

internal fun sendFinancialEmailRoute(
    send: (SendFinancialEmail) -> FinancialEmailResult,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "financial-emails" meta {
        operationId = "sendInquiryFinancialEmail"
        summary = "Send the reviewed financial email in the inquiry conversation"
        description =
            "Manual staff send; never triggered by publication/amendment. Requires USER session and trusted Origin. " +
            FINANCIAL_EMAIL_PERMISSIONS +
            "Idempotency-Key is inquiry-scoped. Recipients, prices, HTML and bearer URLs are resolved only by the backend. " +
            "Reuses active payment access; initial issuance and durable send intent commit " +
            "together. Revoked access needs deliberate separate reactivation and a new preview. " +
            "New sends recheck review and conversation; recovery uses the identical command and " +
            "original expectation. An unresolved send reserves the inquiry. " +
            "Recovery requires an exact match for reviewed terms, ledger payments, content, customer and access generation. " +
            "201 ACCEPTED, 200 accepted replay, 202 UNKNOWN. Acceptance never means delivery. No " +
            "body, HTML, amounts or bearer URL is stored in communication tables or returned. " +
            "Financial sends do not acknowledge inbound mail. No-store."
        headers += emailKey
        preFlightExtraction = PreFlightExtraction.None
        principalAuthentication()
        security = staffSessionSecurity
        tags += inquiries
        receiving(emailSendBody to SendFinancialEmailRequest(exampleFinancialEmail, "a".repeat(64), expectEmptyConversation = true))
        val accepted = FinancialEmailSendResponse("019808b9-37c4-7ed7-93c5-8960f0b690aa", StaffReplyOutcomeResponse.ACCEPTED, false)
        returning(Status.CREATED, emailSendResponse to accepted, "New accepted provider intent; never delivery.")
        returning(Status.OK, emailSendResponse to accepted.copy(replayed = true), "Confirmed replay, no new email.")
        returning(
            Status.ACCEPTED,
            emailSendResponse to accepted.copy(outcome = StaffReplyOutcomeResponse.UNKNOWN),
            "Ambiguous. Retain the exact original command and key; never use a new key for recovery.",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "Missing/repeated/invalid key, malformed body or identifiers.", "Malformed request")
        returningError(ErrorCategory.FORBIDDEN, "A required permission, staff USER or trusted Origin is missing.", STAFF_SENDER_REQUIRED)
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returning(
            Status.CONFLICT,
            emailError to ErrorResponse("financial_email_review_stale", "Preview the current document"),
            EMAIL_CONFLICTS +
                " IDEMPOTENCY_KEY_REUSED: changed command. financial_email_in_progress: reconcile " +
                "earlier command. financial_email_reconciliation_required: exact reconstruction is " +
                "impossible; no dispatch. Never change keys to bypass an unknown send.",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "Invalid versions, message, token, expectation or identities; email_recipient_not_allowed.",
            "Invalid financial email",
        )
        returning(
            Status.BAD_GATEWAY,
            emailError to ErrorResponse("email_send_rejected", "The provider definitively refused this email"),
            "`email_send_rejected`: recorded definitive rejection; the same key repeats it.",
        )
        returning(
            Status.SERVICE_UNAVAILABLE,
            emailError to ErrorResponse("email_provider_unavailable", "Provider unavailable"),
            EMAIL_UNAVAILABLE,
        )
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "Unexpected failure. Retain the command: durable intent may exist and recovery must use the same key.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.POST to { id: String, _: String ->
        access.financialEmailAccess().then { request: Request ->
            val key =
                try {
                    require(request.headerValues("Idempotency-Key").size == 1)
                    StaffReplyKey(emailKey(request))
                } catch (failure: IllegalArgumentException) {
                    throw LensFailure(Invalid(emailKey.meta), cause = failure)
                }
            val input = emailSendBody(request)
            val intent = input.email.intent(inquiryId(id))
            val latest = input.expectedLatestMessageId?.let(::emailUuid)
            val command =
                validating {
                    require((latest != null) != input.expectEmptyConversation) { "Exactly one conversation expectation is required" }
                    SendFinancialEmail(
                        intent,
                        staffUser(request),
                        key,
                        input.reviewToken,
                        latest?.let(ConversationExpectation::Latest) ?: ConversationExpectation.Empty,
                    )
                }
            val result = send(command)
            val status =
                if (result.outcome ==
                    FinancialEmailOutcome.UNKNOWN
                ) {
                    Status.ACCEPTED
                } else if (result.replayed) {
                    Status.OK
                } else {
                    Status.CREATED
                }
            Response(status).header("Cache-Control", "no-store").with(
                emailSendResponse of
                    FinancialEmailSendResponse(
                        result.sendId.value.toString(),
                        StaffReplyOutcomeResponse.valueOf(result.outcome.name),
                        result.replayed,
                        result.conversationId?.toString(),
                        result.messageId?.toString(),
                        result.sendState,
                    ),
            )
        }
    }

private fun emailUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (failure: IllegalArgumentException) {
        throw LensFailure(Invalid(emailPreviewBody.metas.single().copy(name = "identifier")), cause = failure)
    }

private fun PreviewFinancialEmailRequest.intent(inquiry: InquiryId): FinancialEmailIntent {
    val document = emailUuid(documentId)
    val proposal = proposalId?.let { InquiryProposalId(emailUuid(it)) }
    return validating {
        FinancialEmailIntent(
            inquiry,
            FinancialEmailKind.valueOf(kind.name),
            document,
            Version.of(expectedDocumentVersion),
            expectedBusinessRevision,
            proposal,
            expectedDepositRequirementRevision,
            FinancialEmailMessage.ofOptional(message),
        )
    }
}

private fun FinancialEmailPreview.response() =
    FinancialEmailPreviewResponse(
        FinancialEmailKindResponse.valueOf(intent.kind.name),
        request.inquiry.customer.name.value,
        request.inquiry.customer.email.value,
        email.subject,
        email.html,
        email.text,
        request.financial.toResponse(),
        intent.expectedBusinessRevision,
        summary.required.toPlainString(),
        summary.paid.toPlainString(),
        summary.balance.toPlainString(),
        (document.total.amount - summary.required).toPlainString().takeIf { intent.kind == FinancialEmailKind.QUOTE },
        reviewToken,
    )

private const val EMAIL_CONFLICTS =
    "`financial_email_review_stale`: refresh preview after changes to reviewed facts or content. " +
        "financial_email_ineligible: wrong lifecycle or terms ineligible for online payment. " +
        "payment_access_revoked: deliberately reactivate then " +
        "preview. conversation_changed: review latest thread. conversation_missing: " +
        "established thread disappeared; never recreate. financial_email_recipient_mismatch: " +
        "reconcile provider participant."
private const val EMAIL_UNAVAILABLE =
    "`email_provider_unavailable`: lookup unavailable; email_sending_disabled, stripe_disabled or " +
        "financial_email_tracking_not_verified. No provider send is attempted."
private val exampleFinancialEmail =
    PreviewFinancialEmailRequest(
        FinancialEmailKindResponse.QUOTE,
        "019808b9-37c4-7ed7-93c5-8960f0b690ab",
        2,
        2,
        "019808b9-37c4-7ed7-93c5-8960f0b690ac",
        1,
    )
