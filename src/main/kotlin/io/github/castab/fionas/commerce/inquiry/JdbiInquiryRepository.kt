package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import org.jdbi.v3.core.mapper.RowMapper
import java.time.OffsetDateTime
import java.util.UUID

/** [InquiryRepository] on `public.inquiries`, through the transaction's JDBI handle. */
class JdbiInquiryRepository : InquiryRepository {
    override fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO public.inquiries (id, customer_id, message, created_at)
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
            .createQuery("SELECT id, customer_id, message, created_at FROM public.inquiries WHERE id = :id")
            .bind("id", id.value)
            .map(inquiryRow)
            .findOne()
            .orElse(null)
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
