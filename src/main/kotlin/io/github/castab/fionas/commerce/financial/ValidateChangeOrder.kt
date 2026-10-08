package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating

/** Fiona policy, evaluated under the association lock before any successor or related write. */
internal fun validateChangeOrder(
    current: FinancialDocument,
    changes: ChangeOrder,
) {
    val successor = validating { current.changeOrder(changes) }
    if (successor.total.amount.signum() < 0) {
        throw CommerceFailure.ValidationFailed("A change order must not produce a negative financial-document total")
    }
}
