package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.UUID

/**
 * One already-priced financial line, before it has a ledger identity: exactly what an
 * authorized commercial actor committed. Fiona never derives, checks or rounds the amount:
 * the actor is the pricing authority (the web server's service principal for a public
 * inquiry, a verified staff user for negotiated terms). Fiona only enforces that the values
 * are well formed and payable, and commerce-domain derives every total from the lines.
 *
 * Semantics are [LineItem]'s: a `null` [quantity] is a flat charge whose subtotal is
 * [unitPrice]; otherwise the subtotal is `unitPrice × quantity`. [taxAmount] is the final tax
 * of the whole line, already decided by the actor; never a rate. Signed amounts express
 * discounts and credits.
 *
 * Fiona's well-formedness policy, so that every amount is exact and settleable:
 * - [description] is trimmed, nonblank text of at most [DESCRIPTION_MAX_LENGTH] characters, and
 *   [subDescription], when present, of at most [SUB_DESCRIPTION_MAX_LENGTH];
 * - [unitPrice] and [taxAmount] share one currency, have at most its minor-unit digits and at
 *   most [MAX_AMOUNT_INTEGER_DIGITS] integer digits;
 * - a [quantity] is nonzero with at most [MAX_QUANTITY_INTEGER_DIGITS] integer and
 *   [MAX_QUANTITY_FRACTION_DIGITS] fraction digits, and the resulting subtotal still has at
 *   most the currency's minor-unit digits, because nothing is ever rounded.
 *
 * The constructor accepts only that canonical form; [of] trims submitted text first.
 */
data class PricedLine(
    val description: String,
    val subDescription: String?,
    val quantity: BigDecimal?,
    val unitPrice: Money,
    val taxAmount: Money,
) {
    init {
        requireCanonicalText(description, DESCRIPTION_MAX_LENGTH, "A line description")
        subDescription?.let { requireCanonicalText(it, SUB_DESCRIPTION_MAX_LENGTH, "A line sub-description") }
        require(unitPrice.currency == taxAmount.currency) { "A line's unit price and tax must use one currency" }
        requireAmount(unitPrice, "A line's unit price")
        requireAmount(taxAmount, "A line's tax amount")
        quantity?.let { quantity ->
            require(quantity.signum() != 0) { "A line quantity must not be zero; omit it for a flat charge" }
            require(quantity.integerDigits() <= MAX_QUANTITY_INTEGER_DIGITS) {
                "A line quantity has at most $MAX_QUANTITY_INTEGER_DIGITS integer digits"
            }
            require(quantity.stripTrailingZeros().scale() <= MAX_QUANTITY_FRACTION_DIGITS) {
                "A line quantity has at most $MAX_QUANTITY_FRACTION_DIGITS decimal places"
            }
            require(minorUnits(unitPrice.amount.multiply(quantity), unitPrice.currency)) {
                "A line's subtotal (unit price × quantity) must be exact in ${unitPrice.currency.currencyCode} minor units; " +
                    "nothing is rounded"
            }
        }
    }

    val currency: Currency get() = unitPrice.currency

    /** The line amount including tax, exactly as commerce-domain derives it for a [LineItem]. */
    val total: Money get() = (quantity?.let { unitPrice * it } ?: unitPrice) + taxAmount

    /** This line on a document, as [id]. */
    fun withId(id: UUID): LineItem = LineItem(id, description, subDescription, quantity, unitPrice, taxAmount)

    /** Whether this line charges exactly what [line] does, ignoring its id; amounts compare numerically. */
    fun charges(line: LineItem): Boolean = sameCharges(listOf(withId(line.id)), listOf(line))

    companion object {
        const val DESCRIPTION_MAX_LENGTH = 200
        const val SUB_DESCRIPTION_MAX_LENGTH = 500
        const val MAX_AMOUNT_INTEGER_DIGITS = 12
        const val MAX_QUANTITY_INTEGER_DIGITS = 9
        const val MAX_QUANTITY_FRACTION_DIGITS = 6

        /** A line from submitted text: [description] and [subDescription] are trimmed, and a blank sub-description is none. */
        fun of(
            description: String,
            subDescription: String?,
            quantity: BigDecimal?,
            unitPrice: Money,
            taxAmount: Money,
        ) = PricedLine(description.trim(), subDescription?.trim()?.takeIf { it.isNotEmpty() }, quantity, unitPrice, taxAmount)
    }
}

/** The most lines one financial-document snapshot may carry. */
const val MAX_DOCUMENT_LINES = 100

/** Requires [lines] to form one document's lines: at least one, at most [MAX_DOCUMENT_LINES], all in one currency. */
internal fun requireDocumentLines(lines: List<PricedLine>) {
    require(lines.isNotEmpty()) { "A financial document has at least one line" }
    require(lines.size <= MAX_DOCUMENT_LINES) { "A financial document has at most $MAX_DOCUMENT_LINES lines" }
    require(lines.map { it.currency }.toSet().size == 1) { "Every line of a financial document uses one currency" }
}

/** Fiona's policy for a new document's first snapshot: its derived total is never negative. */
internal fun requireNonnegativeTotal(lines: List<PricedLine>) {
    val total = lines.map { it.total }.reduce(Money::plus)
    require(total.amount.signum() >= 0) { "A financial document's total must not be negative" }
}

internal fun requireCanonicalText(
    value: String,
    maximum: Int,
    what: String,
) {
    require(value.isNotBlank()) { "$what must not be blank" }
    require(value == value.trim()) { "$what must not start or end with whitespace" }
    require(value.length <= maximum) { "$what must be at most $maximum characters" }
}

private fun requireAmount(
    money: Money,
    what: String,
) {
    require(money.amount.integerDigits() <= PricedLine.MAX_AMOUNT_INTEGER_DIGITS) {
        "$what has at most ${PricedLine.MAX_AMOUNT_INTEGER_DIGITS} integer digits"
    }
    require(minorUnits(money.amount, money.currency)) {
        "$what in ${money.currency.currencyCode} has at most ${money.currency.defaultFractionDigits} decimal places"
    }
}

private fun minorUnits(
    amount: BigDecimal,
    currency: Currency,
): Boolean = currency.defaultFractionDigits >= 0 && amount.stripTrailingZeros().scale() <= currency.defaultFractionDigits

private fun BigDecimal.integerDigits(): Int =
    abs()
        .setScale(0, RoundingMode.DOWN)
        .toPlainString()
        .trimStart('0')
        .length
