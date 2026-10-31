package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** Timeline invalidation tokens, not a count of messages or an authoritative timeline version. */
class InquiryTimelineRevisionRepository {
    /** Call after business locks/writes; use one sorted set for cross-inquiry changes. */
    fun advance(
        transaction: Transaction,
        inquiries: Collection<InquiryId>,
    ) {
        inquiries.distinct().sortedBy { it.value }.forEach { inquiry ->
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_change_revisions (inquiry_id, revision) VALUES (:id, 1)
                    ON CONFLICT (inquiry_id) DO UPDATE SET revision = fionas.inquiry_change_revisions.revision + 1
                    """.trimIndent(),
                ).bind("id", inquiry.value)
                .execute()
        }
    }

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
