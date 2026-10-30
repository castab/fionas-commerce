package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** Communication invalidation tokens, not a count of messages or an authoritative timeline version. */
class InquiryTimelineRevisionRepository {
    /** Missing counters mean baseline zero; missing inquiries are omitted. No history or provider reads. */
    fun read(
        transaction: Transaction,
        inquiries: Set<InquiryId>,
    ): Map<InquiryId, Long> {
        require(inquiries.size <= InquiryTimelineSubscriptions.MAX_SUBSCRIPTIONS)
        if (inquiries.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery(
                """
                SELECT i.id, coalesce(r.revision, 0) AS revision
                FROM fionas.inquiries i
                LEFT JOIN fionas.inquiry_change_revisions r ON r.inquiry_id = i.id
                WHERE i.id = ANY(:ids)
                """.trimIndent(),
            ).bindArray("ids", UUID::class.java, inquiries.map { it.value })
            .map { row, _ -> InquiryId(row.getObject("id", UUID::class.java)) to row.getLong("revision") }
            .list()
            .toMap()
    }
}
