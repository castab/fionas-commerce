package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.financialemail.FinancialEmailOutcome
import io.github.castab.fionas.commerce.financialemail.FinancialEmailRecord

/** Safe, static reasons only; never content, provider diagnostics or credentials. */
enum class MessageTextVisibility {
    READABLE,
    FINANCIAL_EMAIL_PROTECTED,
    PAYMENT_CONTENT_UNVERIFIED,
    PROVIDER_CONTENT_UNAVAILABLE,
}

/**
 * Message identity establishes financial provenance even before acceptance. Other raw provider text is not
 * evidence of credential-free correspondence: inbound and ordinary outbound messages can both contain
 * pasted/quoted credentials. The provider currently supplies no trusted safe-content projection.
 * A definitive first-dispatch rejection establishes no provider send; UNKNOWN never does.
 */
internal fun ConversationMessage.withTextVisibility(financialEmails: List<FinancialEmailRecord>): ConversationMessage {
    val visibility =
        when {
            financialEmails.any { it.messageId == id } -> MessageTextVisibility.FINANCIAL_EMAIL_PROTECTED
            text == null -> MessageTextVisibility.PROVIDER_CONTENT_UNAVAILABLE
            financialEmails.any { it.outcome != FinancialEmailOutcome.REJECTED || it.dispatchCount != 1 } ->
                MessageTextVisibility.PAYMENT_CONTENT_UNVERIFIED
            else -> MessageTextVisibility.READABLE
        }
    val withheld =
        visibility == MessageTextVisibility.FINANCIAL_EMAIL_PROTECTED ||
            visibility == MessageTextVisibility.PAYMENT_CONTENT_UNVERIFIED
    return copy(
        text = if (withheld) null else text,
        textOmittedForPaymentSecurity = withheld,
        textVisibility = visibility,
    )
}
