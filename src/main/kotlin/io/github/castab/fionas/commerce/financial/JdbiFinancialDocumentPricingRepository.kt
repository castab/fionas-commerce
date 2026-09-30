package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Duration
import java.util.UUID

/**
 * [FinancialDocumentPricingRepository] on `fionas.financial_document_pricing` and its ordered
 * `…_categories` and `…_selections`, through the transaction's JDBI handle. Category blocks
 * and the offerings in each keep their submitted positions; an empty block is a category row
 * without selections.
 */
class JdbiFinancialDocumentPricingRepository : FinancialDocumentPricingRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
        inputs: FionasPricingInputs,
    ) {
        val context = inputs.context
        val minutes = context.duration.toMinutes()
        check(Duration.ofMinutes(minutes) == context.duration) { "A priced service duration is a whole number of minutes" }
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_pricing
                    (document_id, document_version, catalog_revision, guest_count, guest_count_is_minimum, duration_minutes)
                VALUES (:documentId, :version, :catalogRevision, :guestCount, :guestCountIsMinimum, :durationMinutes)
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .bind("catalogRevision", inputs.catalogRevision.number)
            .bind("guestCount", context.guestCount)
            .bind("guestCountIsMinimum", context.guestCountIsMinimum)
            .bind("durationMinutes", Math.toIntExact(minutes))
            .execute()
        inputs.selections.categories.forEachIndexed { categoryPosition, block ->
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.financial_document_pricing_categories (document_id, document_version, position, category_key)
                    VALUES (:documentId, :version, :position, :category)
                    """.trimIndent(),
                ).bind("documentId", snapshot.id)
                .bind("version", snapshot.version.number)
                .bind("position", categoryPosition)
                .bind("category", block.category.value)
                .execute()
            block.offerings.forEachIndexed { position, offering ->
                transaction.handle
                    .createUpdate(
                        """
                        INSERT INTO fionas.financial_document_pricing_selections
                            (document_id, document_version, category_position, position, offering_key)
                        VALUES (:documentId, :version, :categoryPosition, :position, :offering)
                        """.trimIndent(),
                    ).bind("documentId", snapshot.id)
                    .bind("version", snapshot.version.number)
                    .bind("categoryPosition", categoryPosition)
                    .bind("position", position)
                    .bind("offering", offering.value)
                    .execute()
            }
        }
    }

    override fun find(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
    ): FionasPricingInputs? {
        val source =
            transaction.handle
                .createQuery(
                    """
                    SELECT catalog_revision, guest_count, guest_count_is_minimum, duration_minutes
                    FROM fionas.financial_document_pricing
                    WHERE document_id = :documentId AND document_version = :version
                    """.trimIndent(),
                ).bind("documentId", snapshot.id)
                .bind("version", snapshot.version.number)
                .map { row, _ ->
                    Source(
                        OfferingsRevision.of(row.getInt("catalog_revision")),
                        FionasOfferingsContext(
                            guestCount = row.getInt("guest_count"),
                            guestCountIsMinimum = row.getBoolean("guest_count_is_minimum"),
                            duration = Duration.ofMinutes(row.getLong("duration_minutes")),
                        ),
                    )
                }.findOne()
                .orElse(null) ?: return null
        val categories =
            transaction.handle
                .createQuery(
                    """
                    SELECT category_key FROM fionas.financial_document_pricing_categories
                    WHERE document_id = :documentId AND document_version = :version
                    ORDER BY position
                    """.trimIndent(),
                ).bind("documentId", snapshot.id)
                .bind("version", snapshot.version.number)
                .map { row, _ -> OfferingCategoryKey(row.getString("category_key")) }
                .list()
        val selections =
            transaction.handle
                .createQuery(
                    """
                    SELECT category_position, offering_key FROM fionas.financial_document_pricing_selections
                    WHERE document_id = :documentId AND document_version = :version
                    ORDER BY category_position, position
                    """.trimIndent(),
                ).bind("documentId", snapshot.id)
                .bind("version", snapshot.version.number)
                .map { row, _ -> row.getInt("category_position") to OfferingKey(row.getString("offering_key")) }
                .list()
                .groupBy({ it.first }, { it.second })
        return FionasPricingInputs(
            catalogRevision = source.catalogRevision,
            selections =
                OfferingSelections(
                    categories.mapIndexed { position, category ->
                        OfferingCategorySelection(category, selections[position].orEmpty())
                    },
                ),
            context = source.context,
        )
    }

    override fun findAll(
        transaction: Transaction,
        documentId: UUID,
    ): Map<Version, FionasPricingInputs> =
        transaction.handle
            .createQuery(
                """
                SELECT document_version FROM fionas.financial_document_pricing
                WHERE document_id = :documentId
                ORDER BY document_version
                """.trimIndent(),
            ).bind("documentId", documentId)
            .map { row, _ -> Version.of(row.getInt("document_version")) }
            .list()
            .associateWith { version -> checkNotNull(find(transaction, FinancialDocumentReference(documentId, version))) }

    override fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    ) {
        check(from.id == to.id && from.version < to.version) { "A pricing source is copied forward within one lineage, not $from to $to" }
        val copied =
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.financial_document_pricing
                        (document_id, document_version, catalog_revision, guest_count, guest_count_is_minimum, duration_minutes)
                    SELECT document_id, :toVersion, catalog_revision, guest_count, guest_count_is_minimum, duration_minutes
                    FROM fionas.financial_document_pricing
                    WHERE document_id = :documentId AND document_version = :fromVersion
                    """.trimIndent(),
                ).bind("documentId", from.id)
                .bind("fromVersion", from.version.number)
                .bind("toVersion", to.version.number)
                .execute()
        // Materialized inquiry estimates have no legacy pricing metadata to carry forward.
        if (copied == 0) return
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_pricing_categories (document_id, document_version, position, category_key)
                SELECT document_id, :toVersion, position, category_key
                FROM fionas.financial_document_pricing_categories
                WHERE document_id = :documentId AND document_version = :fromVersion
                """.trimIndent(),
            ).bind("documentId", from.id)
            .bind("fromVersion", from.version.number)
            .bind("toVersion", to.version.number)
            .execute()
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_pricing_selections
                    (document_id, document_version, category_position, position, offering_key)
                SELECT document_id, :toVersion, category_position, position, offering_key
                FROM fionas.financial_document_pricing_selections
                WHERE document_id = :documentId AND document_version = :fromVersion
                """.trimIndent(),
            ).bind("documentId", from.id)
            .bind("fromVersion", from.version.number)
            .bind("toVersion", to.version.number)
            .execute()
    }

    private class Source(
        val catalogRevision: OfferingsRevision,
        val context: FionasOfferingsContext,
    )
}
