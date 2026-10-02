package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.offering.restorePersistedPricingInputs
import io.github.castab.fionas.commerce.offering.toPersistedJson
import java.util.UUID

/**
 * [FinancialDocumentPricingRepository] on `fionas.financial_document_pricing`, through the
 * transaction's JDBI handle: one row per exact `(document_id, document_version)`, whose
 * `pricing_inputs` jsonb holds the complete inputs in Fiona's persisted representation.
 */
class JdbiFinancialDocumentPricingRepository : FinancialDocumentPricingRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
        inputs: FionasPricingInputs,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_pricing (document_id, document_version, pricing_inputs)
                VALUES (:documentId, :version, CAST(:pricingInputs AS jsonb))
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .bind("pricingInputs", inputs.toPersistedJson())
            .execute()
    }

    override fun find(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
    ): FionasPricingInputs? =
        transaction.handle
            .createQuery(
                """
                SELECT pricing_inputs FROM fionas.financial_document_pricing
                WHERE document_id = :documentId AND document_version = :version
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .map { row, _ -> restorePersistedPricingInputs(snapshot.describe(), row.getString("pricing_inputs")) }
            .findOne()
            .orElse(null)

    override fun findAll(
        transaction: Transaction,
        documentId: UUID,
    ): Map<Version, FionasPricingInputs> =
        transaction.handle
            .createQuery(
                """
                SELECT document_version, pricing_inputs FROM fionas.financial_document_pricing
                WHERE document_id = :documentId
                ORDER BY document_version
                """.trimIndent(),
            ).bind("documentId", documentId)
            .map { row, _ ->
                val version = Version.of(row.getInt("document_version"))
                version to
                    restorePersistedPricingInputs(
                        FinancialDocumentReference(documentId, version).describe(),
                        row.getString("pricing_inputs"),
                    )
            }.list()
            .toMap()

    // The source is restored strictly and written again, so a corrupt pricing source fails
    // here rather than being copied into another historical record.
    override fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    ) {
        check(from.id == to.id && from.version < to.version) { "A pricing source is copied forward within one lineage, not $from to $to" }
        // Materialized inquiry estimates have no legacy pricing metadata to carry forward.
        val source = find(transaction, from) ?: return
        insert(transaction, to, source)
    }
}

private fun FinancialDocumentReference.describe() = "financial document $id version ${version.number}"
