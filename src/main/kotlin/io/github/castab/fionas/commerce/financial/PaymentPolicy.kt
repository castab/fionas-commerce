package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import java.math.BigDecimal
import java.util.Currency

/** Fiona accepts allocations only against a quote or invoice. */
internal fun FinancialDocument.requirePaymentDestination() {
    if (this is FinancialDocument.Estimate) {
        throw CommerceFailure.InvariantViolated(
            "Financial document $id is an estimate; payments are accepted against a quote or an invoice",
        )
    }
}

/** Constructs an exact amount in the selected currency for any Fiona payment path. */
internal fun paymentMoney(
    amount: BigDecimal,
    currency: Currency,
): Money {
    if (amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) {
        throw CommerceFailure.ValidationFailed(
            "A ${currency.currencyCode} payment amount has at most ${currency.defaultFractionDigits} decimal places",
        )
    }
    return validating { Money(amount, currency) }
}
