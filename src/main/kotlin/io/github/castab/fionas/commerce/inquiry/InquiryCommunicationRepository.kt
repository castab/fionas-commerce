package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction

interface InquiryCommunicationRepository {
    fun append(
        transaction: Transaction,
        activity: InquiryCommunication,
    ): RecordedInquiryCommunication

    /** One aggregate per inquiry with activity, reduced in PostgreSQL in the caller's snapshot. Absence means no activity. */
    fun attentionFor(
        transaction: Transaction,
        inquiryIds: Collection<InquiryId>,
    ): Map<InquiryId, InquiryCommunicationAttention>
}
