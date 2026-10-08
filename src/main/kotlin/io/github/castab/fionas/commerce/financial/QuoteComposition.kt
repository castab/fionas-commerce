package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.util.UUID

/**
 * Staff commercial intent for the initial canonical Quote: how its baseline lines are priced,
 * which generated charges get a negotiated final amount, and which separate charges, discounts
 * and credits are added. Never a client-supplied financial document, price calculation or total:
 * the server derives every line and amount from this intent and the authoritative Estimate.
 */
data class QuoteComposition(
    val pricing: QuotePricing,
    val overrides: List<QuoteLineOverride> = emptyList(),
    val adjustments: List<QuoteAdjustment> = emptyList(),
)

/** Where the Quote's baseline lines come from. Each mode means one thing; none reprices silently. */
sealed interface QuotePricing {
    val basis: QuotePricingBasis

    /**
     * The reviewed Estimate's persisted lines exactly as recorded, with their ids, and its
     * effective service configuration unchanged. No catalog price is consulted.
     */
    data object KeepEstimate : QuotePricing {
        override val basis = QuotePricingBasis.KEEP_ESTIMATE
    }

    /**
     * The Estimate's persisted lines, with a changed selection of offerings that must be
     * financially neutral: the current catalog prices the effective and the revised selections
     * identically. Guest count and duration are unchanged.
     */
    data class ReviseServiceSelections(
        val catalogRevision: OfferingsRevision,
        val selections: OfferingSelections,
    ) : QuotePricing {
        override val basis = QuotePricingBasis.REVISE_SERVICE_SELECTIONS
    }

    /**
     * A complete proposed configuration priced from the current catalog. Its generated lines
     * replace the Estimate's whole line set when any amount differs.
     */
    data class RepriceConfiguration(
        val inputs: FionasPricingInputs,
    ) : QuotePricing {
        override val basis = QuotePricingBasis.REPRICE_CONFIGURATION
    }
}

enum class QuotePricingBasis { KEEP_ESTIMATE, REVISE_SERVICE_SELECTIONS, REPRICE_CONFIGURATION }

/** What a direct override replaces: a reviewed persisted line, or a freshly generated charge. */
sealed interface QuoteOverrideTarget {
    /** A line of the reviewed Estimate, by its authoritative persisted id; only without repricing. */
    data class ExistingLine(
        val lineItemId: UUID,
    ) : QuoteOverrideTarget

    /** The line a repriced configuration generates for [source]; only with repricing. */
    data class GeneratedCharge(
        val source: FionasChargeSource,
    ) : QuoteOverrideTarget
}

/**
 * The negotiated final amount of one generated service line: never a delta or a hidden
 * discount, and never negative. Negative corrections are separate [QuoteAdjustment]s.
 */
data class QuoteLineOverride(
    val target: QuoteOverrideTarget,
    val finalAmount: Money,
    val reason: QuoteEditReason,
) {
    init {
        require(finalAmount.amount.signum() >= 0) { "An overridden line amount must not be negative" }
        requireMinorUnits(finalAmount, "An overridden line amount")
    }
}

/** Fiona's meaning of a separate adjustment line; the ledger sees only its signed amount. */
enum class QuoteAdjustmentKind {
    /** An additional charge, a positive line. */
    CHARGE,

    /** A price reduction, a negative line. */
    DISCOUNT,

    /** A service credit, a negative line distinct in meaning from a discount. */
    CREDIT,
    ;

    /** The line's signed amount for a positive [magnitude]. */
    fun signed(magnitude: Money): Money =
        when (this) {
            CHARGE -> magnitude
            DISCOUNT, CREDIT -> Money(magnitude.amount.negate(), magnitude.currency)
        }
}

/**
 * One separate charge, discount or credit. [amount] is the positive magnitude; [kind] decides
 * the sign. [key] identifies it within one request only.
 */
data class QuoteAdjustment(
    val key: QuoteAdjustmentKey,
    val kind: QuoteAdjustmentKind,
    val description: QuoteLineDescription,
    val subDescription: QuoteLineSubDescription?,
    val amount: Money,
    val reason: QuoteEditReason,
) {
    init {
        require(amount.amount.signum() > 0) { "An adjustment amount must be positive; its kind decides the sign" }
        requireMinorUnits(amount, "An adjustment amount")
    }
}

/** A request-local identity for one adjustment, so a preview can say which line it became. */
@JvmInline
value class QuoteAdjustmentKey(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "An adjustment key is 1 to $MAX_LENGTH ASCII letters, digits, underscores or hyphens" }
    }

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")
    }
}

/** Why staff changed the generated pricing: required business intent, kept with the approved plan. */
@JvmInline
value class QuoteEditReason(
    val value: String,
) {
    init {
        requireCanonicalText(value, MAX_LENGTH, "A reason")
    }

    companion object {
        const val MAX_LENGTH = 500

        fun of(submitted: String) = QuoteEditReason(submitted.trim())
    }
}

/** The short description of a staff-entered line. */
@JvmInline
value class QuoteLineDescription(
    val value: String,
) {
    init {
        requireCanonicalText(value, MAX_LENGTH, "A line description")
    }

    companion object {
        const val MAX_LENGTH = 120

        fun of(submitted: String) = QuoteLineDescription(submitted.trim())
    }
}

/** Optional secondary text of a staff-entered line. */
@JvmInline
value class QuoteLineSubDescription(
    val value: String,
) {
    init {
        requireCanonicalText(value, MAX_LENGTH, "A line sub-description")
    }

    companion object {
        const val MAX_LENGTH = 240

        /** The sub-description in [submitted], or `null` when nothing but whitespace was submitted. */
        fun ofOptional(submitted: String?) = submitted?.trim()?.takeIf { it.isNotEmpty() }?.let(::QuoteLineSubDescription)
    }
}

private fun requireCanonicalText(
    value: String,
    maximum: Int,
    what: String,
) {
    require(value.isNotBlank()) { "$what must not be blank" }
    require(value == value.trim()) { "$what must not start or end with whitespace" }
    require(value.length <= maximum) { "$what must be at most $maximum characters" }
}

/** Staff-entered money has at most its currency's minor-unit digits: nothing is ever rounded. */
private fun requireMinorUnits(
    money: Money,
    what: String,
) {
    val digits = money.currency.defaultFractionDigits
    require(digits >= 0 && money.amount.stripTrailingZeros().scale() <= digits) {
        "$what in ${money.currency.currencyCode} has at most $digits decimal places"
    }
}
