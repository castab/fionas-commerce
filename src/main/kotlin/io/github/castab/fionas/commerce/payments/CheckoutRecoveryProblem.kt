package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.operation.CommerceFailure

/** Stable customer actions for unsuccessful recovery; these commands authorize no new money or key. */
enum class CheckoutRecoveryProblem(
    val code: String,
    val description: String,
) {
    OPEN("checkout_in_progress", "The existing Checkout is still open; resume it or wait for confirmed expiration"),
    PROCESSING("payment_processing", "An existing payment is awaiting provider confirmation; do not start another payment"),
    CHANGED("payment_state_changed", "An earlier payment has been recorded; refresh the payment summary before paying again"),
    COMPETING("checkout_recovery_conflict", "Another payment request changed the active attempt; refresh and retry deliberately"),
    UNKNOWN("checkout_reconciliation_required", "The provider outcome is unknown; staff must reconcile it before another payment"),
}

internal class CheckoutRecoveryRequired(
    val problem: CheckoutRecoveryProblem,
) : RuntimeException(problem.description)

internal fun CheckoutRecoveryProblem.conflict(): CommerceFailure.Conflict =
    CommerceFailure.Conflict(description, CheckoutRecoveryRequired(this))
