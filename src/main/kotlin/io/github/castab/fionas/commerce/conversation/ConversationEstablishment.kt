package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository

/**
 * Whether Fiona holds evidence that the inquiry's provider topic exists: an ingested provider event or an accepted
 * staff reply. The one rule shared by sending and the timeline, so a topic `404` with evidence is MISSING in both,
 * including when the conversation consumer is disabled and only accepted replies exist.
 */
class ConversationEstablishment(
    private val sources: JdbiInquiryConversationRepository,
    private val replies: JdbiStaffReplyRepository,
) {
    fun established(
        transaction: Transaction,
        inquiry: InquiryId,
    ): Boolean = sources.established(transaction, inquiry) || replies.established(transaction, inquiry)
}
