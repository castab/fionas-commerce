package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import java.time.Instant
import java.util.UUID

/**
 * What Fiona's promises to serve with one exact canonical Quote snapshot, and why each of its
 * financial lines exists: Fiona business facts the ledger cannot reproduce.
 *
 * The plan holds no money. Every amount stays on the commerce-runtime snapshot; each
 * [ServicePlanLine] names its ledger line by id, so readers join the two. Display names are the
 * catalog's at approval, which staff reviewed, never presented as the customer's historical
 * labels: the Offerings catalog keeps only current contents.
 *
 * Immutable and bound to [quote]; [reviewedEstimate] is the Estimate snapshot staff composed it
 * from. Every manual edit in the plan was approved by [principalId] at [approvedAt].
 */
data class InquiryServicePlan(
    val inquiryId: InquiryId,
    val quote: FinancialDocumentReference,
    val reviewedEstimate: FinancialDocumentReference,
    val basis: QuotePricingBasis,
    val catalogRevision: OfferingsRevision,
    val context: FionasOfferingsContext,
    val selections: List<ServicePlanCategory>,
    val lines: List<ServicePlanLine>,
    val approvedAt: Instant,
    val principalId: PrincipalId,
) {
    init {
        require(quote.id == reviewedEstimate.id && reviewedEstimate.version < quote.version) {
            "A service plan's Quote must follow its reviewed Estimate in the same lineage"
        }
        require(quote.version.number >= 2) { "A service plan belongs to an issued Quote" }
        require(lines.isNotEmpty()) { "A service plan describes at least one line" }
        require(lines.map { it.lineItemId }.toSet().size == lines.size) { "A service plan describes each line once" }
        require(selections.map { it.category }.toSet().size == selections.size) { "A service plan lists each category once" }
        val generated = lines.mapNotNull { (it.origin as? ServicePlanLineOrigin.Generated)?.source }
        require(generated.toSet().size == generated.size) { "A service plan names each generated charge source once" }
    }

    /** Whether this plan describes exactly the lines of [quote], in order. */
    fun describes(quote: FinancialDocument): Boolean =
        quote is FinancialDocument.Quote &&
            quote.reference == this.quote &&
            quote.lineItems.map { it.id } == lines.map { it.lineItemId }
}

/** The offerings approved from one catalog category, in presentation order. */
data class ServicePlanCategory(
    val category: OfferingCategoryKey,
    val displayName: String,
    val offerings: List<ServicePlanOffering>,
) {
    init {
        require(displayName.isNotBlank()) { "An approved category has a display name" }
        require(offerings.map { it.offering }.toSet().size == offerings.size) { "An approved category lists each offering once" }
    }
}

/** One approved offering, including unpriced ones, with the catalog text staff reviewed. */
data class ServicePlanOffering(
    val offering: OfferingKey,
    val displayName: String,
    val description: String?,
) {
    init {
        require(displayName.isNotBlank()) { "An approved offering has a display name" }
    }
}

/** Why one ledger line of the Quote exists. [overrideReason] marks a negotiated final amount. */
data class ServicePlanLine(
    val lineItemId: UUID,
    val origin: ServicePlanLineOrigin,
    val overrideReason: QuoteEditReason?,
) {
    init {
        require(origin !is ServicePlanLineOrigin.Adjustment || overrideReason == null) { "An adjustment line is not an override" }
    }
}

sealed interface ServicePlanLineOrigin {
    /**
     * Carried from the reviewed Estimate under the same id. When overridden, the original line
     * is that id in the reviewed Estimate snapshot.
     */
    data object EstimateLine : ServicePlanLineOrigin

    /** Generated for [source] by pricing the plan's configuration from the current catalog. */
    data class Generated(
        val source: FionasChargeSource,
    ) : ServicePlanLineOrigin

    /** A staff-entered charge, discount or credit. */
    data class Adjustment(
        val kind: QuoteAdjustmentKind,
        val reason: QuoteEditReason,
    ) : ServicePlanLineOrigin
}

/** The plan approving [composed] as the persisted [quote], by [principalId] at [approvedAt]. */
internal fun ComposedQuote.servicePlan(
    quote: FinancialDocument.Quote,
    approvedAt: Instant,
    principalId: PrincipalId,
): InquiryServicePlan =
    InquiryServicePlan(
        inquiryId = inquiryId,
        quote = quote.reference,
        reviewedEstimate = reviewed.reference,
        basis = basis,
        catalogRevision = catalogRevision,
        context = configuration.context,
        selections = selections,
        lines =
            lines.map { composed ->
                ServicePlanLine(
                    composed.line.id,
                    when (val origin = composed.origin) {
                        QuoteLineOrigin.EstimateLine -> ServicePlanLineOrigin.EstimateLine
                        is QuoteLineOrigin.Generated -> ServicePlanLineOrigin.Generated(origin.source)
                        is QuoteLineOrigin.Adjustment -> ServicePlanLineOrigin.Adjustment(origin.kind, origin.reason)
                    },
                    composed.override?.reason,
                )
            },
        approvedAt = approvedAt,
        principalId = principalId,
    ).also { check(it.describes(quote)) { "The service plan must describe exactly the persisted Quote's lines" } }

/**
 * Immutable approved service plans in the caller's transaction. Never begins, commits or rolls
 * back. A plan is written once, with the Quote it describes, and never changed.
 */
interface InquiryServicePlanRepository {
    /** Records [plan]; its Quote must exist and belong to the inquiry's canonical lineage. */
    fun insert(
        transaction: Transaction,
        plan: InquiryServicePlan,
    )

    /** The plan of exactly [quote], or `null` when that snapshot has none. */
    fun find(
        transaction: Transaction,
        quote: FinancialDocumentReference,
    ): InquiryServicePlan?
}
