package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/**
 * Persistence of [InquiryDocumentAssociation]s, inside the caller's [Transaction]. Never
 * begins, commits, or rolls back a transaction; the calling operation owns the boundary.
 */
interface InquiryFinancialDocumentRepository {
    /** All canonical inquiry/document relationships, without locks or RELATED lineages. */
    fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID>

    /**
     * The canonical initial Estimate lineage, or null when [inquiryId] names no inquiry. Every
     * accepted inquiry has one; no initial Estimate is inferred for development data that predates it.
     */
    fun initialEstimateOf(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): UUID?

    /**
     * Records that the lineage belongs to the inquiry. The inquiry and the lineage's first
     * snapshot must already exist in the same database.
     */
    fun associate(
        transaction: Transaction,
        association: InquiryDocumentAssociation,
    )

    /** The inquiry that owns the lineage [documentId], or `null` when Fiona does not own it. */
    fun inquiryOf(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId?

    /** Set-based ownership lookup, without row locks. Missing ids are absent from the map. */
    fun inquiriesOf(
        transaction: Transaction,
        documentIds: Collection<UUID>,
    ): Map<UUID, InquiryId>

    /** [inquiryOf], locking the association until the caller's transaction ends. */
    fun lockInquiryOf(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId?

    /** The lineages [inquiryId] owns, oldest association first. */
    fun documentsOf(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<UUID>
}
