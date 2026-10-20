package io.github.castab.fionas.commerce.financial

import java.math.BigDecimal

internal const val EXACT_DECIMAL_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$"
private val exactDecimalText = Regex(EXACT_DECIMAL_PATTERN)

/** Shared input validation for HTTP and producer events: exact plain decimal text, no exponent or rounding. */
internal fun parseExactDecimal(
    text: String,
    what: String,
): BigDecimal {
    require(text.length <= 40 && exactDecimalText.matches(text)) {
        "$what must be an exact decimal string, for example 450.00"
    }
    return BigDecimal(text)
}
