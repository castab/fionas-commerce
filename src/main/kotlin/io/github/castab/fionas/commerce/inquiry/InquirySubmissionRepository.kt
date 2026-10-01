package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import java.time.Instant

data class InquirySubmission(
    val fingerprint: String,
    val inquiryId: InquiryId,
)

/** Durable public command identity; every call uses the operation's transaction. */
interface InquirySubmissionRepository {
    /** Waits on a competing key. True reserves [inquiryId]; false means a completed owner committed. */
    fun claim(
        transaction: Transaction,
        key: InquirySubmissionKey,
        fingerprint: String,
        inquiryId: InquiryId,
        createdAt: Instant,
    ): Boolean

    fun find(
        transaction: Transaction,
        key: InquirySubmissionKey,
    ): InquirySubmission?
}
