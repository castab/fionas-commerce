package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.util.UUID

/**
 * Persistence of the Fiona pricing inputs each financial-document snapshot was priced from,
 * one record per exact `(document id, version)`, inside the caller's [Transaction]. Never
 * begins, commits, or rolls back a transaction; the calling operation owns the boundary.
 *
 * The snapshot's commercial facts are commerce-runtime's; this is only why Fiona priced it
 * that way. Records are written once and never changed.
 */
interface FinancialDocumentPricingRepository {
    /**
     * Records [inputs] as the pricing source of [snapshot]. The snapshot must already exist,
     * and its lineage must belong to an inquiry.
     */
    fun insert(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
        inputs: FionasPricingInputs,
    )

    /** The pricing source of exactly [snapshot], or `null` when none is recorded. */
    fun find(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
    ): FionasPricingInputs?

    /** The pricing source of every recorded version of the lineage [documentId]. */
    fun findAll(
        transaction: Transaction,
        documentId: UUID,
    ): Map<Version, FionasPricingInputs>

    /**
     * Records the pricing source of [from] unchanged as that of [to], a later snapshot of the
     * same lineage, as a lifecycle transition that did not reprice requires. [from] must have
     * a pricing source.
     */
    fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    )
}
