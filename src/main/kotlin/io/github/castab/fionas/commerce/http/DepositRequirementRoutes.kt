@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.fionas.commerce.financial.InquiryFinancialLineage
import io.github.castab.fionas.commerce.financial.QueryFinancialLineages
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.financial.WithdrawDepositRequirement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

@Serializable
@JsonClassDiscriminator("type")
sealed interface DepositTermsRequest {
    @Serializable
    @SerialName("FIXED")
    @ApiClosedObject
    data class Fixed(
        @ApiProperty(description = "Positive exact decimal money, with at most the currency's minor-unit digits.") val amount: String,
        @ApiProperty(description = "Explicit ISO 4217 currency; must match the approval document.") val currency: String,
    ) : DepositTermsRequest

    @Serializable
    @SerialName("PERCENTAGE")
    @ApiClosedObject
    data class Percentage(
        @ApiProperty(description = "Exact decimal percentage greater than 0 and at most 100; never rounded itself.") val percentage: String,
    ) : DepositTermsRequest
}

/** Strict union shapes even though ordinary CommerceJson requests accept additive fields. */
private object StrictDepositTerms : KSerializer<DepositTermsRequest> {
    private val delegate = DepositTermsRequest.serializer()
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): DepositTermsRequest {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Deposit terms require JSON")
        val value = input.decodeJsonElement().jsonObject
        val fields =
            when (value["type"]?.jsonPrimitive?.content) {
                "FIXED" -> setOf("type", "amount", "currency")
                "PERCENTAGE" -> setOf("type", "percentage")
                else -> throw SerializationException("Unknown deposit terms type")
            }
        if (value.keys != fields) throw SerializationException("Deposit terms must match their type")
        return input.json.decodeFromJsonElement(delegate, value)
    }

    override fun serialize(
        encoder: Encoder,
        value: DepositTermsRequest,
    ) = delegate.serialize(encoder, value)
}

@Serializable
data class SetDepositRequirementRequest(
    @ApiProperty(description = "Exact latest document version approved; a stale version conflicts.") val expectedDocumentVersion: Int,
    @ApiProperty(
        description = "Null or absent expects no requirement history; otherwise the exact latest revision. Never don't-care.",
        nullable = true,
    )
    val expectedRequirementRevision: Int?,
    @Serializable(with = StrictDepositTerms::class)
    val terms: DepositTermsRequest,
)

@Serializable
data class WithdrawDepositRequirementRequest(
    @ApiProperty(description = "Exact latest active requirement revision. Withdrawal needs no document version.")
    val expectedRequirementRevision: Int,
)

@Serializable
data class DepositMoneyResponse(
    @ApiProperty(description = "Exact decimal amount.") val amount: String,
    @ApiProperty(description = "ISO 4217 currency.") val currency: String,
)

@Serializable
@JsonClassDiscriminator("state")
sealed interface CurrentDepositRequirementResponse {
    @Serializable
    @SerialName("NONE")
    data class None(
        @ApiProperty(format = "uuid") val documentId: String,
    ) : CurrentDepositRequirementResponse

    @Serializable
    @SerialName("ACTIVE")
    data class Active(
        @ApiProperty(format = "uuid") val documentId: String,
        val revision: Int,
        val previousRevision: Int? = null,
        @ApiProperty(description = "Persisted revision creation time.", format = "date-time") val createdAt: String,
        val approvalDocumentVersion: Int,
        val terms: DepositTermsRequest,
        @ApiProperty(description = "Frozen amount resolved at approval, unchanged by later document versions.")
        val requiredAmount: DepositMoneyResponse,
        @ApiProperty(description = "Current runtime netApplied >= requiredAmount; refunds can undo this. No booking consequence.")
        val satisfied: Boolean,
    ) : CurrentDepositRequirementResponse

    @Serializable
    @SerialName("WITHDRAWN")
    data class Withdrawn(
        @ApiProperty(format = "uuid") val documentId: String,
        val revision: Int,
        val previousRevision: Int,
        @ApiProperty(format = "date-time") val createdAt: String,
    ) : CurrentDepositRequirementResponse
}

@Serializable
@JsonClassDiscriminator("state")
sealed interface HistoricalDepositRequirementResponse {
    @Serializable
    @SerialName("ACTIVE")
    data class Active(
        @ApiProperty(format = "uuid") val documentId: String,
        val revision: Int,
        val previousRevision: Int? = null,
        @ApiProperty(format = "date-time") val createdAt: String,
        val approvalDocumentVersion: Int,
        val terms: DepositTermsRequest,
        val requiredAmount: DepositMoneyResponse,
    ) : HistoricalDepositRequirementResponse

    @Serializable
    @SerialName("WITHDRAWN")
    data class Withdrawn(
        @ApiProperty(format = "uuid") val documentId: String,
        val revision: Int,
        val previousRevision: Int,
        @ApiProperty(format = "date-time") val createdAt: String,
    ) : HistoricalDepositRequirementResponse
}

@Serializable
data class DepositRequirementHistoryResponse(
    val revisions: List<HistoricalDepositRequirementResponse>,
)

@Serializable
data class QueryFinancialLineagesRequest(
    @ApiProperty(description = "Explicit Fiona lineage UUIDs in desired order. Empty is valid; duplicates fail validation.")
    val documentIds: List<String>,
)

@Serializable
data class FinancialLineageReconciliationResponse(
    val grossAllocated: String,
    val refundAllocations: String,
    val netApplied: String,
    val balance: String,
    val currency: String,
)

/** Direct projection of runtime FinancialLineageActivity, never a workflow activity policy. */
@Serializable
data class FinancialLineageActivityResponse(
    @ApiProperty(format = "date-time") val latestDocumentVersionAt: String,
    @ApiProperty(format = "date-time") val latestDepositRequirementAt: String? = null,
    @ApiProperty(format = "date-time") val latestPaymentAllocationAt: String? = null,
    @ApiProperty(format = "date-time") val latestRefundAllocationAt: String? = null,
    @ApiProperty(description = "Maximum of these four event times; excludes receipt and standalone refund times.", format = "date-time")
    val latestFinancialActivityAt: String,
)

@Serializable
data class FinancialLineageResponse(
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val documentId: String,
    val version: Int,
    @ApiProperty(description = "Latest stage: ESTIMATE, QUOTE, or INVOICE.") val stage: String,
    @ApiProperty(description = "Latest exact decimal total.") val total: String,
    val currency: String,
    val reconciliation: FinancialLineageReconciliationResponse,
    val depositRequirement: CurrentDepositRequirementResponse,
    val activity: FinancialLineageActivityResponse,
)

@Serializable
data class FinancialLineagesResponse(
    @ApiProperty(description = "Every requested lineage, in request order; no pricing metadata. No id is silently omitted.")
    val lineages: List<FinancialLineageResponse>,
)

private val depositPath = Path.of("documentId", "Fiona-owned financial lineage UUID", mapOf("schema" to mapOf("format" to "uuid")))
private val setDepositBody = jsonBody(SetDepositRequirementRequest.serializer())
private val withdrawDepositBody = jsonBody(WithdrawDepositRequirementRequest.serializer())
private val currentDepositBody = jsonBody(CurrentDepositRequirementResponse.serializer())
private val depositHistoryBody = jsonBody(DepositRequirementHistoryResponse.serializer())
private val queryLineagesBody = jsonBody(QueryFinancialLineagesRequest.serializer())
private val lineagesBody = jsonBody(FinancialLineagesResponse.serializer())
private val depositTag =
    Tag("Deposit requirements", "Immutable approved financial terms, with current derived satisfaction and no workflow consequences.")
private const val DEPOSIT_EXAMPLE_ID = "5f0c6a7e-8c1d-4f63-9b2a-0d8e7f6a5b4c"
private val exampleTerms = DepositTermsRequest.Fixed("100.00", "USD")
private val exampleActive =
    CurrentDepositRequirementResponse.Active(
        DEPOSIT_EXAMPLE_ID,
        1,
        createdAt = "2026-10-03T17:00:00Z",
        approvalDocumentVersion = 2,
        terms = exampleTerms,
        requiredAmount = DepositMoneyResponse("100.00", "USD"),
        satisfied = false,
    )
private val exampleWithdrawn = CurrentDepositRequirementResponse.Withdrawn(DEPOSIT_EXAMPLE_ID, 2, 1, "2026-10-03T18:00:00Z")

private fun RouteMetaDsl.depositErrors(
    permission: PermissionKey,
    unsafe: Boolean,
    mutation: Boolean = false,
    validation: Boolean = false,
) {
    principalAccess(permission, UNTRUSTED_ORIGIN.takeIf { unsafe })
    returningError(ErrorCategory.MALFORMED_REQUEST, "unreadable UUID, JSON body, or discriminator.", "Malformed request")
    returningError(
        ErrorCategory.NOT_FOUND,
        "any requested lineage is missing or is not Fiona-owned; withdrawal also requires history.",
        "Financial document was not found",
    )
    if (mutation) {
        returningError(
            ErrorCategory.CONFLICT,
            "stale document/requirement token, already-withdrawn illegal transition, or runtime lineage contention; reload and retry the whole request.",
            "Stale expected revision",
        )
    }
    if (validation) {
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "invalid terms, revisions, duplicate ids, or an Estimate activation.",
            "Invalid financial request",
        )
    }
    returningError(ErrorCategory.INTERNAL_FAILURE, "unexpected failure; no internal detail is exposed.", INTERNAL_FAILURE)
}

fun getDepositRequirementRoute(
    get: (UUID) -> FinancialLineageView,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / depositPath / "deposit-requirement" meta {
        operationId = "getFinancialDocumentDepositRequirement"
        summary = "Read current deposit requirement"
        description =
            "Requires commerce.financial-document.read. NONE means no history; ACTIVE includes current financial satisfaction; WITHDRAWN contains no terms. One REPEATABLE_READ snapshot, no locks or workflow effects."
        tags += depositTag
        returning(Status.OK, currentDepositBody to exampleActive)
        depositErrors(CommercePermissions.FinancialDocumentRead, false)
    } bindContract Method.GET to { id: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            Response(Status.OK).with(currentDepositBody of get(depositUuid(id)).currentDepositResponse())
        }
    }

fun getDepositRequirementHistoryRoute(
    get: (UUID) -> List<DepositRequirementVersion>,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / depositPath / "deposit-requirement" / "history" meta {
        operationId = "getFinancialDocumentDepositRequirementHistory"
        summary = "Read deposit revision history"
        description =
            "Requires commerce.financial-document.read. Complete immutable ACTIVE/WITHDRAWN revisions oldest first, with persisted timestamps and frozen amounts. No historical NONE or satisfaction. One REPEATABLE_READ snapshot."
        tags += depositTag
        returning(Status.OK, depositHistoryBody to DepositRequirementHistoryResponse(emptyList()))
        depositErrors(CommercePermissions.FinancialDocumentRead, false)
    } bindContract Method.GET to { id: String, _: String, _: String ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { _: Request ->
            Response(Status.OK).with(
                depositHistoryBody of DepositRequirementHistoryResponse(get(depositUuid(id)).map { it.historyResponse() }),
            )
        }
    }

fun setDepositRequirementRoute(
    set: (SetDepositRequirement.Command) -> FinancialLineageView,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / depositPath / "deposit-requirement" meta {
        operationId = "setFinancialDocumentDepositRequirement"
        summary = "Approve, replace, or reactivate deposit terms"
        description =
            "Requires commerce.deposit-requirement.manage. Only the exact latest Quote/Invoice is eligible. Null expectedRequirementRevision expects no history. Explicit FIXED currency or exact PERCENTAGE terms resolve once; percentages round HALF_UP to minor units and amounts are frozen. No workflow effects."
        tags += depositTag
        receiving(setDepositBody to SetDepositRequirementRequest(2, null, exampleTerms))
        returning(Status.OK, currentDepositBody to exampleActive)
        depositErrors(CommercePermissions.DepositRequirementManage, true, mutation = true, validation = true)
    } bindContract Method.PUT to { id: String, _: String ->
        access.requirePermission(CommercePermissions.DepositRequirementManage).then { request: Request ->
            val documentId = depositUuid(id)
            val body = setDepositBody(request)
            val command =
                validating {
                    SetDepositRequirement.Command(
                        documentId,
                        Version.of(body.expectedDocumentVersion),
                        body.expectedRequirementRevision?.let(DepositRequirementRevision::of),
                        body.terms.domain(),
                    )
                }
            Response(Status.OK).with(currentDepositBody of set(command).currentDepositResponse())
        }
    }

fun withdrawDepositRequirementRoute(
    withdraw: (WithdrawDepositRequirement.Command) -> DepositRequirementVersion,
    access: AccessControl,
): ContractRoute =
    "/financial-documents" / depositPath / "deposit-requirement" meta {
        operationId = "withdrawFinancialDocumentDepositRequirement"
        summary = "Withdraw approved deposit terms"
        description =
            "Requires commerce.deposit-requirement.manage. Appends WITHDRAWN from an exact active requirement revision, even after document stage/version changes. No document version or stage eligibility check; history is retained."
        tags += depositTag
        receiving(withdrawDepositBody to WithdrawDepositRequirementRequest(1))
        returning(Status.OK, currentDepositBody to exampleWithdrawn)
        depositErrors(CommercePermissions.DepositRequirementManage, true, mutation = true, validation = true)
    } bindContract (Method.DELETE) to { id: String, _: String ->
        access.requirePermission(CommercePermissions.DepositRequirementManage).then { request: Request ->
            val documentId = depositUuid(id)
            val body = withdrawDepositBody(request)
            val command =
                validating {
                    WithdrawDepositRequirement.Command(
                        documentId,
                        DepositRequirementRevision.of(body.expectedRequirementRevision),
                    )
                }
            Response(Status.OK).with(currentDepositBody of withdraw(command).withdrawnResponse())
        }
    }

fun queryFinancialLineagesRoute(
    query: (QueryFinancialLineages.Command) -> List<InquiryFinancialLineage>,
    access: AccessControl,
): ContractRoute =
    "/financial-documents/query" meta {
        operationId = "queryFinancialDocumentLineages"
        summary = "Query current financial lineages in bulk"
        description =
            "Requires commerce.financial-document.read. Explicit UUIDs, preserved request order, empty input allowed, duplicates rejected, any unowned/missing id fails the whole request. One unlocked REPEATABLE_READ snapshot and set-based ownership/runtime reads. Objective activity excludes payment receipt and standalone refund times; no workflow interpretation. Cookie-authenticated POST requires a trusted Origin."
        tags += depositTag
        receiving(queryLineagesBody to QueryFinancialLineagesRequest(listOf(DEPOSIT_EXAMPLE_ID)))
        returning(Status.OK, lineagesBody to FinancialLineagesResponse(emptyList()))
        depositErrors(CommercePermissions.FinancialDocumentRead, true, validation = true)
    } bindContract Method.POST to { request ->
        access.requirePermission(CommercePermissions.FinancialDocumentRead).then { authorized: Request ->
            val body = queryLineagesBody(authorized)
            val ids =
                body.documentIds.map { value ->
                    try {
                        UUID.fromString(value)
                    } catch (e: IllegalArgumentException) {
                        throw LensFailure(Invalid(queryLineagesBody.metas.single()), cause = e)
                    }
                }
            Response(Status.OK).with(
                lineagesBody of FinancialLineagesResponse(query(QueryFinancialLineages.Command(ids)).map { it.toResponse() }),
            )
        }(request)
    }

private fun depositUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(depositPath.meta), cause = e)
    }

private fun depositDecimal(value: String): BigDecimal {
    require(Regex("""-?\d+(\.\d+)?""").matches(value)) { "Deposit values must be exact decimal strings" }
    return BigDecimal(value)
}

private fun DepositTermsRequest.domain(): DepositTerms =
    when (this) {
        is DepositTermsRequest.Fixed -> DepositTerms.Fixed(Money(depositDecimal(amount), Currency.getInstance(currency)))
        is DepositTermsRequest.Percentage -> DepositTerms.Percentage(depositDecimal(percentage))
    }

private fun DepositTerms.toResponse(): DepositTermsRequest =
    when (this) {
        is DepositTerms.Fixed -> DepositTermsRequest.Fixed(amount.amount.toPlainString(), amount.currency.currencyCode)
        is DepositTerms.Percentage -> DepositTermsRequest.Percentage(percentage.toPlainString())
    }

private fun Money.toDepositResponse() = DepositMoneyResponse(amount.toPlainString(), currency.currencyCode)

internal fun FinancialLineageView.currentDepositResponse(): CurrentDepositRequirementResponse {
    val persisted = depositRequirement ?: return CurrentDepositRequirementResponse.None(latestVersion.document.id.toString())
    return when (val requirement = persisted.requirement) {
        is DepositRequirement.Active ->
            CurrentDepositRequirementResponse.Active(
                requirement.documentId.toString(),
                requirement.revision.number,
                requirement.previousRevision?.number,
                persisted.createdAt.toString(),
                requirement.approvalReference.version.number,
                requirement.terms.toResponse(),
                requirement.requiredAmount.toDepositResponse(),
                checkNotNull(depositSatisfied),
            )
        is DepositRequirement.Withdrawn -> persisted.withdrawnResponse()
    }
}

private fun DepositRequirementVersion.withdrawnResponse(): CurrentDepositRequirementResponse.Withdrawn {
    val withdrawn = requirement as DepositRequirement.Withdrawn
    return CurrentDepositRequirementResponse.Withdrawn(
        withdrawn.documentId.toString(),
        withdrawn.revision.number,
        checkNotNull(withdrawn.previousRevision).number,
        createdAt.toString(),
    )
}

private fun DepositRequirementVersion.historyResponse(): HistoricalDepositRequirementResponse =
    when (val entry = requirement) {
        is DepositRequirement.Active ->
            HistoricalDepositRequirementResponse.Active(
                entry.documentId.toString(),
                entry.revision.number,
                entry.previousRevision?.number,
                createdAt.toString(),
                entry.approvalReference.version.number,
                entry.terms.toResponse(),
                entry.requiredAmount.toDepositResponse(),
            )
        is DepositRequirement.Withdrawn ->
            HistoricalDepositRequirementResponse.Withdrawn(
                entry.documentId.toString(),
                entry.revision.number,
                checkNotNull(entry.previousRevision).number,
                createdAt.toString(),
            )
    }

private fun InquiryFinancialLineage.toResponse(): FinancialLineageResponse {
    val document = financial.latestVersion.document
    val settlement = financial.reconciliation
    val activity = financial.activity
    return FinancialLineageResponse(
        inquiryId.value.toString(),
        document.id.toString(),
        document.version.number,
        when (document) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        },
        document.total.decimal(),
        document.currency.currencyCode,
        FinancialLineageReconciliationResponse(
            settlement.grossAllocated.decimal(),
            settlement.refundAllocations.decimal(),
            settlement.netApplied.decimal(),
            settlement.balance.decimal(),
            settlement.currency.currencyCode,
        ),
        financial.currentDepositResponse(),
        FinancialLineageActivityResponse(
            activity.latestDocumentVersionAt.toString(),
            activity.latestDepositRequirementAt?.toString(),
            activity.latestPaymentAllocationAt?.toString(),
            activity.latestRefundAllocationAt?.toString(),
            activity.latestFinancialActivityAt.toString(),
        ),
    )
}
