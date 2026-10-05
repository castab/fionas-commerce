package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.offering.restorePersistedPricingInputs
import io.github.castab.fionas.commerce.offering.toPersistedJson
import org.jdbi.v3.core.mapper.RowMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [InquiryRepository] on `fionas.inquiries`, through the caller's transaction. The requested
 * pricing inputs are the row's `pricing_inputs` jsonb, in Fiona's persisted representation.
 */
class JdbiInquiryRepository : InquiryRepository {
    override fun findByIds(
        transaction: Transaction,
        ids: Set<InquiryId>,
    ): Map<InquiryId, Inquiry> {
        if (ids.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery(
                """
                SELECT id, customer_id, message, created_at, zip_code, event_date, event_type
                FROM fionas.inquiries WHERE id = ANY(:ids)
                """.trimIndent(),
            ).bindArray("ids", UUID::class.java, ids.map { it.value })
            .map(inquiryRow)
            .list()
            .associateBy { it.id }
    }

    override fun ids(transaction: Transaction): Set<InquiryId> =
        transaction.handle
            .createQuery("SELECT id FROM fionas.inquiries")
            .map { row, _ -> InquiryId(row.getObject("id", UUID::class.java)) }
            .list()
            .toSet()

    override fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
        pricingInputs: FionasPricingInputs,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.inquiries (id, customer_id, message, created_at, zip_code, event_date, event_type, pricing_inputs)
                VALUES (:id, :customerId, :message, :createdAt, :zipCode, :eventDate, :eventType, CAST(:pricingInputs AS jsonb))
                """.trimIndent(),
            ).bind("id", inquiry.id.value)
            .bind("customerId", inquiry.customerId.value)
            .bind("message", inquiry.message?.value)
            .bind("createdAt", inquiry.createdAt)
            .bind("zipCode", inquiry.zipCode.value)
            .bind("eventDate", inquiry.eventDate.value)
            .bind("eventType", inquiry.eventType.name)
            .bind("pricingInputs", pricingInputs.toPersistedJson())
            .execute()
    }

    override fun findById(
        transaction: Transaction,
        id: InquiryId,
    ): Inquiry? =
        transaction.handle
            .createQuery(
                """
                SELECT id, customer_id, message, created_at, zip_code, event_date, event_type FROM fionas.inquiries WHERE id = :id
                """.trimIndent(),
            ).bind("id", id.value)
            .map(inquiryRow)
            .findOne()
            .orElse(null)

    override fun findRequested(
        transaction: Transaction,
        id: InquiryId,
    ): RequestedInquiry? =
        transaction.handle
            .createQuery(
                """
                SELECT id, customer_id, message, created_at, zip_code, event_date, event_type, pricing_inputs
                FROM fionas.inquiries WHERE id = :id
                """.trimIndent(),
            ).bind("id", id.value)
            .map { row, context ->
                RequestedInquiry(
                    inquiryRow.map(row, context),
                    restorePersistedPricingInputs("inquiry ${id.value}", row.getString("pricing_inputs")),
                )
            }.findOne()
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
                    SELECT id, customer_id, message, created_at, zip_code, event_date, event_type FROM fionas.inquiries
                    ORDER BY created_at DESC, id DESC
                    LIMIT :limit
                    """.trimIndent(),
                )
            } else {
                transaction.handle
                    .createQuery(
                        """
                        SELECT id, customer_id, message, created_at, zip_code, event_date, event_type FROM fionas.inquiries
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
            zipCode = ZipCode(row.getString("zip_code")),
            eventDate = EventDate(row.getObject("event_date", LocalDate::class.java)),
            eventType = EventType.valueOf(row.getString("event_type")),
        )
    }
