package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.PaymentHistory
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ValidationViolationResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.AllocatedPayment
import io.github.castab.fionas.commerce.financial.CreateChangeOrder
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.FirstSnapshotStage
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financial.LineProposalViolations
import io.github.castab.fionas.commerce.financial.MAX_DOCUMENT_LINES
import io.github.castab.fionas.commerce.financial.ReconciledRefund
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.financial.RecordedPayment
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import org.http4k.lens.PathLens
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.UUID

/** The body of `POST /inquiries/{inquiryId}/estimates`: the staff-committed lines of a new Estimate lineage. */
@Serializable
data class CreateInquiryEstimateRequest(
    @ApiProperty(
        description =
            "The exact already-priced lines, in order: 1 to $MAX_DOCUMENT_LINES lines in one currency with a nonnegative " +
                "total. Fiona generates their ids and derives every total.",
    )
    val lines: List<PricedLineRequest>,
)

/** A new lineage's starting stage and its staff-committed lines. */
@Serializable
data class CreateInquiryFinancialDocumentRequest(
    @ApiProperty(description = "The first snapshot's stage: `ESTIMATE`, `QUOTE`, or `INVOICE`.")
    val stage: String,
    @ApiProperty(description = "The exact already-priced lines, in order; Fiona generates their ids and derives every total.")
    val lines: List<PricedLineRequest>,
)

/** The body of `POST /financial-documents/{documentId}/change-orders`: the complete staff-committed final lines. */
@Serializable
data class ChangeOrderRequest(
    @ApiProperty(
        description =
            "The version the staff user reviewed, which must still be the document's latest version. A document that has " +
                "moved on answers `409 conflict`; the edit is never rebased onto a version its author never saw.",
    )
    val expectedVersion: Int,
    @ApiProperty(
        description =
            "The complete final lines, in order. Each names an existing line of the reviewed version (`lineItemId`: carried " +
                "when unchanged, or a direct override under the same id) or a new line (`key`). Reviewed lines left out are " +
                "removed. Lines identical to the current ones are no financial change.",
    )
    val lines: List<ProposedLineRequest>,
)

/** The body of the quote and invoice transitions. */
@Serializable
data class StageTransitionRequest(
    @ApiProperty(
        description =
            "The version of the document the caller acted on, which must still be its latest version; otherwise the " +
                "request is `409 conflict`.",
    )
    val expectedVersion: Int,
)

/** The body of `POST /financial-documents/{documentId}/payments`. */
@Serializable
data class RecordPaymentRequest(
    @ApiProperty(
        description =
            "The exact version of the document the whole payment is applied to. It must be the document's latest " +
                "version, a quote or an invoice; otherwise the request is `409 conflict`.",
    )
    val documentVersion: Int,
    @ApiProperty(
        description =
            "The money received, a positive exact decimal string such as `300.00`, never a JSON number, with at most " +
                "the currency's minor-unit digits. The currency is the document's.",
    )
    val amount: String,
    @ApiProperty(description = "How the money was received: `CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`, or `OTHER`.")
    val method: String,
    @ApiProperty(
        description = "When the money was received, an RFC 3339 timestamp. The time the payment is recorded when absent.",
        format = "date-time",
    )
    val receivedAt: String? = null,
    @ApiProperty(
        description =
            "The payment's identity in an external system, such as a processor's transaction id. A provider and " +
                "reference already recorded for another payment is `409 conflict`.",
    )
    val externalReference: PaymentExternalReference? = null,
    @ApiProperty(
        description =
            "Required for a canonical Quote: the exact current payable proposal id. " +
                "Invoice and RELATED payments need no proposal id.",
        format = "uuid",
    )
    val expectedProposalId: String? = null,
)

/** Records money received without assigning it to a document yet. */
@Serializable
data class RecordStandalonePaymentRequest(
    @ApiProperty(description = "The money received, a positive exact decimal string, never a JSON number.")
    val amount: String,
    @ApiProperty(description = "An ISO 4217 currency code; the amount may use only its minor-unit digits.")
    val currency: String,
    @ApiProperty(description = "How the money was received: `CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`, or `OTHER`.")
    val method: String,
    @ApiProperty(description = "When the money was received, an RFC 3339 timestamp; recording time when absent.", format = "date-time")
    val receivedAt: String? = null,
    @ApiProperty(description = "The payment's unique provider and reference, when it has one.")
    val externalReference: PaymentExternalReference? = null,
)

/** Applies some of an existing payment to the document's exact latest Quote or Invoice snapshot. */
@Serializable
data class AllocatePaymentRequest(
    @ApiProperty(description = "The Fiona-owned financial-document lineage's id.", format = "uuid")
    val documentId: String,
    @ApiProperty(description = "The exact snapshot to allocate to; it must still be the latest version.")
    val documentVersion: Int,
    @ApiProperty(description = "The amount to apply, as a positive exact decimal string; currency comes from the document.")
    val amount: String,
)

/** A payment's identity in an external system. */
@Serializable
data class PaymentExternalReference(
    @ApiProperty(description = "The external system's stable name. Must not be blank.")
    val provider: String,
    @ApiProperty(description = "The payment's identifier in that system. Must not be blank.")
    val reference: String,
)

/** The body of a refund of an existing payment, with explicit allocation unwinds. */
@Serializable
data class RecordRefundRequest(
    @ApiProperty(description = "The positive exact decimal amount returned, never a JSON number.")
    val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code; the ledger validates agreement.")
    val currency: String,
    @ApiProperty(description = "How money left: `CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`, or `OTHER`.")
    val method: String,
    @ApiProperty(description = "When money was returned, RFC 3339; server time when absent.", format = "date-time")
    val refundedAt: String? = null,
    @ApiProperty(description = "The refund's external provider and reference, when present.")
    val externalReference: RefundExternalReference? = null,
    @ApiProperty(description = "Explicit portions unwinding prior payment allocations; empty for unapplied value.")
    val allocations: List<RefundAllocationRequest> = emptyList(),
)

@Serializable
data class RefundExternalReference(
    @ApiProperty(description = "The external provider's nonblank name.") val provider: String,
    @ApiProperty(description = "The refund's nonblank reference at that provider.") val reference: String,
)

@Serializable
data class RefundAllocationRequest(
    @ApiProperty(description = "The payment allocation to unwind.", format = "uuid") val paymentAllocationId: String,
    @ApiProperty(description = "The positive exact decimal portion unwound.") val amount: String,
)

/**
 * One immutable financial-document snapshot, as commerce-runtime's ledger records it, with
 * who committed its lines. Settlement is derived, never stored.
 */
@Serializable
data class FinancialDocumentResponse(
    @ApiProperty(description = "The document lineage's id, shared by every version.", format = "uuid")
    val id: String,
    @ApiProperty(description = "This snapshot's version within the lineage, from 1.")
    val version: Int,
    @ApiProperty(
        description = "When PostgreSQL persisted this exact immutable version, an RFC 3339 timestamp supplied by commerce-runtime.",
        format = "date-time",
    )
    val createdAt: String,
    @ApiProperty(description = "The version this snapshot succeeded; absent for version 1.")
    val previousVersion: Int? = null,
    @ApiProperty(description = "The lifecycle stage of this snapshot: `ESTIMATE`, `QUOTE`, or `INVOICE`.")
    val stage: String,
    @ApiProperty(description = "The id of the inquiry the lineage belongs to.", format = "uuid")
    val inquiryId: String,
    @ApiProperty(
        description =
            "Who committed this snapshot's lines: the SERVICE pricing authority for an inquiry's Estimate v1, or the staff " +
                "USER for staff-authored lines; stage transitions carry it forward unchanged.",
    )
    val linesAuthoredBy: LineAuthorshipResponse? = null,
    @ApiProperty(description = "The snapshot's lines, in order. Line ids are durable ledger facts.")
    val lines: List<FinancialDocumentLine>,
    @ApiProperty(description = "The sum of the lines' subtotals, an exact decimal.")
    val subtotal: String,
    @ApiProperty(description = "The sum of the lines' tax amounts, an exact decimal.")
    val taxAmount: String,
    @ApiProperty(description = "`subtotal` plus `taxAmount`, an exact decimal.")
    val total: String,
    @ApiProperty(description = "The ISO 4217 code of every amount, for example `USD`.")
    val currency: String,
    @ApiProperty(
        description =
            "The lineage's current settlement, on the latest snapshot only; absent on historical snapshots, which " +
                "are never reconciled after later payments.",
    )
    val reconciliation: DocumentReconciliation? = null,
)

/** One line of a financial-document snapshot. */
@Serializable
data class FinancialDocumentLine(
    @ApiProperty(description = "The line's id within the document.", format = "uuid")
    val id: String,
    @ApiProperty(description = "What the line charges for, for example `Ice cream service`.")
    val description: String,
    @ApiProperty(description = "Secondary text; absent when there is none.")
    val subDescription: String? = null,
    @ApiProperty(description = "How many units `unitPrice` is charged for, an exact decimal; absent for a flat charge.")
    val quantity: String? = null,
    @ApiProperty(description = "The price of one unit, or of the flat charge, an exact decimal.")
    val unitPrice: String,
    @ApiProperty(description = "`unitPrice` × `quantity` (or `unitPrice` for a flat charge), an exact decimal.")
    val subtotal: String,
    @ApiProperty(description = "The line's final tax amount, an exact decimal.")
    val taxAmount: String,
    @ApiProperty(description = "`subtotal` plus `taxAmount`, an exact decimal.")
    val total: String,
    @ApiProperty(description = "The ISO 4217 code of the line's amounts.")
    val currency: String,
)

/**
 * The money applied to a document's whole lineage and what remains owed, derived from the
 * payment allocations of every version and the latest snapshot's total.
 */
@Serializable
data class DocumentReconciliation(
    @ApiProperty(description = "The sum of every allocation to any version of the document, an exact decimal.")
    val grossAllocated: String,
    @ApiProperty(description = "The money currently applied to the document, an exact decimal.")
    val netApplied: String,
    @ApiProperty(description = "The latest total minus `netApplied`, an exact decimal; negative when more is applied than owed.")
    val balance: String,
    @ApiProperty(description = "The ISO 4217 code of every amount.")
    val currency: String,
)

/** Every immutable version of one lineage, oldest first. */
@Serializable
data class FinancialDocumentHistoryResponse(
    @ApiProperty(description = "The document lineage's id.", format = "uuid")
    val id: String,
    @ApiProperty(description = "The id of the inquiry the lineage belongs to.", format = "uuid")
    val inquiryId: String,
    @ApiProperty(description = "Every self-contained snapshot, oldest first, with its line authorship.")
    val versions: List<FinancialDocumentResponse>,
)

/** The financial-document lineages of one inquiry. */
@Serializable
data class InquiryFinancialDocumentsResponse(
    @ApiProperty(description = "The inquiry's id.", format = "uuid")
    val inquiryId: String,
    @ApiProperty(description = "Each lineage at its latest snapshot, with its current settlement, oldest lineage first.")
    val documents: List<FinancialDocumentResponse>,
)

/** A recorded payment and where it was applied. */
@Serializable
data class RecordedPaymentResponse(
    @ApiProperty(description = "The payment's id.", format = "uuid")
    val paymentId: String,
    @ApiProperty(description = "The id of the allocation that applies the whole payment to the document.", format = "uuid")
    val allocationId: String,
    @ApiProperty(description = "The document lineage the payment was applied to.", format = "uuid")
    val documentId: String,
    @ApiProperty(description = "The exact version the payment was applied to; it stays attached to it as the document advances.")
    val documentVersion: Int,
    @ApiProperty(description = "How the money was received.")
    val method: String,
    @ApiProperty(description = "The money received, an exact decimal.")
    val amount: String,
    @ApiProperty(description = "The ISO 4217 code of the amount: the document's currency.")
    val currency: String,
    @ApiProperty(description = "When the money was received, an RFC 3339 timestamp in UTC.", format = "date-time")
    val receivedAt: String,
    @ApiProperty(description = "The payment's identity in an external system; absent when it has none.")
    val externalReference: PaymentExternalReference? = null,
    @ApiProperty(description = "When the payment was applied, an RFC 3339 timestamp in UTC.", format = "date-time")
    val allocatedAt: String,
    @ApiProperty(description = "The document lineage's settlement after this payment.")
    val reconciliation: DocumentReconciliation,
)

/** An immutable receipt fact, which may have no allocation yet. */
@Serializable
data class PaymentRecordResponse(
    @ApiProperty(description = "The payment's id.", format = "uuid")
    val paymentId: String,
    @ApiProperty(description = "How the money was received.")
    val method: String,
    @ApiProperty(description = "The received amount, an exact decimal string.")
    val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.")
    val currency: String,
    @ApiProperty(description = "When the money was received, an RFC 3339 timestamp in UTC.", format = "date-time")
    val receivedAt: String,
    @ApiProperty(description = "The payment's identity in an external system, when provided.")
    val externalReference: PaymentExternalReference? = null,
)

/** An immutable allocation and the destination lineage's resulting derived settlement. */
@Serializable
data class PaymentAllocationResponse(
    @ApiProperty(description = "The allocation's id.", format = "uuid")
    val allocationId: String,
    @ApiProperty(description = "The received payment's id.", format = "uuid")
    val paymentId: String,
    @ApiProperty(description = "The financial-document lineage's id.", format = "uuid")
    val documentId: String,
    @ApiProperty(description = "The exact document version this allocation stays attached to.")
    val documentVersion: Int,
    @ApiProperty(description = "The amount applied, an exact decimal string.")
    val amount: String,
    @ApiProperty(description = "The document and payment currency's ISO 4217 code.")
    val currency: String,
    @ApiProperty(description = "When the allocation was recorded, an RFC 3339 timestamp in UTC.", format = "date-time")
    val allocatedAt: String,
    @ApiProperty(description = "The document lineage's settlement after this allocation.")
    val reconciliation: DocumentReconciliation,
)

@Serializable
data class RefundAllocationResponse(
    @ApiProperty(description = "The generated refund allocation id.", format = "uuid") val refundAllocationId: String,
    @ApiProperty(description = "The payment allocation unwound.", format = "uuid") val paymentAllocationId: String,
    @ApiProperty(description = "The exact amount unwound.") val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.") val currency: String,
    @ApiProperty(description = "When the unwind was recorded, RFC 3339 UTC.", format = "date-time") val allocatedAt: String,
)

@Serializable
data class PaymentReconciliationResponse(
    @ApiProperty(description = "The payment's original exact amount.") val paymentAmount: String,
    @ApiProperty(description = "All returned value.") val totalRefunded: String,
    @ApiProperty(description = "Original amount less refunds.") val netReceived: String,
    @ApiProperty(description = "All original allocations.") val grossAllocated: String,
    @ApiProperty(description = "Allocation reversals; currently zero because runtime does not persist them.") val allocationReversals:
        String,
    @ApiProperty(description = "Applied value unwound by refunds.") val refundAllocations: String,
    @ApiProperty(description = "Currently applied value.") val netAllocated: String,
    @ApiProperty(description = "Kept money not applied to documents.") val unallocated: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.") val currency: String,
)

@Serializable
data class RecordedRefundResponse(
    @ApiProperty(description = "The generated refund id.", format = "uuid") val refundId: String,
    @ApiProperty(description = "The payment being refunded.", format = "uuid") val paymentId: String,
    @ApiProperty(description = "The exact amount returned.") val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.") val currency: String,
    @ApiProperty(description = "How money left the business.") val method: String,
    @ApiProperty(description = "When money was returned, RFC 3339 UTC.", format = "date-time") val refundedAt: String,
    @ApiProperty(description = "The refund's external identity, when present.") val externalReference: RefundExternalReference? = null,
    @ApiProperty(description = "Explicit portions of prior allocations unwound by the refund.") val allocations:
        List<RefundAllocationResponse>,
    @ApiProperty(description = "The payment's derived reconciliation after recording the refund.")
    val reconciliation: PaymentReconciliationResponse,
)

/** The payments that have touched one Fiona financial-document lineage, each with its complete history. */
@Serializable
data class FinancialDocumentPaymentsResponse(
    @ApiProperty(description = "The document lineage's id, as requested.", format = "uuid")
    val documentId: String,
    @ApiProperty(
        description =
            "Every payment ever allocated to any version of the lineage, by `receivedAt`, then `paymentId`. Empty " +
                "when the document has never received a payment.",
    )
    val payments: List<PaymentHistoryResponse>,
)

/** Complete runtime histories with positive derived unapplied value. */
@Serializable
data class UnappliedPaymentsResponse(
    @ApiProperty(
        description =
            "Payments with positive reconciliation.unallocated, ordered by receivedAt then paymentId; " +
                "no inquiry association is required.",
    )
    val payments: List<PaymentHistoryResponse>,
)

/**
 * The complete, immutable history of one payment and the reconciliation derived from exactly
 * those facts. It is the whole payment, never only its part in the document it was found through.
 */
@Serializable
data class PaymentHistoryResponse(
    @ApiProperty(description = "The payment fact.")
    val payment: PaymentRecordResponse,
    @ApiProperty(
        description =
            "Every allocation of the payment, to this or any other lineage, by `allocatedAt`, then `allocationId`, " +
                "including allocations later unwound by refunds. Select `documentId` to show one document's.",
    )
    val allocations: List<PaymentAllocationRecordResponse>,
    @ApiProperty(description = "Every refund of the payment, by `refundedAt`, then `refundId`.")
    val refunds: List<RefundRecordResponse>,
    @ApiProperty(
        description =
            "Which allocation each refund unwound, by `allocatedAt`, then `refundAllocationId`. Empty when every " +
                "refund came from unapplied value.",
    )
    val refundAllocations: List<RefundAllocationRecordResponse>,
    @ApiProperty(description = "The payment's settlement, derived from exactly these facts; never stored.")
    val reconciliation: PaymentReconciliationResponse,
)

/** An immutable allocation fact: some of a payment applied to an exact document snapshot. */
@Serializable
data class PaymentAllocationRecordResponse(
    @ApiProperty(description = "The allocation's id; a refund names it as `paymentAllocationId` to unwind it.", format = "uuid")
    val allocationId: String,
    @ApiProperty(description = "The payment's id.", format = "uuid")
    val paymentId: String,
    @ApiProperty(
        description = "The financial-document lineage the money was applied to, which need not be the one requested.",
        format = "uuid",
    )
    val documentId: String,
    @ApiProperty(description = "The exact document version the allocation stays attached to.")
    val documentVersion: Int,
    @ApiProperty(description = "The amount applied, an exact decimal string.")
    val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.")
    val currency: String,
    @ApiProperty(description = "When the allocation was recorded, an RFC 3339 timestamp in UTC.", format = "date-time")
    val allocatedAt: String,
)

/** An immutable refund fact: money of a payment returned to the payer. */
@Serializable
data class RefundRecordResponse(
    @ApiProperty(description = "The refund's id.", format = "uuid")
    val refundId: String,
    @ApiProperty(description = "The refunded payment's id.", format = "uuid")
    val paymentId: String,
    @ApiProperty(description = "The amount returned, an exact decimal string.")
    val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.")
    val currency: String,
    @ApiProperty(description = "How the money left the business, which may differ from how it arrived.")
    val method: String,
    @ApiProperty(description = "When the money was returned, an RFC 3339 timestamp in UTC.", format = "date-time")
    val refundedAt: String,
    @ApiProperty(description = "The refund's identity in an external system; absent when it has none.")
    val externalReference: RefundExternalReference? = null,
)

/** An immutable refund-allocation fact: part of a refund unwinding value an allocation applied. */
@Serializable
data class RefundAllocationRecordResponse(
    @ApiProperty(description = "The refund allocation's id.", format = "uuid")
    val refundAllocationId: String,
    @ApiProperty(description = "The refund, in `refunds`, whose money this portion is.", format = "uuid")
    val refundId: String,
    @ApiProperty(description = "The allocation, in `allocations`, whose applied value is unwound.", format = "uuid")
    val paymentAllocationId: String,
    @ApiProperty(description = "The amount unwound, an exact decimal string.")
    val amount: String,
    @ApiProperty(description = "The payment's ISO 4217 currency code.")
    val currency: String,
    @ApiProperty(description = "When the unwind was recorded, an RFC 3339 timestamp in UTC.", format = "date-time")
    val allocatedAt: String,
)

private val createEstimateRequest = jsonBody(CreateInquiryEstimateRequest.serializer())
private val createFinancialDocumentRequest = jsonBody(CreateInquiryFinancialDocumentRequest.serializer())
private val changeOrderRequest = jsonBody(ChangeOrderRequest.serializer())
private val transitionRequest = jsonBody(StageTransitionRequest.serializer())
private val recordPaymentRequest = jsonBody(RecordPaymentRequest.serializer())
private val recordStandalonePaymentRequest = jsonBody(RecordStandalonePaymentRequest.serializer())
private val allocatePaymentRequest = jsonBody(AllocatePaymentRequest.serializer())
private val recordRefundRequest = jsonBody(RecordRefundRequest.serializer())
private val documentResponse = jsonBody(FinancialDocumentResponse.serializer())
private val historyResponse = jsonBody(FinancialDocumentHistoryResponse.serializer())
private val inquiryDocumentsResponse = jsonBody(InquiryFinancialDocumentsResponse.serializer())
private val recordedPaymentResponse = jsonBody(RecordedPaymentResponse.serializer())
private val paymentRecordResponse = jsonBody(PaymentRecordResponse.serializer())
private val paymentAllocationResponse = jsonBody(PaymentAllocationResponse.serializer())
private val recordedRefundResponse = jsonBody(RecordedRefundResponse.serializer())
private val documentPaymentsResponse = jsonBody(FinancialDocumentPaymentsResponse.serializer())
private val paymentAllocationIdBodyMeta = recordRefundRequest.metas.single().copy(name = "paymentAllocationId")
private val allocationDocumentIdBodyMeta = allocatePaymentRequest.metas.single().copy(name = "documentId")
private val unappliedPaymentsResponse = jsonBody(UnappliedPaymentsResponse.serializer())

// Plain strings for the contract: a contract treats a path value its lens rejects as an
// unmatched route (404), while an id that is not a UUID is a malformed request (400).
private val documentIdPath =
    Path.of("documentId", "The financial-document lineage's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val inquiryIdPath =
    Path.of("inquiryId", "The inquiry's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val paymentIdPath =
    Path.of("paymentId", "The received payment's id.", mapOf("schema" to mapOf("format" to "uuid")))

private val financialDocuments =
    Tag(
        "Financial documents",
        "An inquiry's persisted estimates, quotes, and invoices: immutable commerce-runtime snapshots whose self-contained " +
            "lines an authorized actor committed; Fiona derives totals and never reprices.",
    )

private val payments = Tag("Payments", "Money received, allocations to exact financial-document snapshots, and refunds.")

private const val EXAMPLE_DOCUMENT = "5f0c6a7e-8c1d-4f63-9b2a-0d8e7f6a5b4c"
private const val EXAMPLE_INQUIRY = "c755f7cd-1e28-4c75-a85f-d066ede7387d"
private const val NOT_FOUND_DOCUMENT = "Financial document $EXAMPLE_DOCUMENT was not found"
private const val STALE_DOCUMENT = "Financial document $EXAMPLE_DOCUMENT is at v4, not the expected v3; reload it and retry"

private val exampleLines =
    listOf(
        PricedLineRequest("Churro catering service", "Prepared on site", "1", "450.00", "0.00", "USD"),
        PricedLineRequest("Courtesy discount", null, null, "-50.00", "0.00", "USD"),
    )

private val exampleCreateEstimate = CreateInquiryEstimateRequest(exampleLines)

private val exampleCreateFinancialDocument = CreateInquiryFinancialDocumentRequest("QUOTE", exampleLines)

private val exampleChangeOrder =
    ChangeOrderRequest(
        expectedVersion = 1,
        lines =
            listOf(
                ProposedLineRequest("0b0c8e6e-9f4a-4c1e-8e59-2f1f3a4b5c61", null, "Base service", "2 hours", null, "250.00", "0.00", "USD"),
                ProposedLineRequest(null, "travel", "Travel surcharge", "Outside the local area", null, "75.00", "0.00", "USD"),
            ),
    )

private fun exampleDocument(
    version: Int,
    stage: String,
    guests: Int,
    reconciliation: DocumentReconciliation?,
): FinancialDocumentResponse {
    fun line(
        id: String,
        description: String,
        subDescription: String?,
        quantity: String?,
        unitPrice: String,
        subtotal: String,
    ) = FinancialDocumentLine(id, description, subDescription, quantity, unitPrice, subtotal, "0.00", subtotal, "USD")
    val perGuest = { rate: String -> (BigDecimal(rate) * guests.toBigDecimal()).setScale(2).toPlainString() }
    val lines =
        listOf(
            line(
                "0b0c8e6e-9f4a-4c1e-8e59-2f1f3a4b5c61",
                "Base service",
                "2 hours · setup, staff & local travel",
                null,
                "250.00",
                "250.00",
            ),
            line("1c1d9f7f-a05b-4d2f-9f6a-3a2b4c5d6e72", "Ice cream service", "$guests guests", "$guests", "4.00", perGuest("4.00")),
            line("2d2eaf80-b16c-4e30-a07b-4b3c5d6e7f83", "Horchata", "Premium soft serve", "$guests", "0.50", perGuest("0.50")),
            line("3e3fb091-c27d-4f41-b18c-5c4d6e7f8094", "Waffle cones", null, "$guests", "0.75", perGuest("0.75")),
            line(
                "4f40c1a2-d38e-4052-829d-6d5e7f8091a5",
                "Extra toppings (2)",
                "4 toppings included; each extra is charged per guest",
                "${2 * guests}",
                "0.25",
                perGuest("0.50"),
            ),
        )
    val total = lines.map { BigDecimal(it.subtotal) }.reduce(BigDecimal::add).toPlainString()
    return FinancialDocumentResponse(
        createdAt = "2026-09-28T17:05:00Z",
        id = EXAMPLE_DOCUMENT,
        version = version,
        previousVersion = (version - 1).takeIf { it >= 1 },
        stage = stage,
        inquiryId = EXAMPLE_INQUIRY,
        linesAuthoredBy = LineAuthorshipResponse("USER", "580a28a1-7417-480a-9089-8f5f3c25c1cd", "2026-09-28T17:05:00Z"),
        lines = lines,
        subtotal = total,
        taxAmount = "0.00",
        total = total,
        currency = "USD",
        reconciliation = reconciliation,
    )
}

private fun unpaid(total: String) = DocumentReconciliation("0.00", "0.00", total, "USD")

internal val exampleEstimate = exampleDocument(1, "ESTIMATE", 75, unpaid("681.25"))
private val exampleChangedEstimate = exampleDocument(2, "ESTIMATE", 100, unpaid("825.00"))
internal val exampleQuote = exampleDocument(3, "QUOTE", 100, unpaid("825.00"))
private val exampleInvoice = exampleDocument(4, "INVOICE", 100, DocumentReconciliation("300.00", "300.00", "525.00", "USD"))

private val examplePayment =
    RecordPaymentRequest(
        documentVersion = 3,
        amount = "300.00",
        method = "CARD",
        receivedAt = "2026-09-27T17:05:00Z",
        externalReference = PaymentExternalReference("square", "pay_7Q2Rk9"),
        expectedProposalId = "34b41196-38b8-4e27-a48d-e2aaf896f570",
    )

private val exampleRecordedPayment =
    RecordedPaymentResponse(
        paymentId = "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
        allocationId = "7b8c9d0e-1f2a-4b3c-9d4e-5f6a7b8c9d0e",
        documentId = EXAMPLE_DOCUMENT,
        documentVersion = 3,
        method = "CARD",
        amount = "300.00",
        currency = "USD",
        receivedAt = "2026-09-27T17:05:00Z",
        externalReference = PaymentExternalReference("square", "pay_7Q2Rk9"),
        allocatedAt = "2026-09-27T17:06:12.123456Z",
        reconciliation = DocumentReconciliation("300.00", "300.00", "525.00", "USD"),
    )

private val exampleStandalonePayment =
    RecordStandalonePaymentRequest(
        amount = "300.00",
        currency = "USD",
        method = "CARD",
        receivedAt = "2026-09-28T20:00:00Z",
        externalReference = PaymentExternalReference("stripe", "pi_example"),
    )

private val examplePaymentRecord =
    PaymentRecordResponse(
        paymentId = "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
        method = "CARD",
        amount = "300.00",
        currency = "USD",
        receivedAt = "2026-09-28T20:00:00Z",
        externalReference = PaymentExternalReference("stripe", "pi_example"),
    )

private val exampleAllocatePayment = AllocatePaymentRequest(EXAMPLE_DOCUMENT, 3, "150.00")
private val examplePaymentAllocation =
    PaymentAllocationResponse(
        allocationId = "7b8c9d0e-1f2a-4b3c-9d4e-5f6a7b8c9d0e",
        paymentId = examplePaymentRecord.paymentId,
        documentId = EXAMPLE_DOCUMENT,
        documentVersion = 3,
        amount = "150.00",
        currency = "USD",
        allocatedAt = "2026-09-28T20:01:00Z",
        reconciliation = DocumentReconciliation("150.00", "150.00", "675.00", "USD"),
    )

private val exampleRefundRequest =
    RecordRefundRequest(
        amount = "50.00",
        currency = "USD",
        method = "OTHER",
        allocations = listOf(RefundAllocationRequest(examplePaymentAllocation.allocationId, "50.00")),
    )
private val exampleRefundResponse =
    RecordedRefundResponse(
        refundId = "8c9d0e1f-2a3b-4c4d-8e5f-6a7b8c9d0e1f",
        paymentId = examplePaymentRecord.paymentId,
        amount = "50.00",
        currency = "USD",
        method = "OTHER",
        refundedAt = "2026-09-28T20:02:00Z",
        allocations =
            listOf(
                RefundAllocationResponse(
                    "9d0e1f2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a",
                    examplePaymentAllocation.allocationId,
                    "50.00",
                    "USD",
                    "2026-09-28T20:02:00Z",
                ),
            ),
        reconciliation = PaymentReconciliationResponse("150.00", "50.00", "100.00", "150.00", "0.00", "50.00", "100.00", "0.00", "USD"),
    )

// A $500 payment split between the example document and another lineage, then partly refunded
// out of the example document's allocation, with $150 still unapplied.
private val exampleDocumentPayments =
    FinancialDocumentPaymentsResponse(
        documentId = EXAMPLE_DOCUMENT,
        payments =
            listOf(
                PaymentHistoryResponse(
                    payment = PaymentRecordResponse(examplePaymentRecord.paymentId, "CARD", "500.00", "USD", "2026-09-28T20:00:00Z"),
                    allocations =
                        listOf(
                            PaymentAllocationRecordResponse(
                                examplePaymentAllocation.allocationId,
                                examplePaymentRecord.paymentId,
                                EXAMPLE_DOCUMENT,
                                3,
                                "200.00",
                                "USD",
                                "2026-09-28T20:01:00Z",
                            ),
                            PaymentAllocationRecordResponse(
                                "ae1f2a3b-4c5d-4e6f-9a7b-8c9d0e1f2a3b",
                                examplePaymentRecord.paymentId,
                                "b0c1d2e3-f4a5-4b6c-8d7e-9f0a1b2c3d4e",
                                1,
                                "150.00",
                                "USD",
                                "2026-09-28T20:01:30Z",
                            ),
                        ),
                    refunds =
                        listOf(
                            RefundRecordResponse(
                                exampleRefundResponse.refundId,
                                examplePaymentRecord.paymentId,
                                "50.00",
                                "USD",
                                "OTHER",
                                "2026-09-28T20:02:00Z",
                            ),
                        ),
                    refundAllocations =
                        listOf(
                            RefundAllocationRecordResponse(
                                exampleRefundResponse.allocations.single().refundAllocationId,
                                exampleRefundResponse.refundId,
                                examplePaymentAllocation.allocationId,
                                "50.00",
                                "USD",
                                "2026-09-28T20:02:00Z",
                            ),
                        ),
                    reconciliation =
                        PaymentReconciliationResponse("500.00", "50.00", "450.00", "350.00", "0.00", "50.00", "300.00", "150.00", "USD"),
                ),
            ),
    )

/**
 * The errors every financial route answers, in addition to its own: any authenticated principal
 * holding [permission], and for an [unsafe] method a trusted Origin when it carries the staff
 * session cookie.
 */
private fun RouteMetaDsl.financialErrors(
    permission: PermissionKey,
    unsafe: Boolean,
) = financialErrors(permission, UNTRUSTED_ORIGIN.takeIf { unsafe })

private fun RouteMetaDsl.financialErrors(
    permission: PermissionKey,
    alsoForbidden: String?,
) {
    principalAccess(permission, alsoForbidden)
    returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
}

private fun RouteMetaDsl.malformed(what: String) =
    returningError(ErrorCategory.MALFORMED_REQUEST, "$what is not a UUID, or the body cannot be read.", "Malformed request: body 'body'")

private fun RouteMetaDsl.staleVersion(extra: String) =
    returningError(
        ErrorCategory.CONFLICT,
        "the version in the request is no longer the document's latest version; reload it and retry.$extra",
        STALE_DOCUMENT,
    )

private const val LINES_REJECTED =
    "a value is invalid: no lines or more than $MAX_DOCUMENT_LINES, a blank description, a malformed or overlong decimal, " +
        "more fraction digits than the currency allows, an inexact subtotal, mixed currencies, or a negative total."

/** The `403` causes of a route that commits staff-negotiated financial values, beyond missing permissions. */
private const val STAFF_TERMS_FORBIDDEN =
    "or the principal lacks `fionas.financial-terms.manage`, or is not a staff USER (a SERVICE token never commits " +
        "staff-negotiated values), $UNTRUSTED_ORIGIN"

/** Every route that commits staff-negotiated lines: the commerce permission, Fiona's terms authority, and a staff USER. */
private fun AccessControl.staffTerms(permission: PermissionKey) =
    requirePermission(permission).then(requirePermission(FionaPermissions.FinancialTermsManage)).then(requireStaffUser)

/**
 * `POST /inquiries/{inquiryId}/estimates`: persists a new RELATED Estimate lineage from the final
 * lines a verified staff user committed.
 */
fun createInquiryEstimateRoute(
    createDocument: (CreateInquiryFinancialDocument.Command) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/inquiries" / inquiryIdPath / "estimates" meta {
        operationId = "createInquiryEstimate"
        summary = "Persist a staff-authored estimate for an inquiry"
        description =
            "Records the exact already-priced lines a verified staff user committed as version 1 of a new RELATED Estimate " +
            "lineage owned by the inquiry, with the user as their author. Nothing is priced or checked against a catalog; " +
            "totals are derived from the lines. The `Location` response header holds `/financial-documents/{documentId}`. " +
            "Requires a staff USER holding `commerce.financial-document.create` and `fionas.financial-terms.manage`."
        tags += financialDocuments
        receiving(createEstimateRequest to exampleCreateEstimate)
        returning(Status.CREATED, documentResponse to exampleEstimate, "The persisted estimate. `Location` holds its path.")
        malformed("`inquiryId`")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry has this id.", "Inquiry $EXAMPLE_INQUIRY was not found")
        returningError(ErrorCategory.VALIDATION_FAILED, LINES_REJECTED, "A financial document has at least one line")
        financialErrors(CommercePermissions.FinancialDocumentCreate, STAFF_TERMS_FORBIDDEN)
        security = staffSessionSecurity
    } bindContract Method.POST to { id: String, _: String ->
        access.staffTerms(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val inquiryId = InquiryId(uuidIn(id, inquiryIdPath))
            val body = createEstimateRequest(request)
            val command =
                validating {
                    CreateInquiryFinancialDocument.Command(inquiryId, FirstSnapshotStage.ESTIMATE, body.lines.domain(), staffUser(request))
                }
            val created = createDocument(command)
            Response(Status.CREATED)
                .header("Location", "/financial-documents/${created.latest.document.id}")
                .with(documentResponse of created.toResponse())
        }
    }

/** `POST /inquiries/{inquiryId}/financial-documents`: a new RELATED lineage at a chosen first stage, from staff-committed lines. */
fun createInquiryFinancialDocumentRoute(
    createDocument: (CreateInquiryFinancialDocument.Command) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/inquiries" / inquiryIdPath / "financial-documents" meta {
        operationId = "createInquiryFinancialDocument"
        summary = "Create an inquiry's financial document"
        description =
            "Records the exact already-priced lines a verified staff user committed as version 1 of a new RELATED " +
            "Estimate, Quote, or Invoice lineage owned by the inquiry. A direct Quote or Invoice has no predecessor; no " +
            "intermediate snapshots are invented. Nothing is priced or checked against a catalog. `Location` contains " +
            "`/financial-documents/{documentId}`. Requires a staff USER holding `commerce.financial-document.create` and " +
            "`fionas.financial-terms.manage`."
        tags += financialDocuments
        receiving(createFinancialDocumentRequest to exampleCreateFinancialDocument)
        returning(
            Status.CREATED,
            documentResponse to exampleDocument(1, "QUOTE", 75, unpaid("681.25")),
            "The first snapshot and exact-reference reconciliation; `Location` holds its path.",
        )
        malformed("`inquiryId`")
        returningError(ErrorCategory.NOT_FOUND, "the inquiry does not exist.", "Inquiry $EXAMPLE_INQUIRY was not found")
        returningError(ErrorCategory.VALIDATION_FAILED, "the stage is unknown, or $LINES_REJECTED", "Invalid starting stage")
        financialErrors(CommercePermissions.FinancialDocumentCreate, STAFF_TERMS_FORBIDDEN)
        security = staffSessionSecurity
    } bindContract Method.POST to { id: String, _: String ->
        access.staffTerms(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val inquiryId = InquiryId(uuidIn(id, inquiryIdPath))
            val body = createFinancialDocumentRequest(request)
            val command =
                validating {
                    val stage =
                        requireNotNull(FirstSnapshotStage.entries.find { it.name == body.stage }) {
                            "Starting stage must be ESTIMATE, QUOTE, or INVOICE"
                        }
                    CreateInquiryFinancialDocument.Command(inquiryId, stage, body.lines.domain(), staffUser(request))
                }
            val created = createDocument(command)
            Response(Status.CREATED)
                .header("Location", "/financial-documents/${created.latest.document.id}")
                .with(documentResponse of created.toResponse())
        }
    }

/** `GET /inquiries/{inquiryId}/financial-documents`: the inquiry's lineages at their latest snapshots. */
fun listInquiryFinancialDocumentsRoute(
    listDocuments: (InquiryId) -> List<InquiryFinancialDocument>,
    access: AccessControl,
): ContractRoute =
    "/inquiries" / inquiryIdPath / "financial-documents" meta {
        operationId = "listInquiryFinancialDocuments"
        summary = "List an inquiry's financial documents"
        description =
            "Every financial-document lineage the inquiry owns, oldest first, each at its latest snapshot with its " +
            "line authorship and current settlement. Requires `commerce.financial-document.read`."
        tags += financialDocuments
        returning(
            Status.OK,
            inquiryDocumentsResponse to InquiryFinancialDocumentsResponse(EXAMPLE_INQUIRY, listOf(exampleInvoice)),
            "The inquiry's documents.",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry has this id.", "Inquiry $EXAMPLE_INQUIRY was not found")
        financialErrors(CommercePermissions.FinancialDocumentRead, unsafe = false)
    } bindContract Method.GET to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            val inquiryId = InquiryId(uuidIn(id, inquiryIdPath))
            val documents = listDocuments(inquiryId).map { it.toResponse() }
            Response(Status.OK).with(inquiryDocumentsResponse of InquiryFinancialDocumentsResponse(inquiryId.value.toString(), documents))
        }
    }

/** `GET /financial-documents/{documentId}`: the latest snapshot and current settlement. */
fun getFinancialDocumentRoute(
    getDocument: (UUID) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath meta {
        operationId = "getFinancialDocument"
        summary = "Read a financial document"
        description =
            "The lineage's latest immutable snapshot, its line authorship, and the settlement derived " +
            "from every payment applied to any of its versions. Only documents an inquiry owns exist here. Requires " +
            "`commerce.financial-document.read`."
        tags += financialDocuments
        returning(Status.OK, documentResponse to exampleInvoice, "The latest snapshot.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`documentId` is not a UUID.", "Malformed request: path 'documentId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        financialErrors(CommercePermissions.FinancialDocumentRead, unsafe = false)
    } bindContract Method.GET to { id: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            Response(Status.OK).with(documentResponse of getDocument(uuidIn(id, documentIdPath)).toResponse())
        }
    }

/** `GET /financial-documents/{documentId}/history`: every version, with its line authorship. */
fun getFinancialDocumentHistoryRoute(
    getHistory: (UUID) -> InquiryFinancialDocumentHistory,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "history" meta {
        operationId = "getFinancialDocumentHistory"
        summary = "Read a financial document's history"
        description =
            "Every self-contained immutable snapshot of the lineage, oldest first, with its line authorship. " +
            "Historical snapshots carry no settlement; the current settlement is on `GET /financial-documents/{documentId}`. " +
            "Requires `commerce.financial-document.read`."
        tags += financialDocuments
        returning(
            Status.OK,
            historyResponse to
                FinancialDocumentHistoryResponse(
                    EXAMPLE_DOCUMENT,
                    EXAMPLE_INQUIRY,
                    listOf(exampleEstimate, exampleChangedEstimate).map { it.copy(reconciliation = null) },
                ),
            "The ordered versions.",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "`documentId` is not a UUID.", "Malformed request: path 'documentId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        financialErrors(CommercePermissions.FinancialDocumentRead, unsafe = false)
    } bindContract Method.GET to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            Response(Status.OK).with(historyResponse of getHistory(uuidIn(id, documentIdPath)).toResponse())
        }
    }

/** `POST /financial-documents/{documentId}/quote`: issues the latest estimate as a quote. */
fun issueQuoteRoute(
    issueQuote: (UUID, Version) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    stageTransitionRoute(
        segment = "quote",
        operation = "issueQuote",
        summary = "Issue an estimate as a quote",
        description =
            "Issues the latest version, an estimate, as a quote: a new immutable snapshot with the same lines and their " +
                "authorship, never repriced. Only RELATED lineages support this standalone action. " +
                "Canonical INITIAL_ESTIMATE issuance requires POST /staff/requests/{inquiryId}/proposals with explicit deposit terms.",
        from = "an estimate",
        example = exampleQuote,
        transition = issueQuote,
        access = access,
    )

/** `POST /financial-documents/{documentId}/invoice`: issues the latest quote as an invoice. */
fun issueInvoiceRoute(
    issueInvoice: (UUID, Version) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    stageTransitionRoute(
        segment = "invoice",
        operation = "issueInvoice",
        summary = "Issue a quote as an invoice",
        description =
            "Issues the latest version, a quote, as an invoice: a new immutable snapshot with the same lines and their " +
                "authorship, never repriced. Payments applied to earlier versions stay attached to them and " +
                "still count toward the settlement. An estimate cannot become an invoice directly. " +
                "This manual action is allowed only for RELATED lineages. Canonical INITIAL_ESTIMATE lineages " +
                "reject it with illegal_transition and become Invoice only through active deposit satisfaction.",
        from = "a quote",
        example = exampleInvoice,
        transition = issueInvoice,
        access = access,
    )

private fun stageTransitionRoute(
    segment: String,
    operation: String,
    summary: String,
    description: String,
    from: String,
    example: FinancialDocumentResponse,
    transition: (UUID, Version) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / segment meta {
        operationId = operation
        this.summary = summary
        this.description =
            "$description Requires `commerce.financial-document.create`. Returns the new latest snapshot with the " +
            "lineage's current settlement."
        tags += financialDocuments
        receiving(transitionRequest to StageTransitionRequest(example.version - 1))
        returning(Status.OK, documentResponse to example, "The new latest snapshot.")
        malformed("`documentId`")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        staleVersion(" A latest version that is not $from answers the same status with code `illegal_transition`.")
        returningError(ErrorCategory.VALIDATION_FAILED, "`expectedVersion` is below 1.", "A version number must be at least 1, but was 0")
        financialErrors(CommercePermissions.FinancialDocumentCreate, unsafe = true)
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val expected = validating { Version.of(transitionRequest(request).expectedVersion) }
            Response(Status.OK).with(documentResponse of transition(documentId, expected).toResponse())
        }
    }

/**
 * `POST /financial-documents/{documentId}/change-orders`: commits the complete final lines a
 * verified staff user authored for the exact version they reviewed, in the current stage.
 */
fun createChangeOrderRoute(
    changeOrder: (CreateChangeOrder.Command) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "change-orders" meta {
        operationId = "createChangeOrder"
        summary = "Revise a financial document's lines"
        description =
            "Fiona's staff change order: commits the complete final lines a verified staff user authored for the version " +
            "they reviewed, appending them as a new version in the same stage (estimate, RELATED quote, or invoice), " +
            "authored by that user. Existing lines keep their ids when carried unchanged or overridden (`lineItemId`); " +
            "reviewed lines left out are removed; new lines (`key`) are added with ids derived from the document, the " +
            "reviewed version and the key. Nothing is priced or checked against a catalog; totals are derived from the " +
            "lines. Lines identical to the current ones are no financial change (`NO_FINANCIAL_CHANGE`). The resulting " +
            "total must be nonnegative (`NEGATIVE_DOCUMENT_TOTAL`); zero totals and negative reconciliation balances are " +
            "allowed. Canonical Quote change orders are rejected with illegal_transition (use the staff proposal " +
            "quote-revisions); a CLOSED canonical Invoice rejects ordinary change orders with illegal_transition. Requires " +
            "a staff USER holding `commerce.financial-document.create` and `fionas.financial-terms.manage`."
        tags += financialDocuments
        receiving(changeOrderRequest to exampleChangeOrder)
        returning(Status.OK, documentResponse to exampleChangedEstimate, "The new latest snapshot.")
        malformed("`documentId` or a body `lineItemId`, or a line names both or neither of `lineItemId` and `key`;")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        staleVersion(" `illegal_transition` at the same status for a canonical Quote or a CLOSED canonical lineage.")
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "$LINES_REJECTED Stable `violations` codes: `LINE_NOT_IN_REVIEWED_DOCUMENT`, `CURRENCY_MISMATCH`, " +
                "`NO_FINANCIAL_CHANGE`, `NEGATIVE_DOCUMENT_TOTAL`.",
            "The proposed lines are exactly the current lines; there is no financial change",
            listOf(ValidationViolationResponse(LineProposalViolations.NO_FINANCIAL_CHANGE)),
        )
        financialErrors(CommercePermissions.FinancialDocumentCreate, STAFF_TERMS_FORBIDDEN)
        security = staffSessionSecurity
    } bindContract Method.POST to { id: String, _: String ->
        access.staffTerms(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val body = changeOrderRequest(request)
            val identities = body.lines.identities(changeOrderRequest)
            val command =
                validating {
                    CreateChangeOrder.Command(
                        documentId,
                        Version.of(body.expectedVersion),
                        body.lines.domain(identities),
                        staffUser(request),
                    )
                }
            Response(Status.OK).with(documentResponse of changeOrder(command).toResponse())
        }
    }

/**
 * `POST /financial-documents/{documentId}/payments`: records money received and applies all
 * of it to the document's latest snapshot, a quote or an invoice.
 */
fun recordPaymentRoute(
    recordPayment: (RecordDocumentPayment.Command) -> RecordedPayment,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "payments" meta {
        operationId = "recordPayment"
        summary = "Record a payment against a financial document"
        description =
            "Records a payment and applies the whole of it to the exact version named, which must be the document's " +
            "latest version, a quote (for example a deposit) or an invoice. The allocation stays attached to that " +
            "version when the document later advances, and still counts toward its settlement. The payment's currency " +
            "is the document's. A canonical INITIAL_ESTIMATE Quote requires expectedProposalId naming the current " +
            "payable proposal and one payment exactly equal to its complete approved deposit, with no historical " +
            "allocation. Partial and excessive deposits reject without writes. Exact acceptance atomically issues " +
            "the Invoice; extra money requires a distinct subsequent Invoice payment. Invoice partial payments " +
            "remain allowed. The response retains the original Quote allocation and returns current settlement; " +
            "GET /staff/requests/{inquiryId} to reload the Invoice, BOOKED lifecycle, satisfied deposit and payment histories. " +
            "RELATED lineages never automatically advance. Payment status is never stored: settlement is derived. Requires " +
            "`commerce.payment.record`."
        tags += payments
        receiving(recordPaymentRequest to examplePayment)
        returning(Status.CREATED, recordedPaymentResponse to exampleRecordedPayment, "The recorded payment and the settlement after it.")
        malformed("`documentId` or body `expectedProposalId`")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        returningError(
            ErrorCategory.CONFLICT,
            "`expectedProposalId` is no longer the current payable proposal; " +
                "`documentVersion` is no longer the document's latest version (reload the " +
                "document before retrying against its current version); or the external provider and reference pair " +
                "is already recorded for another payment (reloading does not resolve it). " +
                "Historical applied payment rejects with `illegal_transition` at the same status.",
            STALE_DOCUMENT,
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid: an amount that is not a positive exact decimal with at most the currency's minor-unit " +
                "digits, an unknown method, or a `receivedAt` that is not RFC 3339. A latest version that is an " +
                "estimate answers the same status with code `invariant_violated`: payments are accepted against a " +
                "quote or an invoice. A canonical Quote also requires expectedProposalId and an exact complete deposit amount.",
            "Payment amount must be an exact decimal string, for example 300.00",
        )
        financialErrors(CommercePermissions.PaymentRecord, unsafe = true)
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.PaymentRecord).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val body = recordPaymentRequest(request)
            val expectedProposalId = body.expectedProposalId?.let { InquiryProposalId(proposalPaymentId(it)) }
            val command =
                validating {
                    RecordDocumentPayment.Command(
                        documentId = documentId,
                        documentVersion = Version.of(body.documentVersion),
                        amount = paymentAmount(body.amount),
                        method = paymentMethod(body.method),
                        receivedAt = body.receivedAt?.let(::receivedAt),
                        externalReference =
                            body.externalReference?.let { ExternalPaymentReference(it.provider.trim(), it.reference.trim()) },
                        expectedProposalId = expectedProposalId,
                    )
                }
            Response(Status.CREATED).with(recordedPaymentResponse of recordPayment(command).toResponse())
        }
    }

/**
 * `GET /financial-documents/{documentId}/payments`: the complete history of every payment ever
 * allocated to the lineage, so its payment, allocation, refund, and refund-allocation ids
 * survive the responses that recorded them.
 */
fun listFinancialDocumentPaymentsRoute(
    listPayments: (UUID) -> List<PaymentHistory>,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "payments" meta {
        operationId = "listFinancialDocumentPayments"
        summary = "List a financial document's payments"
        description =
            "Every payment that has ever been allocated to any version of the lineage, by `receivedAt`, then " +
            "`paymentId`, each with its complete history: the payment, its allocations, its refunds, which allocation " +
            "each refund unwound, and the payment's reconciliation derived from exactly those facts. Discovery is " +
            "historical: a payment stays listed after refunds unwind its allocations here completely. Each payment " +
            "is the whole payment, never only its part in this document: its allocations to other lineages are " +
            "included, because its reconciliation depends on them, so an allocation's `documentId` need not be this " +
            "document's; select `documentId` to show this document's own. A payment never allocated to this lineage " +
            "(for example one recorded with `POST /payments` and still unapplied) is not listed. An empty list means " +
            "the document exists and has no payments. Only documents an inquiry owns exist here. Requires " +
            "`commerce.financial-document.read`."
        tags += payments
        returning(Status.OK, documentPaymentsResponse to exampleDocumentPayments, "The document's payment histories.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`documentId` is not a UUID.", "Malformed request: path 'documentId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        financialErrors(CommercePermissions.FinancialDocumentRead, unsafe = false)
    } bindContract Method.GET to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val payments = listPayments(documentId).map { it.toResponse() }
            Response(Status.OK).with(documentPaymentsResponse of FinancialDocumentPaymentsResponse(documentId.toString(), payments))
        }
    }

/** `GET /payments/unapplied`: the runtime's operational queue, including standalone receipts. */
fun listUnappliedPaymentsRoute(
    listPayments: () -> List<PaymentHistory>,
    access: AccessControl,
): ContractRoute =
    "/payments/unapplied" meta {
        operationId = "listUnappliedPayments"
        summary = "List payments with unapplied value"
        description =
            "Returns complete runtime payment histories whose derived reconciliation.unallocated is positive, " +
            "ordered by receivedAt then paymentId. Unallocated is net received minus net allocated after refunds " +
            "and allocation unwinds. No inquiry or document association is required. Requires `commerce.payment.record`."
        tags += payments
        returning(Status.OK, unappliedPaymentsResponse to UnappliedPaymentsResponse(emptyList()), "The available payment histories.")
        financialErrors(CommercePermissions.PaymentRecord, unsafe = false)
    } bindContract Method.GET to
        access.requirePermission(CommercePermissions.PaymentRecord).then { _: Request ->
            Response(Status.OK).with(unappliedPaymentsResponse of UnappliedPaymentsResponse(listPayments().map { it.toResponse() }))
        }

/** `POST /payments`: records received money without requiring an allocation. */
fun recordStandalonePaymentRoute(
    recordPayment: (RecordPayment.Command) -> PaymentRecord,
    access: AccessControl,
): ContractRoute =
    "/payments" meta {
        operationId = "recordStandalonePayment"
        summary = "Record money received"
        description =
            "Records an immutable payment fact. It may remain unapplied or be allocated later, in parts, through " +
            "`POST /payments/{paymentId}/allocations`. Requires `commerce.payment.record`."
        tags += payments
        receiving(recordStandalonePaymentRequest to exampleStandalonePayment)
        returning(Status.CREATED, paymentRecordResponse to examplePaymentRecord, "The received payment, without allocation fields.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "the body cannot be read.", "Malformed request: body 'body'")
        returningError(
            ErrorCategory.CONFLICT,
            "the external provider and reference pair already exists.",
            "Payment id or external reference already exists",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "the amount, ISO 4217 currency, method, receivedAt, or external reference is invalid.",
            "Payment amount must be an exact decimal string, for example 300.00",
        )
        financialErrors(CommercePermissions.PaymentRecord, unsafe = true)
    } bindContract Method.POST to
        access.requirePermission(CommercePermissions.PaymentRecord).then { request: Request ->
            val body = recordStandalonePaymentRequest(request)
            val command =
                validating {
                    RecordPayment.Command(
                        amount = paymentAmount(body.amount),
                        currency = paymentCurrency(body.currency),
                        method = paymentMethod(body.method),
                        receivedAt = body.receivedAt?.let(::receivedAt),
                        externalReference =
                            body.externalReference?.let {
                                ExternalPaymentReference(
                                    it.provider.trim(),
                                    it.reference.trim(),
                                )
                            },
                    )
                }
            Response(Status.CREATED).with(paymentRecordResponse of recordPayment(command).toResponse())
        }

/** `POST /payments/{paymentId}/allocations`: applies part of a payment to an exact snapshot. */
fun allocatePaymentRoute(
    allocate: (AllocatePayment.Command) -> AllocatedPayment,
    access: AccessControl,
): ContractRoute =
    "/payments" / paymentIdPath / "allocations" meta {
        operationId = "allocatePayment"
        summary = "Allocate a payment to a financial document"
        description =
            "Applies part of an existing payment to the specified latest Quote or Invoice snapshot of a Fiona-owned " +
            "lineage. The allocation remains attached to that version; settlement is derived from allocation " +
            "history. Canonical INITIAL_ESTIMATE Quotes reject standalone allocations: their deposits require " +
            "one exact payment naming the current proposal through the document payment operation. " +
            "Invoices retain ordinary partial allocations. " +
            "RELATED lineages never automatically advance. Requires `commerce.payment.record`."
        tags += payments
        receiving(allocatePaymentRequest to exampleAllocatePayment)
        returning(
            Status.CREATED,
            paymentAllocationResponse to examplePaymentAllocation,
            "The allocation and resulting document settlement.",
        )
        malformed("`paymentId` or body `documentId`")
        returningError(
            ErrorCategory.NOT_FOUND,
            "the payment or Fiona-owned document does not exist.",
            "Payment $EXAMPLE_DOCUMENT was not found",
        )
        staleVersion(
            " A canonical Quote also answers this status with `illegal_transition`: standalone allocation cannot fund its deposit.",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "the amount or version is invalid, currencies differ, the payment would be over-allocated, or the latest " +
                "document is an Estimate (code `invariant_violated`).",
            "Payment amount must be an exact decimal string, for example 300.00",
        )
        financialErrors(CommercePermissions.PaymentRecord, unsafe = true)
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.PaymentRecord).then { request: Request ->
            val paymentId = uuidIn(id, paymentIdPath)
            val body = allocatePaymentRequest(request)
            val documentId = allocationDocumentId(body.documentId)
            val command =
                validating {
                    AllocatePayment.Command(
                        paymentId = paymentId,
                        documentId = documentId,
                        documentVersion = Version.of(body.documentVersion),
                        amount = paymentAmount(body.amount),
                    )
                }
            Response(Status.CREATED).with(paymentAllocationResponse of allocate(command).toResponse())
        }
    }

/** `POST /payments/{paymentId}/refunds`: returns money with caller-selected allocation unwinds. */
fun recordRefundRoute(
    recordRefund: (RecordRefund.Command) -> ReconciledRefund,
    access: AccessControl,
): ContractRoute =
    "/payments" / paymentIdPath / "refunds" meta {
        operationId = "recordRefund"
        summary = "Record a payment refund"
        description =
            "Returns part or all of a payment. Each allocation portion explicitly names a previous payment allocation " +
            "to unwind; an empty list refunds unapplied value. No document snapshot changes. The runtime validates " +
            "the complete payment history. Requires `commerce.refund.record`."
        tags += payments
        receiving(recordRefundRequest to exampleRefundRequest)
        returning(
            Status.CREATED,
            recordedRefundResponse to exampleRefundResponse,
            "The refund, its unwind portions, and payment reconciliation.",
        )
        malformed("`paymentId` or an allocation's `paymentAllocationId`")
        returningError(
            ErrorCategory.NOT_FOUND,
            "the payment or a named payment allocation does not exist.",
            "Payment $EXAMPLE_DOCUMENT was not found",
        )
        returningError(
            ErrorCategory.CONFLICT,
            "the external provider and refund reference already exist.",
            "Refund id or external reference already exists",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "the amount, currency, method, time, reference, or allocation relationship is invalid. A refund that " +
                "makes the complete payment history impossible answers the same status with code `invariant_violated`.",
            "Refund amount must be an exact decimal string, for example 50.00",
        )
        financialErrors(CommercePermissions.RefundRecord, unsafe = true)
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.RefundRecord).then { request: Request ->
            val paymentId = uuidIn(id, paymentIdPath)
            val body = recordRefundRequest(request)
            val command =
                validating {
                    RecordRefund.Command(
                        paymentId = paymentId,
                        amount = refundAmount(body.amount),
                        currency = paymentCurrency(body.currency),
                        method = paymentMethod(body.method),
                        refundedAt = body.refundedAt?.let(::refundedAt),
                        externalReference =
                            body.externalReference?.let { ExternalRefundReference(it.provider.trim(), it.reference.trim()) },
                        allocations =
                            body.allocations.map {
                                RecordRefund.AllocationCommand(
                                    refundAllocationId(it.paymentAllocationId),
                                    refundAmount(it.amount),
                                )
                            },
                    )
                }
            Response(Status.CREATED).with(recordedRefundResponse of recordRefund(command).toResponse())
        }
    }

private val EXACT_DECIMAL = Regex("""\d+(\.\d+)?""")

private fun paymentAmount(text: String): BigDecimal {
    require(EXACT_DECIMAL.matches(text)) { "Payment amount must be an exact decimal string, for example 300.00" }
    return BigDecimal(text)
}

private fun refundAmount(text: String): BigDecimal {
    require(EXACT_DECIMAL.matches(text)) { "Refund amount must be an exact decimal string, for example 50.00" }
    return BigDecimal(text)
}

private fun paymentMethod(text: String): PaymentMethod =
    requireNotNull(PaymentMethod.entries.find { it.name == text }) {
        "Payment method must be one of ${PaymentMethod.entries.joinToString()}"
    }

private fun paymentCurrency(text: String): Currency =
    try {
        Currency.getInstance(text)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Currency must be an ISO 4217 code", e)
    }

private fun receivedAt(text: String) =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("receivedAt must be an RFC 3339 timestamp, for example 2026-09-27T17:05:00Z", e)
    }

private fun refundedAt(text: String) =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("refundedAt must be an RFC 3339 timestamp, for example 2026-09-28T20:02:00Z", e)
    }

/** The UUID in a path segment; one that is not a UUID is reported as the unreadable path value it is. */
private fun uuidIn(
    segment: String,
    path: PathLens<String>,
): UUID =
    try {
        UUID.fromString(segment)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(path.meta), cause = e)
    }

/** The allocation id is a field of the refund body, so its lens failure carries body metadata. */
private fun refundAllocationId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(paymentAllocationIdBodyMeta), cause = e)
    }

/** An unreadable allocation destination is malformed body input, before domain validation. */
private fun allocationDocumentId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(allocationDocumentIdBodyMeta), cause = e)
    }

private fun proposalPaymentId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(recordPaymentRequest.metas.single().copy(name = "expectedProposalId")), cause = e)
    }

private fun InquiryFinancialDocumentHistory.toResponse() =
    FinancialDocumentHistoryResponse(documentId.toString(), inquiryId.value.toString(), versions.map { it.toResponse(inquiryId, null) })

private fun RecordedPayment.toResponse() =
    RecordedPaymentResponse(
        paymentId = payment.id.toString(),
        allocationId = allocation.id.toString(),
        documentId = allocation.financialDocumentReference.id.toString(),
        documentVersion = allocation.financialDocumentReference.version.number,
        method = payment.method.name,
        amount = payment.amount.decimal(),
        currency = payment.currency.currencyCode,
        receivedAt = payment.receivedAt.toString(),
        externalReference = payment.externalReference?.let { PaymentExternalReference(it.provider, it.reference) },
        allocatedAt = allocation.allocatedAt.toString(),
        reconciliation = document.reconciliation.toResponse(),
    )

private fun PaymentRecord.toResponse() =
    PaymentRecordResponse(
        paymentId = id.toString(),
        method = method.name,
        amount = amount.decimal(),
        currency = currency.currencyCode,
        receivedAt = receivedAt.toString(),
        externalReference = externalReference?.let { PaymentExternalReference(it.provider, it.reference) },
    )

private fun AllocatedPayment.toResponse() =
    PaymentAllocationResponse(
        allocationId = allocation.id.toString(),
        paymentId = allocation.paymentReference.toString(),
        documentId = allocation.financialDocumentReference.id.toString(),
        documentVersion = allocation.financialDocumentReference.version.number,
        amount = allocation.amount.decimal(),
        currency = allocation.currency.currencyCode,
        allocatedAt = allocation.allocatedAt.toString(),
        reconciliation = document.reconciliation.toResponse(),
    )

private fun ReconciledRefund.toResponse() =
    RecordedRefundResponse(
        refundId = recorded.refund.id.toString(),
        paymentId = recorded.refund.paymentReference.toString(),
        amount = recorded.refund.amount.decimal(),
        currency = recorded.refund.currency.currencyCode,
        method = recorded.refund.method.name,
        refundedAt = recorded.refund.refundedAt.toString(),
        externalReference = recorded.refund.externalReference?.let { RefundExternalReference(it.provider, it.reference) },
        allocations =
            recorded.allocations.map {
                RefundAllocationResponse(
                    it.id.toString(),
                    it.paymentAllocationReference.toString(),
                    it.amount.decimal(),
                    it.currency.currencyCode,
                    it.allocatedAt.toString(),
                )
            },
        reconciliation = reconciliation.toResponse(),
    )

// Every fact of the history, in commerce-runtime's order; nothing is filtered, re-sorted, or recomputed.
internal fun PaymentHistory.toResponse() =
    PaymentHistoryResponse(
        payment = payment.toResponse(),
        allocations =
            allocations.map {
                PaymentAllocationRecordResponse(
                    allocationId = it.id.toString(),
                    paymentId = it.paymentReference.toString(),
                    documentId = it.financialDocumentReference.id.toString(),
                    documentVersion = it.financialDocumentReference.version.number,
                    amount = it.amount.decimal(),
                    currency = it.currency.currencyCode,
                    allocatedAt = it.allocatedAt.toString(),
                )
            },
        refunds =
            refunds.map {
                RefundRecordResponse(
                    refundId = it.id.toString(),
                    paymentId = it.paymentReference.toString(),
                    amount = it.amount.decimal(),
                    currency = it.currency.currencyCode,
                    method = it.method.name,
                    refundedAt = it.refundedAt.toString(),
                    externalReference =
                        it.externalReference?.let { reference ->
                            RefundExternalReference(reference.provider, reference.reference)
                        },
                )
            },
        refundAllocations =
            refundAllocations.map {
                RefundAllocationRecordResponse(
                    refundAllocationId = it.id.toString(),
                    refundId = it.refundReference.toString(),
                    paymentAllocationId = it.paymentAllocationReference.toString(),
                    amount = it.amount.decimal(),
                    currency = it.currency.currencyCode,
                    allocatedAt = it.allocatedAt.toString(),
                )
            },
        reconciliation = reconciliation.toResponse(),
    )

private fun PaymentReconciliation.toResponse() =
    PaymentReconciliationResponse(
        paymentAmount.decimal(),
        totalRefunded.decimal(),
        netReceived.decimal(),
        grossAllocated.decimal(),
        allocationReversals.decimal(),
        refundAllocations.decimal(),
        netAllocated.decimal(),
        unallocated.decimal(),
        currency.currencyCode,
    )
