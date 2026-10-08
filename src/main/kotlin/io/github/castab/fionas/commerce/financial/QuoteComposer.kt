package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.ValidationViolation
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.offering.SourcedLine
import io.github.castab.fionas.commerce.offering.requireCurrentCatalogRevision
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The authoritative facts a quote composition starts from, all observed in the caller's
 * transaction: the reviewed canonical [estimate], the [effective] configuration whose evaluation
 * produced its lines, and the current catalog [snapshot].
 */
internal data class QuoteCompositionBasis(
    val inquiryId: InquiryId,
    val estimate: FinancialDocument.Estimate,
    val effective: FionasPricingInputs,
    val snapshot: OfferingsSnapshot,
)

/** Why one proposed line exists. Adjustments keep their request-local [QuoteAdjustmentKey]. */
sealed interface QuoteLineOrigin {
    data object EstimateLine : QuoteLineOrigin

    data class Generated(
        val source: FionasChargeSource,
    ) : QuoteLineOrigin

    data class Adjustment(
        val key: QuoteAdjustmentKey,
        val kind: QuoteAdjustmentKind,
        val reason: QuoteEditReason,
    ) : QuoteLineOrigin
}

/** A negotiated final amount, and the line it replaced as it was before the override. */
data class AppliedQuoteOverride(
    val original: LineItem,
    val reason: QuoteEditReason,
)

/**
 * One line of the proposed Quote. [persisted] lines keep an id the reviewed Estimate already
 * has; the others are new, with ids generated for this evaluation only.
 */
data class ComposedQuoteLine(
    val line: LineItem,
    val persisted: Boolean,
    val origin: QuoteLineOrigin,
    val override: AppliedQuoteOverride?,
)

/**
 * The deterministic result of composing a Quote: the approved service, the ordered proposed
 * lines with their provenance, the change order (absent when the lines charge exactly what the
 * reviewed Estimate does), the pure Quote candidate whose totals the domain derives, and the
 * deposit the approved terms resolve to against that exact Quote.
 *
 * [reviewToken] identifies everything a reviewer saw, independent of generated line ids, so
 * an approval can prove it publishes the result that was previewed.
 */
data class ComposedQuote(
    val inquiryId: InquiryId,
    val reviewed: FinancialDocument.Estimate,
    val basis: QuotePricingBasis,
    val catalogRevision: OfferingsRevision,
    val configuration: FionasPricingInputs,
    val selections: List<ServicePlanCategory>,
    val lines: List<ComposedQuoteLine>,
    val changes: ChangeOrder?,
    val quote: FinancialDocument.Quote,
    val terms: DepositTerms,
    val requiredDeposit: Money,
    val reviewToken: QuoteReviewToken,
) {
    /** Whether an intermediate Estimate successor precedes the Quote. */
    val financialChange: Boolean get() = changes != null
}

/** A preview's identity of its reviewed result; approval recomputes and compares it. */
@JvmInline
value class QuoteReviewToken(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "A quote review token is 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Stable codes of quote composition rejections. Clients match codes, never messages. */
object QuoteCompositionViolations {
    const val OVERRIDE_TARGET_NOT_FOUND = "OVERRIDE_TARGET_NOT_FOUND"
    const val OVERRIDE_TARGET_NOT_ALLOWED = "OVERRIDE_TARGET_NOT_ALLOWED"
    const val DUPLICATE_OVERRIDE_TARGET = "DUPLICATE_OVERRIDE_TARGET"
    const val OVERRIDE_UNCHANGED = "OVERRIDE_UNCHANGED"
    const val DUPLICATE_ADJUSTMENT_KEY = "DUPLICATE_ADJUSTMENT_KEY"
    const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
    const val SERVICE_SELECTIONS_UNCHANGED = "SERVICE_SELECTIONS_UNCHANGED"
    const val SERVICE_SELECTIONS_CHANGE_PRICING = "SERVICE_SELECTIONS_CHANGE_PRICING"
    const val NEGATIVE_DOCUMENT_TOTAL = "NEGATIVE_DOCUMENT_TOTAL"
    const val QUOTE_TOTAL_NOT_POSITIVE = "QUOTE_TOTAL_NOT_POSITIVE"
}

/**
 * Fiona's one quote composition core, shared by the write-free preview and the atomic initial
 * proposal issuance, so both derive identical lines, totals, deposit and review identity.
 *
 * Pure: it reads nothing and writes nothing. Pricing goes through [pricing]'s pure evaluation of
 * the observed snapshot; totals come from commerce-domain's own documents; the deposit from the
 * shared [DepositTerms.resolve]. Only [newLineId] varies between evaluations, and the review
 * token ignores the ids it generates.
 */
internal class QuoteComposer(
    private val pricing: FionasPricing,
    private val newLineId: () -> UUID = UUID::randomUUID,
) {
    fun compose(
        basis: QuoteCompositionBasis,
        composition: QuoteComposition,
        terms: DepositTerms,
    ): ComposedQuote {
        val estimate = basis.estimate
        val baseline = baseline(basis, composition.pricing)
        val violations = mutableListOf<String>()
        val overridden = applyOverrides(estimate, composition, baseline.lines, violations)
        val adjustments = adjustments(estimate, composition.adjustments, violations)
        if (violations.isNotEmpty()) throw compositionFailure(violations.distinct())

        var lines = overridden + adjustments
        val changed = !sameCharges(lines.map { it.line }, estimate.lineItems)
        if (!changed && !lines.all { it.persisted }) {
            // Complete equality, line by line in order, proves each generated line is the Estimate's
            // line at that position; keep the persisted line, and its id, instead of a new one.
            lines = lines.zip(estimate.lineItems) { composed, persisted -> composed.copy(line = persisted, persisted = true) }
        }
        val changes = if (changed) changeOrder(estimate, composition.pricing, lines) else null
        val successor = changes?.let { validating { estimate.changeOrder(it) } } ?: estimate
        if (successor.total.amount.signum() < 0) {
            throw compositionFailure(
                listOf(QuoteCompositionViolations.NEGATIVE_DOCUMENT_TOTAL),
                "A change order must not produce a negative financial-document total",
            )
        }
        val quote = successor.toQuote()
        if (quote.total.amount.signum() <= 0) {
            throw compositionFailure(
                listOf(QuoteCompositionViolations.QUOTE_TOTAL_NOT_POSITIVE),
                "An initial Quote requires a positive total, which its positive deposit cannot exceed",
            )
        }
        val required = resolveDeposit(quote, terms)
        val selections = presentation(basis.snapshot, baseline.configuration)
        val token = reviewToken(basis, composition.pricing.basis, baseline, selections, lines, changed, quote, terms, required)
        return ComposedQuote(
            basis.inquiryId,
            estimate,
            composition.pricing.basis,
            basis.snapshot.revision,
            baseline.configuration,
            selections,
            lines,
            changes,
            quote,
            terms,
            required,
            token,
        )
    }

    private class Baseline(
        val configuration: FionasPricingInputs,
        val lines: List<ComposedQuoteLine>,
    )

    private fun baseline(
        basis: QuoteCompositionBasis,
        mode: QuotePricing,
    ): Baseline {
        val snapshot = basis.snapshot
        val estimateLines = basis.estimate.lineItems.map { ComposedQuoteLine(it, true, QuoteLineOrigin.EstimateLine, null) }
        val effective = basis.effective.copy(catalogRevision = snapshot.revision)
        return when (mode) {
            QuotePricing.KeepEstimate -> {
                // Prices nothing that is kept: the evaluation only proves the configuration the plan
                // promises is still selectable in the current catalog, whose names the plan pins.
                pricing.price(snapshot, effective)
                Baseline(effective, estimateLines)
            }
            is QuotePricing.ReviseServiceSelections -> {
                requireCurrentCatalogRevision(mode.catalogRevision, snapshot)
                val revised = effective.copy(selections = mode.selections)
                if (revised.selections == effective.selections) {
                    throw compositionFailure(
                        listOf(QuoteCompositionViolations.SERVICE_SELECTIONS_UNCHANGED),
                        "The revised selections are the Estimate's own; keep the Estimate instead",
                    )
                }
                val before = pricing.price(snapshot, effective).lineItems
                val after = pricing.price(snapshot, revised).lineItems
                if (!sameCharges(before, after)) {
                    throw compositionFailure(
                        listOf(QuoteCompositionViolations.SERVICE_SELECTIONS_CHANGE_PRICING),
                        "The revised selections change the charges; reprice the configuration and review the result",
                    )
                }
                Baseline(revised, estimateLines)
            }
            is QuotePricing.RepriceConfiguration -> {
                requireCurrentCatalogRevision(mode.inputs.catalogRevision, snapshot)
                val evaluation = pricing.priceWithSources(snapshot, mode.inputs).lines
                Baseline(mode.inputs, evaluation.map { generated(it) })
            }
        }
    }

    private fun generated(line: SourcedLine) =
        ComposedQuoteLine(line.line.copy(id = newLineId()), false, QuoteLineOrigin.Generated(line.source), null)

    private fun applyOverrides(
        estimate: FinancialDocument.Estimate,
        composition: QuoteComposition,
        baseline: List<ComposedQuoteLine>,
        violations: MutableList<String>,
    ): List<ComposedQuoteLine> {
        val repricing = composition.pricing is QuotePricing.RepriceConfiguration
        val targets = composition.overrides.map { it.target }
        if (targets.toSet().size != targets.size) violations += QuoteCompositionViolations.DUPLICATE_OVERRIDE_TARGET
        val byPosition = mutableMapOf<Int, QuoteLineOverride>()
        composition.overrides.forEach { override ->
            if (override.finalAmount.currency != estimate.currency) violations += QuoteCompositionViolations.CURRENCY_MISMATCH
            val position =
                when (val target = override.target) {
                    is QuoteOverrideTarget.ExistingLine -> {
                        if (repricing) {
                            violations += QuoteCompositionViolations.OVERRIDE_TARGET_NOT_ALLOWED
                            return@forEach
                        }
                        baseline.indexOfFirst { it.line.id == target.lineItemId }
                    }
                    is QuoteOverrideTarget.GeneratedCharge -> {
                        if (!repricing) {
                            violations += QuoteCompositionViolations.OVERRIDE_TARGET_NOT_ALLOWED
                            return@forEach
                        }
                        baseline.indexOfFirst { (it.origin as? QuoteLineOrigin.Generated)?.source == target.source }
                    }
                }
            if (position < 0) {
                violations += QuoteCompositionViolations.OVERRIDE_TARGET_NOT_FOUND
            } else {
                val line = baseline[position].line
                if (line.currency == override.finalAmount.currency && line.total.amount.compareTo(override.finalAmount.amount) == 0) {
                    violations += QuoteCompositionViolations.OVERRIDE_UNCHANGED
                }
                byPosition.putIfAbsent(position, override)
            }
        }
        if (violations.isNotEmpty()) return baseline
        return baseline.mapIndexed { position, composed ->
            val override = byPosition[position] ?: return@mapIndexed composed
            val original = composed.line
            // Fiona charges no tax yet; a final amount on a taxed line needs a tax decision first.
            check(original.taxAmount.amount.signum() == 0) { "An overridden line has tax, which Fiona does not charge yet" }
            // A negotiated total is a flat amount: never a rounded, misleading unit price. The
            // guest count and duration stay in the service plan. A per-unit line's secondary text
            // describes unit pricing, so only a flat line keeps it.
            val replacement =
                original.copy(
                    subDescription = original.subDescription.takeIf { original.quantity == null },
                    quantity = null,
                    price = override.finalAmount,
                )
            composed.copy(line = replacement, override = AppliedQuoteOverride(original, override.reason))
        }
    }

    private fun adjustments(
        estimate: FinancialDocument.Estimate,
        adjustments: List<QuoteAdjustment>,
        violations: MutableList<String>,
    ): List<ComposedQuoteLine> {
        val keys = adjustments.map { it.key }
        val own = mutableListOf<String>()
        if (keys.toSet().size != keys.size) own += QuoteCompositionViolations.DUPLICATE_ADJUSTMENT_KEY
        if (adjustments.any { it.amount.currency != estimate.currency }) own += QuoteCompositionViolations.CURRENCY_MISMATCH
        violations += own
        if (own.isNotEmpty()) return emptyList()
        return adjustments.map { adjustment ->
            ComposedQuoteLine(
                LineItem(
                    newLineId(),
                    adjustment.description.value,
                    adjustment.subDescription?.value,
                    null,
                    adjustment.kind.signed(adjustment.amount),
                    Money.zero(estimate.currency),
                ),
                false,
                QuoteLineOrigin.Adjustment(adjustment.key, adjustment.kind, adjustment.reason),
                null,
            )
        }
    }

    /**
     * Without repricing, the Estimate's line identities survive: each override replaces its line
     * in place under the same id, and each adjustment is added. Repricing has no persisted line
     * identity to target (an Estimate's lines carry no source), so it explicitly replaces the
     * Estimate's whole line set with the newly generated one; it never matches by description.
     */
    private fun changeOrder(
        estimate: FinancialDocument.Estimate,
        mode: QuotePricing,
        lines: List<ComposedQuoteLine>,
    ): ChangeOrder =
        when (mode) {
            QuotePricing.KeepEstimate, is QuotePricing.ReviseServiceSelections ->
                ChangeOrder(
                    lines.mapNotNull { composed ->
                        when {
                            composed.override != null -> ChangeOrder.Change.ReplaceLineItem(composed.line.id, composed.line)
                            composed.persisted -> null
                            else -> ChangeOrder.Change.AddLineItem(composed.line)
                        }
                    },
                )
            is QuotePricing.RepriceConfiguration ->
                ChangeOrder(
                    estimate.lineItems.map { ChangeOrder.Change.RemoveLineItem(it.id) } +
                        lines.map { ChangeOrder.Change.AddLineItem(it.line) },
                )
        }

    private fun resolveDeposit(
        quote: FinancialDocument.Quote,
        terms: DepositTerms,
    ): Money {
        if (terms is DepositTerms.Fixed) paymentMoney(terms.amount.amount, terms.amount.currency)
        return validating { terms.resolve(quote) }
    }

    /** The configuration's selections, with the snapshot's current names staff review and approve. */
    private fun presentation(
        snapshot: OfferingsSnapshot,
        configuration: FionasPricingInputs,
    ): List<ServicePlanCategory> =
        configuration.selections.categories.map { block ->
            // Evaluating the configuration against this snapshot already proved every key exists.
            val category = checkNotNull(snapshot.category(block.category)) { "Category ${block.category.value} is not in the catalog" }
            ServicePlanCategory(
                block.category,
                category.displayName,
                block.offerings.map { key ->
                    val offering = checkNotNull(snapshot.offering(key)) { "Offering ${key.value} is not in the catalog" }
                    ServicePlanOffering(key, offering.displayName, offering.description)
                },
            )
        }

    private fun reviewToken(
        basis: QuoteCompositionBasis,
        pricingBasis: QuotePricingBasis,
        baseline: Baseline,
        selections: List<ServicePlanCategory>,
        lines: List<ComposedQuoteLine>,
        changed: Boolean,
        quote: FinancialDocument.Quote,
        terms: DepositTerms,
        required: Money,
    ): QuoteReviewToken {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.text(basis.inquiryId.value.toString())
            output.text(basis.estimate.id.toString())
            output.writeInt(basis.estimate.version.number)
            output.text(pricingBasis.name)
            output.writeInt(basis.snapshot.revision.number)
            val context = baseline.configuration.context
            output.writeInt(context.guestCount)
            output.writeBoolean(context.guestCountIsMinimum)
            output.writeLong(context.duration.seconds)
            output.writeInt(context.duration.nano)
            output.writeInt(selections.size)
            selections.forEach { category ->
                output.text(category.category.value)
                output.text(category.displayName)
                output.writeInt(category.offerings.size)
                category.offerings.forEach { offering ->
                    output.text(offering.offering.value)
                    output.text(offering.displayName)
                    output.optional(offering.description)
                }
            }
            output.writeBoolean(changed)
            output.writeInt(lines.size)
            lines.forEach { composed ->
                // A persisted id is a reviewed fact; a generated id is not, so only its presence counts.
                output.optional(
                    composed.line.id
                        .toString()
                        .takeIf { composed.persisted },
                )
                output.line(composed.line)
                when (val origin = composed.origin) {
                    QuoteLineOrigin.EstimateLine -> output.text("ESTIMATE_LINE")
                    is QuoteLineOrigin.Generated -> output.text("GENERATED:" + origin.source.tokenText())
                    is QuoteLineOrigin.Adjustment -> {
                        output.text("ADJUSTMENT:" + origin.kind.name)
                        output.text(origin.key.value)
                        output.text(origin.reason.value)
                    }
                }
                output.writeBoolean(composed.override != null)
                composed.override?.let {
                    output.line(it.original)
                    output.text(it.reason.value)
                }
            }
            output.text(quote.total.amount.decimalText())
            output.text(quote.currency.currencyCode)
            when (terms) {
                is DepositTerms.Fixed -> {
                    output.text("FIXED")
                    output.text(terms.amount.amount.decimalText())
                    output.text(terms.amount.currency.currencyCode)
                }
                is DepositTerms.Percentage -> {
                    output.text("PERCENTAGE")
                    output.text(terms.percentage.decimalText())
                }
            }
            output.text(required.amount.decimalText())
            output.text(required.currency.currencyCode)
        }
        return QuoteReviewToken(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())))
    }
}

private fun compositionFailure(
    codes: List<String>,
    message: String = "The quote composition is invalid: " + codes.joinToString(),
) = CommerceFailure.ValidationFailed(message, codes.map(::ValidationViolation))

private fun FionasChargeSource.tokenText(): String =
    when (this) {
        FionasChargeSource.BaseService -> "BASE_SERVICE"
        FionasChargeSource.IceCreamService -> "ICE_CREAM_SERVICE"
        FionasChargeSource.ExtraToppings -> "EXTRA_TOPPINGS"
        is FionasChargeSource.SelectedOffering -> "SELECTED_OFFERING:${category.value.length}:${category.value}:${offering.value}"
    }

private fun BigDecimal.decimalText(): String = stripTrailingZeros().toPlainString()

private fun DataOutputStream.line(line: LineItem) {
    text(line.description)
    optional(line.subDescription)
    optional(line.quantity?.decimalText())
    text(line.price.amount.decimalText())
    text(line.taxAmount.amount.decimalText())
    text(line.currency.currencyCode)
}

private fun DataOutputStream.optional(value: String?) {
    writeBoolean(value != null)
    value?.let { text(it) }
}

private fun DataOutputStream.text(value: String) {
    writeInt(value.length)
    writeChars(value)
}
