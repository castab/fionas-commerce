package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import java.time.Instant
import java.util.UUID

class JdbiInquirySubmissionRepository : InquirySubmissionRepository {
    override fun claim(
        transaction: Transaction,
        key: InquirySubmissionKey,
        fingerprint: String,
        inquiryId: InquiryId,
        createdAt: Instant,
    ): Boolean =
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.inquiry_submissions (idempotency_key, request_fingerprint, inquiry_id, created_at)
           VALUES (:key, :fingerprint, :inquiryId, :createdAt)
           ON CONFLICT (idempotency_key) DO NOTHING""",
            ).bind("key", key.value)
            .bind("fingerprint", fingerprint)
            .bind("inquiryId", inquiryId.value)
            .bind("createdAt", createdAt)
            .execute() == 1

    override fun find(
        transaction: Transaction,
        key: InquirySubmissionKey,
    ): InquirySubmission? =
        transaction.handle
            .createQuery(
                """SELECT request_fingerprint, inquiry_id FROM fionas.inquiry_submissions WHERE idempotency_key = :key""",
            ).bind("key", key.value)
            .map {
                row,
                _,
                ->
                InquirySubmission(row.getString("request_fingerprint"), InquiryId(row.getObject("inquiry_id", UUID::class.java)))
            }.findOne()
            .orElse(null)
}
