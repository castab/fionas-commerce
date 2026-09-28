package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Instant
import java.util.UUID

/**
 * Fiona's record that a commerce-runtime financial-document lineage belongs to an inquiry.
 *
 * The document itself (its snapshots, lines, and totals) is commerce-runtime's; Fiona owns
 * only this relationship. One inquiry may have several lineages, for example an alternative
 * or restarted proposal; revisions of one proposal are versions inside its lineage.
 */
data class InquiryDocumentAssociation(
    val inquiryId: InquiryId,
    val documentId: UUID,
    val createdAt: Instant,
)

/** One immutable financial-document snapshot and the Fiona [pricing] inputs that produced it. */
data class PricedSnapshot(
    val document: FinancialDocument,
    val pricing: FionasPricingInputs,
)

/**
 * An inquiry's financial-document lineage at its [latest] snapshot, with the settlement
 * commerce-runtime derives across the whole lineage: allocations stay attached to the
 * snapshot they were made against, and the latest snapshot supplies the total owed.
 */
data class InquiryFinancialDocument(
    val inquiryId: InquiryId,
    val latest: PricedSnapshot,
    val reconciliation: FinancialDocumentReconciliation,
)

/**
 * Every snapshot of one lineage, oldest first, each with its own pricing source. Historical
 * snapshots carry no reconciliation: current settlement belongs to the latest snapshot.
 */
data class InquiryFinancialDocumentHistory(
    val inquiryId: InquiryId,
    val documentId: UUID,
    val versions: List<PricedSnapshot>,
)

/** A payment Fiona recorded and applied in full to [allocation]'s exact snapshot, and the lineage afterwards. */
data class RecordedPayment(
    val payment: PaymentRecord,
    val allocation: PaymentAllocation,
    val document: InquiryFinancialDocument,
)

/**
 * Fiona's view of commerce-runtime's financial ledger, inside the caller's transaction: a
 * lineage exists for Fiona only when an inquiry owns it, whatever else the ledger holds.
 *
 * It reads through the ledger's `Transaction` overloads and never opens a transaction.
 */
internal class FionaFinancialDocuments(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
) {
    /** The latest snapshot of a lineage an operation is about to change, and the inquiry that owns it. */
    class Current(
        val inquiryId: InquiryId,
        val document: FinancialDocument,
    )

    /** The inquiry that owns [documentId]; [CommerceFailure.NotFound] for a lineage Fiona does not own. */
    fun inquiryOf(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId = associations.inquiryOf(transaction, documentId) ?: throw notFound(documentId)

    /**
     * The latest snapshot of [documentId], provided it is still [expected], the version the
     * caller acted on; otherwise [CommerceFailure.Conflict], so that no action lands on a
     * snapshot its caller never saw.
     *
     * The lineage's association row is locked first, so Fiona's mutations of one lineage run
     * one at a time and this check still holds when the operation writes. The runtime's
     * `(document_id, previous_version)` uniqueness remains the final guard.
     */
    fun expectLatest(
        transaction: Transaction,
        documentId: UUID,
        expected: Version,
    ): Current {
        val inquiryId = associations.lockInquiryOf(transaction, documentId) ?: throw notFound(documentId)
        val latest = ledger.latest(transaction, documentId)
        if (latest.version != expected) {
            throw CommerceFailure.Conflict(
                "Financial document $documentId is at ${latest.version}, not the expected $expected; reload it and retry",
            )
        }
        return Current(inquiryId, latest)
    }

    /** The lineage's latest snapshot, its pricing source, and its current settlement. */
    fun current(
        transaction: Transaction,
        inquiryId: InquiryId,
        documentId: UUID,
    ): InquiryFinancialDocument {
        val latest = ledger.latest(transaction, documentId)
        return InquiryFinancialDocument(inquiryId, priced(transaction, latest), ledger.reconcileLatest(transaction, documentId))
    }

    /** Every snapshot of the lineage, oldest first, with its pricing source. */
    fun history(
        transaction: Transaction,
        inquiryId: InquiryId,
        documentId: UUID,
    ): InquiryFinancialDocumentHistory {
        val sources = pricingSources.findAll(transaction, documentId)
        val versions =
            ledger.history(transaction, documentId).map { document ->
                PricedSnapshot(document, checkNotNull(sources[document.version]) { missingSource(document) })
            }
        return InquiryFinancialDocumentHistory(inquiryId, documentId, versions)
    }

    private fun priced(
        transaction: Transaction,
        document: FinancialDocument,
    ) = PricedSnapshot(document, checkNotNull(pricingSources.find(transaction, document.reference)) { missingSource(document) })

    // Every Fiona snapshot is written with its pricing source in one transaction; a missing one is an internal failure.
    private fun missingSource(document: FinancialDocument) = "Financial document ${document.reference} has no pricing source"

    private fun notFound(documentId: UUID) = CommerceFailure.NotFound("Financial document $documentId was not found")
}
