package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.util.UUID

/**
 * Optional legacy staff metadata describing the Fiona pricing inputs used to construct lines,
 * one record per exact `(document id, version)`, inside the caller's [Transaction]. Never
 * begins, commits, or rolls back a transaction; the calling operation owns the boundary.
 *
 * The snapshot's self-contained commercial facts are commerce-runtime's. Inquiry-generated
 * initial estimates do not write this metadata. Records are written once and never changed.
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
     * same lineage. When [from] has no legacy metadata, this is a no-op: financial transitions
     * depend only on the prior snapshot's concrete lines.
     */
    fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    )
}
