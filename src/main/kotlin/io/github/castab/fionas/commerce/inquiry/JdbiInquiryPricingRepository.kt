package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Duration

/**
 * [InquiryPricingRepository] on `fionas.inquiry_pricing` and its ordered `…_categories` and
 * `…_selections`, through the transaction's JDBI handle. Category blocks and the offerings in
 * each keep their submitted positions; an empty block is a category row without selections.
 */
class JdbiInquiryPricingRepository : InquiryPricingRepository {
    override fun insert(
        transaction: Transaction,
        inquiryId: InquiryId,
        inputs: FionasPricingInputs,
    ) {
        val context = inputs.context
        val minutes = context.duration.toMinutes()
        check(Duration.ofMinutes(minutes) == context.duration) { "A requested service duration is a whole number of minutes" }
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.inquiry_pricing
                    (inquiry_id, catalog_revision, guest_count, guest_count_is_minimum, duration_minutes)
                VALUES (:inquiryId, :catalogRevision, :guestCount, :guestCountIsMinimum, :durationMinutes)
                """.trimIndent(),
            ).bind("inquiryId", inquiryId.value)
            .bind("catalogRevision", inputs.catalogRevision.number)
            .bind("guestCount", context.guestCount)
            .bind("guestCountIsMinimum", context.guestCountIsMinimum)
            .bind("durationMinutes", Math.toIntExact(minutes))
            .execute()
        inputs.selections.categories.forEachIndexed { categoryPosition, block ->
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_pricing_categories (inquiry_id, position, category_key)
                    VALUES (:inquiryId, :position, :category)
                    """.trimIndent(),
                ).bind("inquiryId", inquiryId.value)
                .bind("position", categoryPosition)
                .bind("category", block.category.value)
                .execute()
            block.offerings.forEachIndexed { position, offering ->
                transaction.handle
                    .createUpdate(
                        """
                        INSERT INTO fionas.inquiry_pricing_selections (inquiry_id, category_position, position, offering_key)
                        VALUES (:inquiryId, :categoryPosition, :position, :offering)
                        """.trimIndent(),
                    ).bind("inquiryId", inquiryId.value)
                    .bind("categoryPosition", categoryPosition)
                    .bind("position", position)
                    .bind("offering", offering.value)
                    .execute()
            }
        }
    }

    override fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): FionasPricingInputs? {
        val source =
            transaction.handle
                .createQuery(
                    """
                    SELECT catalog_revision, guest_count, guest_count_is_minimum, duration_minutes
                    FROM fionas.inquiry_pricing
                    WHERE inquiry_id = :inquiryId
                    """.trimIndent(),
                ).bind("inquiryId", inquiryId.value)
                .map { row, _ ->
                    OfferingsRevision.of(row.getInt("catalog_revision")) to
                        FionasOfferingsContext(
                            guestCount = row.getInt("guest_count"),
                            guestCountIsMinimum = row.getBoolean("guest_count_is_minimum"),
                            duration = Duration.ofMinutes(row.getLong("duration_minutes")),
                        )
                }.findOne()
                .orElse(null) ?: return null
        val categories =
            transaction.handle
                .createQuery(
                    """
                    SELECT category_key FROM fionas.inquiry_pricing_categories
                    WHERE inquiry_id = :inquiryId
                    ORDER BY position
                    """.trimIndent(),
                ).bind("inquiryId", inquiryId.value)
                .map { row, _ -> OfferingCategoryKey(row.getString("category_key")) }
                .list()
        val selections =
            transaction.handle
                .createQuery(
                    """
                    SELECT category_position, offering_key FROM fionas.inquiry_pricing_selections
                    WHERE inquiry_id = :inquiryId
                    ORDER BY category_position, position
                    """.trimIndent(),
                ).bind("inquiryId", inquiryId.value)
                .map { row, _ -> row.getInt("category_position") to OfferingKey(row.getString("offering_key")) }
                .list()
                .groupBy({ it.first }, { it.second })
        val (catalogRevision, context) = source
        return FionasPricingInputs(
            catalogRevision = catalogRevision,
            selections =
                OfferingSelections(
                    categories.mapIndexed { position, category ->
                        OfferingCategorySelection(category, selections[position].orEmpty())
                    },
                ),
            context = context,
        )
    }
}
