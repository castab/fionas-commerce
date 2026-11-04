package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class JdbiPaymentAccessRepository {
    fun forInquiry(
        transaction: Transaction,
        id: InquiryId,
    ): PaymentAccess? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_payment_access WHERE inquiry_id = :id")
            .bind("id", id.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    fun forPublicId(
        transaction: Transaction,
        id: UUID,
    ): PaymentAccess? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_payment_access WHERE public_id = :id")
            .bind("id", id)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    /** The caller serializes on the canonical association, also used by checkout authorization. */
    fun save(
        transaction: Transaction,
        access: PaymentAccess,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.inquiry_payment_access
                    (inquiry_id, public_id, generation, token_hash, revoked, issued_at, issued_by)
                VALUES (:inquiry, :public, :generation, :hash, :revoked, :at, :by)
                ON CONFLICT (inquiry_id) DO UPDATE SET generation = EXCLUDED.generation,
                    token_hash = EXCLUDED.token_hash, revoked = EXCLUDED.revoked,
                    issued_at = EXCLUDED.issued_at, issued_by = EXCLUDED.issued_by
                """.trimIndent(),
            ).bind("inquiry", access.inquiryId.value)
            .bind("public", access.publicId)
            .bind("generation", access.generation)
            .bind("hash", access.tokenHash)
            .bind("revoked", access.revoked)
            .bind("at", access.issuedAt)
            .bind("by", access.issuedBy.value)
            .execute()
    }

    private fun restore(row: ResultSet) =
        PaymentAccess(
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            row.getObject("public_id", UUID::class.java),
            row.getString("generation"),
            row.getString("token_hash"),
            row.getBoolean("revoked"),
            row.getObject("issued_at", OffsetDateTime::class.java).toInstant(),
            UserId(row.getObject("issued_by", UUID::class.java)),
        )
}
