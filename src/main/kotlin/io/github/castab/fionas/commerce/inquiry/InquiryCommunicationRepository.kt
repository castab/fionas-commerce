package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction

interface InquiryCommunicationRepository {
    /**
     * Appends [activity] in the caller-owned [transaction], returning its persistence-assigned
     * durable Fiona observation order in [RecordedInquiryCommunication.recordedOrder].
     *
     * The referenced inquiry must exist; otherwise [CommerceFailure.NotFound] is thrown.
     * Same-inquiry appends are serialized so observation order causally orders reply/acknowledgement clearing.
     */
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
