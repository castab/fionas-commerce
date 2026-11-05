package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.financialemail.JdbiFinancialEmailRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository

/**
 * Evidence of the inquiry's provider topic: an ingested event, accepted reply, or confirmed conversation identity
 * on a financial-email command. The shared read/send rule prevents replacement of a vanished established thread,
 * including without event intake or when a known financial message remains indeterminate.
 */
class ConversationEstablishment(
    private val sources: JdbiInquiryConversationRepository,
    private val replies: JdbiStaffReplyRepository,
    private val financialEmails: JdbiFinancialEmailRepository = JdbiFinancialEmailRepository(),
) {
    fun established(
        transaction: Transaction,
        inquiry: InquiryId,
    ): Boolean =
        sources.established(transaction, inquiry) ||
            replies.established(transaction, inquiry) ||
            financialEmails.established(transaction, inquiry)
}
