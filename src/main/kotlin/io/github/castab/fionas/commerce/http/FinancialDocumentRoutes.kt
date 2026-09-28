package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.PricedSnapshot
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordedPayment
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
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
import java.util.UUID

/** The body of `POST /inquiries/{inquiryId}/estimates`: commercial inputs only, never amounts or lines. */
@Serializable
data class CreateInquiryEstimateRequest(
    @ApiProperty(
        description =
            "The revision of Fiona's Offerings catalog to price from, as `GET /offering-catalog` returned it. The " +
                "estimate uses exactly this revision, never a later one. At least 1.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "The guests to price the event for. At least 1.")
    val guestCount: Int,
    @ApiProperty(
        description = "Whether `guestCount` is a lower bound (\"100+ guests\"). Pricing still uses `guestCount`. False when absent.",
    )
    val guestCountIsMinimum: Boolean = false,
    @ApiProperty(description = "The service duration in minutes: one of 90, 120, 150, or 180.")
    val durationMinutes: Int,
    @ApiProperty(description = "The chosen offerings, one entry per catalog category, in the order they are shown.")
    val selections: List<PricingSelection>,
)

/** The body of `POST /financial-documents/{documentId}/change-orders`: the revised commercial inputs. */
@Serializable
data class ChangeOrderRequest(
    @ApiProperty(
        description =
            "The version of the document the change was made from, which must still be its latest version. A " +
                "document that has moved on answers `409 conflict`, so no change lands on a version its caller never saw.",
    )
    val expectedVersion: Int,
    @ApiProperty(
        description =
            "The catalog revision to reprice from, chosen deliberately: the one the document was priced from keeps " +
                "its price book, a later one adopts the later prices. It is never replaced by another. At least 1.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "The revised number of guests. At least 1.")
    val guestCount: Int,
    @ApiProperty(description = "Whether `guestCount` is a lower bound (\"100+ guests\"). False when absent.")
    val guestCountIsMinimum: Boolean = false,
    @ApiProperty(description = "The revised service duration in minutes: one of 90, 120, 150, or 180.")
    val durationMinutes: Int,
    @ApiProperty(description = "The revised chosen offerings, one entry per catalog category, in the order they are shown.")
    val selections: List<PricingSelection>,
)

/** The offerings chosen from one catalog category. */
@Serializable
data class PricingSelection(
    @ApiProperty(description = "The catalog category's key, for example `topping`.")
    val category: String,
    @ApiProperty(description = "The keys of the chosen offerings of that category, in the order chosen.")
    val offerings: List<String>,
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
)

/** A payment's identity in an external system. */
@Serializable
data class PaymentExternalReference(
    @ApiProperty(description = "The external system's stable name. Must not be blank.")
    val provider: String,
    @ApiProperty(description = "The payment's identifier in that system. Must not be blank.")
    val reference: String,
)

/**
 * One immutable financial-document snapshot, as commerce-runtime's ledger records it, with
 * the Fiona pricing inputs it was priced from. Settlement is derived, never stored.
 */
@Serializable
data class FinancialDocumentResponse(
    @ApiProperty(description = "The document lineage's id, shared by every version.", format = "uuid")
    val id: String,
    @ApiProperty(description = "This snapshot's version within the lineage, from 1.")
    val version: Int,
    @ApiProperty(description = "The version this snapshot succeeded; absent for version 1.")
    val previousVersion: Int? = null,
    @ApiProperty(description = "The lifecycle stage of this snapshot: `ESTIMATE`, `QUOTE`, or `INVOICE`.")
    val stage: String,
    @ApiProperty(description = "The id of the inquiry the lineage belongs to.", format = "uuid")
    val inquiryId: String,
    @ApiProperty(description = "The commercial inputs Fiona priced this snapshot from.")
    val pricing: DocumentPricing,
    @ApiProperty(description = "The snapshot's lines, in order. Line ids are durable ledger facts.")
    val lines: List<FinancialDocumentLine>,
    @ApiProperty(description = "The sum of the lines' subtotals, an exact decimal.")
    val subtotal: String,
    @ApiProperty(description = "The sum of the lines' tax, an exact decimal. No tax is charged yet, so it is zero.")
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

/** The commercial inputs a snapshot was priced from. */
@Serializable
data class DocumentPricing(
    @ApiProperty(description = "The catalog revision the snapshot was priced from.")
    val catalogRevision: Int,
    @ApiProperty(description = "The guests the snapshot was priced for.")
    val guestCount: Int,
    @ApiProperty(description = "Whether the guest count was a lower bound.")
    val guestCountIsMinimum: Boolean,
    @ApiProperty(description = "The service duration in minutes.")
    val durationMinutes: Int,
    @ApiProperty(description = "The chosen offerings, in the order they were submitted.")
    val selections: List<PricingSelection>,
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
    @ApiProperty(description = "The line's tax, an exact decimal; zero for now.")
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
    @ApiProperty(description = "Every snapshot, oldest first, each with the pricing inputs it was priced from.")
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

private val createEstimateRequest = jsonBody(CreateInquiryEstimateRequest.serializer())
private val changeOrderRequest = jsonBody(ChangeOrderRequest.serializer())
private val transitionRequest = jsonBody(StageTransitionRequest.serializer())
private val recordPaymentRequest = jsonBody(RecordPaymentRequest.serializer())
private val documentResponse = jsonBody(FinancialDocumentResponse.serializer())
private val historyResponse = jsonBody(FinancialDocumentHistoryResponse.serializer())
private val inquiryDocumentsResponse = jsonBody(InquiryFinancialDocumentsResponse.serializer())
private val recordedPaymentResponse = jsonBody(RecordedPaymentResponse.serializer())

// Plain strings for the contract: a contract treats a path value its lens rejects as an
// unmatched route (404), while an id that is not a UUID is a malformed request (400).
private val documentIdPath =
    Path.of("documentId", "The financial-document lineage's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val inquiryIdPath =
    Path.of("inquiryId", "The inquiry's id.", mapOf("schema" to mapOf("format" to "uuid")))

private val financialDocuments =
    Tag(
        "Financial documents",
        "An inquiry's persisted estimates, quotes, and invoices: immutable commerce-runtime snapshots, priced by Fiona's " +
            "server from commercial inputs, each with the pricing inputs it was priced from.",
    )

private val payments = Tag("Payments", "Money received and applied to an inquiry's financial documents.")

private const val EXAMPLE_DOCUMENT = "5f0c6a7e-8c1d-4f63-9b2a-0d8e7f6a5b4c"
private const val EXAMPLE_INQUIRY = "c755f7cd-1e28-4c75-a85f-d066ede7387d"
private const val NOT_FOUND_DOCUMENT = "Financial document $EXAMPLE_DOCUMENT was not found"
private const val STALE_DOCUMENT = "Financial document $EXAMPLE_DOCUMENT is at v4, not the expected v3; reload it and retry"

private val exampleSelections =
    listOf(
        PricingSelection("soft-serve-flavor", listOf("vanilla", "horchata")),
        PricingSelection("topping", listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")),
        PricingSelection("cone-option", listOf("waffle-cone")),
    )

private val exampleCreateEstimate =
    CreateInquiryEstimateRequest(catalogRevision = 20, guestCount = 75, durationMinutes = 120, selections = exampleSelections)

private val exampleChangeOrder =
    ChangeOrderRequest(expectedVersion = 1, catalogRevision = 20, guestCount = 100, durationMinutes = 120, selections = exampleSelections)

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
        id = EXAMPLE_DOCUMENT,
        version = version,
        previousVersion = (version - 1).takeIf { it >= 1 },
        stage = stage,
        inquiryId = EXAMPLE_INQUIRY,
        pricing = DocumentPricing(20, guests, false, 120, exampleSelections),
        lines = lines,
        subtotal = total,
        taxAmount = "0.00",
        total = total,
        currency = "USD",
        reconciliation = reconciliation,
    )
}

private fun unpaid(total: String) = DocumentReconciliation("0.00", "0.00", total, "USD")

private val exampleEstimate = exampleDocument(1, "ESTIMATE", 75, unpaid("681.25"))
private val exampleChangedEstimate = exampleDocument(2, "ESTIMATE", 100, unpaid("825.00"))
private val exampleQuote = exampleDocument(3, "QUOTE", 100, unpaid("825.00"))
private val exampleInvoice = exampleDocument(4, "INVOICE", 100, DocumentReconciliation("300.00", "300.00", "525.00", "USD"))

private val examplePayment =
    RecordPaymentRequest(
        documentVersion = 3,
        amount = "300.00",
        method = "CARD",
        receivedAt = "2026-09-27T17:05:00Z",
        externalReference = PaymentExternalReference("square", "pay_7Q2Rk9"),
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

/** The errors every staff-protected financial route answers, in addition to its own. */
private fun RouteMetaDsl.staffErrors() {
    returningError(ErrorCategory.UNAUTHENTICATED, "there is no active staff session.", "Authentication is required")
    returningError(
        ErrorCategory.FORBIDDEN,
        "the staff user lacks the route's permission, or an unsafe request's browser origin is not trusted.",
        "The authenticated principal is not permitted to perform this request",
    )
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

private const val PRICING_REJECTED =
    "a value is invalid, or the inputs cannot be priced: they do not fit the catalog revision (for example " +
        "`TOO_MANY_SELECTIONS`, `UNKNOWN_OFFERING`) or Fiona's pricing (for example `INVALID_GUEST_COUNT`, " +
        "`UNSUPPORTED_DURATION`). The message names each violation's stable code."

/**
 * `POST /inquiries/{inquiryId}/estimates`: persists an estimate for an inquiry, priced by the
 * server from commercial inputs. Requires `commerce.financial-document.create`.
 */
fun createInquiryEstimateRoute(
    createEstimate: (InquiryId, FionasPricingInputs) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/inquiries" / inquiryIdPath / "estimates" meta {
        operationId = "createInquiryEstimate"
        summary = "Persist an estimate for an inquiry"
        description =
            "Prices the commercial inputs from exactly the catalog revision they name, with the same Fiona pricing as " +
            "`POST /estimate-preview`, and records the lines as version 1 of a new financial-document lineage owned by " +
            "the inquiry, together with the inputs it was priced from. Lines, amounts, and totals are never accepted " +
            "from the caller. The `Location` response header holds the document's path, " +
            "`/financial-documents/{documentId}`. Requires `commerce.financial-document.create`."
        tags += financialDocuments
        receiving(createEstimateRequest to exampleCreateEstimate)
        returning(Status.CREATED, documentResponse to exampleEstimate, "The persisted estimate. `Location` holds its path.")
        malformed("`inquiryId`")
        returningError(
            ErrorCategory.NOT_FOUND,
            "no inquiry has this id, or the catalog revision does not exist.",
            "Offerings catalog revision r20 was not found",
        )
        returningError(ErrorCategory.VALIDATION_FAILED, PRICING_REJECTED, "The selection cannot be estimated: INVALID_GUEST_COUNT (...)")
        staffErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val inquiryId = InquiryId(uuidIn(id, inquiryIdPath))
            val body = createEstimateRequest(request)
            val created =
                createEstimate(
                    inquiryId,
                    pricingInputs(
                        body.catalogRevision,
                        body.guestCount,
                        body.guestCountIsMinimum,
                        body.durationMinutes,
                        body.selections.map { it.category to it.offerings },
                    ),
                )
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
            "pricing inputs and current settlement. Requires `commerce.financial-document.read`."
        tags += financialDocuments
        returning(
            Status.OK,
            inquiryDocumentsResponse to InquiryFinancialDocumentsResponse(EXAMPLE_INQUIRY, listOf(exampleInvoice)),
            "The inquiry's documents.",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry has this id.", "Inquiry $EXAMPLE_INQUIRY was not found")
        staffErrors()
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
            "The lineage's latest immutable snapshot, the pricing inputs it was priced from, and the settlement derived " +
            "from every payment applied to any of its versions. Only documents an inquiry owns exist here. Requires " +
            "`commerce.financial-document.read`."
        tags += financialDocuments
        returning(Status.OK, documentResponse to exampleInvoice, "The latest snapshot.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`documentId` is not a UUID.", "Malformed request: path 'documentId'")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        staffErrors()
    } bindContract Method.GET to { id: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            Response(Status.OK).with(documentResponse of getDocument(uuidIn(id, documentIdPath)).toResponse())
        }
    }

/** `GET /financial-documents/{documentId}/history`: every version, with its pricing inputs. */
fun getFinancialDocumentHistoryRoute(
    getHistory: (UUID) -> InquiryFinancialDocumentHistory,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "history" meta {
        operationId = "getFinancialDocumentHistory"
        summary = "Read a financial document's history"
        description =
            "Every immutable snapshot of the lineage, oldest first, each with the pricing inputs Fiona priced it from. " +
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
        staffErrors()
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
            "Issues the latest version, an estimate, as a quote: a new immutable snapshot with the same lines and the " +
                "same pricing inputs, never repriced.",
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
            "Issues the latest version, a quote, as an invoice: a new immutable snapshot with the same lines and the " +
                "same pricing inputs, never repriced. Payments applied to earlier versions stay attached to them and " +
                "still count toward the settlement. An estimate cannot become an invoice directly.",
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
        staffErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val expected = validating { Version.of(transitionRequest(request).expectedVersion) }
            Response(Status.OK).with(documentResponse of transition(documentId, expected).toResponse())
        }
    }

/**
 * `POST /financial-documents/{documentId}/change-orders`: reprices the latest snapshot from
 * revised commercial inputs, in its current stage.
 */
fun createChangeOrderRoute(
    changeOrder: (UUID, Version, FionasPricingInputs) -> InquiryFinancialDocument,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / documentIdPath / "change-orders" meta {
        operationId = "createChangeOrder"
        summary = "Reprice a financial document"
        description =
            "Fiona's change order: prices the revised commercial inputs from exactly the catalog revision they name " +
            "and appends the result as a new version in the same stage, whether estimate, quote, or invoice, with the " +
            "revised inputs as its pricing source. The new lines replace the current ones; lines, amounts, and totals " +
            "are never accepted from the caller. Inputs that price exactly as the current version does are no " +
            "financial change and are rejected. Requires `commerce.financial-document.create`."
        tags += financialDocuments
        receiving(changeOrderRequest to exampleChangeOrder)
        returning(Status.OK, documentResponse to exampleChangedEstimate, "The new latest snapshot.")
        malformed("`documentId`")
        returningError(
            ErrorCategory.NOT_FOUND,
            "no inquiry owns a document with this id, or the catalog revision does not exist.",
            NOT_FOUND_DOCUMENT,
        )
        staleVersion("")
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "$PRICING_REJECTED Inputs that produce exactly the current lines are rejected as no financial change.",
            "The revised pricing produces no financial change",
        )
        staffErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentCreate).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val body = changeOrderRequest(request)
            val expected = validating { Version.of(body.expectedVersion) }
            val inputs =
                pricingInputs(
                    body.catalogRevision,
                    body.guestCount,
                    body.guestCountIsMinimum,
                    body.durationMinutes,
                    body.selections.map { it.category to it.offerings },
                )
            Response(Status.OK).with(documentResponse of changeOrder(documentId, expected, inputs).toResponse())
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
            "is the document's. Payment status is never stored: the response's settlement is derived. Requires " +
            "`commerce.payment.record`."
        tags += payments
        receiving(recordPaymentRequest to examplePayment)
        returning(Status.CREATED, recordedPaymentResponse to exampleRecordedPayment, "The recorded payment and the settlement after it.")
        malformed("`documentId`")
        returningError(ErrorCategory.NOT_FOUND, "no inquiry owns a document with this id.", NOT_FOUND_DOCUMENT)
        staleVersion(" A provider and reference already recorded for another payment answer the same.")
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid: an amount that is not a positive exact decimal with at most the currency's minor-unit " +
                "digits, an unknown method, or a `receivedAt` that is not RFC 3339. A latest version that is an " +
                "estimate answers the same status with code `invariant_violated`: payments are accepted against a " +
                "quote or an invoice.",
            "Payment amount must be an exact decimal string, for example 300.00",
        )
        staffErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(CommercePermissions.PaymentRecord).then { request: Request ->
            val documentId = uuidIn(id, documentIdPath)
            val body = recordPaymentRequest(request)
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
                    )
                }
            Response(Status.CREATED).with(recordedPaymentResponse of recordPayment(command).toResponse())
        }
    }

private val EXACT_DECIMAL = Regex("""\d+(\.\d+)?""")

private fun paymentAmount(text: String): BigDecimal {
    require(EXACT_DECIMAL.matches(text)) { "Payment amount must be an exact decimal string, for example 300.00" }
    return BigDecimal(text)
}

private fun paymentMethod(text: String): PaymentMethod =
    requireNotNull(PaymentMethod.entries.find { it.name == text }) {
        "Payment method must be one of ${PaymentMethod.entries.joinToString()}"
    }

private fun receivedAt(text: String) =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("receivedAt must be an RFC 3339 timestamp, for example 2026-09-27T17:05:00Z", e)
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

private fun InquiryFinancialDocument.toResponse() = latest.toResponse(inquiryId, reconciliation)

private fun InquiryFinancialDocumentHistory.toResponse() =
    FinancialDocumentHistoryResponse(documentId.toString(), inquiryId.value.toString(), versions.map { it.toResponse(inquiryId, null) })

private fun PricedSnapshot.toResponse(
    inquiryId: InquiryId,
    reconciliation: FinancialDocumentReconciliation?,
) = FinancialDocumentResponse(
    id = document.id.toString(),
    version = document.version.number,
    previousVersion = document.previousVersion?.number,
    stage =
        when (document) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        },
    inquiryId = inquiryId.value.toString(),
    pricing = pricing.toResponse(),
    lines = document.lineItems.map { it.toResponse() },
    subtotal = document.subtotal.decimal(),
    taxAmount = document.taxAmount.decimal(),
    total = document.total.decimal(),
    currency = document.currency.currencyCode,
    reconciliation = reconciliation?.toResponse(),
)

private fun FionasPricingInputs.toResponse() =
    DocumentPricing(
        catalogRevision = catalogRevision.number,
        guestCount = context.guestCount,
        guestCountIsMinimum = context.guestCountIsMinimum,
        durationMinutes = Math.toIntExact(context.duration.toMinutes()),
        selections = selections.categories.map { block -> PricingSelection(block.category.value, block.offerings.map { it.value }) },
    )

private fun LineItem.toResponse() =
    FinancialDocumentLine(
        id = id.toString(),
        description = description,
        subDescription = subDescription,
        quantity = quantity?.stripTrailingZeros()?.toPlainString(),
        unitPrice = price.decimal(),
        subtotal = subtotal.decimal(),
        taxAmount = taxAmount.decimal(),
        total = total.decimal(),
        currency = currency.currencyCode,
    )

private fun FinancialDocumentReconciliation.toResponse() =
    DocumentReconciliation(
        grossAllocated = grossAllocated.decimal(),
        netApplied = netApplied.decimal(),
        balance = balance.decimal(),
        currency = currency.currencyCode,
    )

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
