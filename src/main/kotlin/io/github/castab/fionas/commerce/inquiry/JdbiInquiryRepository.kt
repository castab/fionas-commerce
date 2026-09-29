package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import org.jdbi.v3.core.mapper.RowMapper
import java.time.OffsetDateTime
import java.util.UUID

/** [InquiryRepository] on `fionas.inquiries`, through the transaction's JDBI handle. */
class JdbiInquiryRepository : InquiryRepository {
    override fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.inquiries (id, customer_id, message, created_at)
                VALUES (:id, :customerId, :message, :createdAt)
                """.trimIndent(),
            ).bind("id", inquiry.id.value)
            .bind("customerId", inquiry.customerId.value)
            .bind("message", inquiry.message?.value)
            .bind("createdAt", inquiry.createdAt)
            .execute()
    }

    override fun findById(
        transaction: Transaction,
        id: InquiryId,
    ): Inquiry? =
        transaction.handle
            .createQuery("SELECT id, customer_id, message, created_at FROM fionas.inquiries WHERE id = :id")
            .bind("id", id.value)
            .map(inquiryRow)
            .findOne()
            .orElse(null)

    // Two statements rather than one with an optional predicate, so each is a plain range scan
    // of inquiries_created_at_id_idx; the row comparison continues strictly after the cursor.
    override fun listNewestFirst(
        transaction: Transaction,
        after: InquiryListPosition?,
        limit: Int,
    ): List<Inquiry> {
        require(limit >= 1) { "A page holds at least one inquiry" }
        val query =
            if (after == null) {
                transaction.handle.createQuery(
                    """
                    SELECT id, customer_id, message, created_at FROM fionas.inquiries
                    ORDER BY created_at DESC, id DESC
                    LIMIT :limit
                    """.trimIndent(),
                )
            } else {
                transaction.handle
                    .createQuery(
                        """
                        SELECT id, customer_id, message, created_at FROM fionas.inquiries
                        WHERE (created_at, id) < (:afterCreatedAt, :afterId)
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """.trimIndent(),
                    ).bind("afterCreatedAt", after.createdAt)
                    .bind("afterId", after.id.value)
            }
        return query
            .bind("limit", limit)
            .map(inquiryRow)
            .list()
    }
}

private val inquiryRow =
    RowMapper { row, _ ->
        Inquiry(
            id = InquiryId(row.getObject("id", UUID::class.java)),
            customerId = CustomerId(row.getObject("customer_id", UUID::class.java)),
            message = row.getString("message")?.let(::InquiryMessage),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }
