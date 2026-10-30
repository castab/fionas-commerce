package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId

/** One short, batched database read for active inquiry subscriptions, independent of timeline size. */
class ReadInquiryTimelineRevisions(
    private val transactor: Transactor,
    private val revisions: InquiryTimelineRevisionRepository,
) {
    operator fun invoke(inquiries: Set<InquiryId>): Map<InquiryId, Long> {
        if (inquiries.isEmpty()) return emptyMap()
        return transactor.inTransaction { revisions.read(it, inquiries) }
    }
}
