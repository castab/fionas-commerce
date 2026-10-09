package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant
import java.util.UUID

/**
 * Persists an authorized actor's already-priced lines as the first snapshot of a new
 * inquiry-owned lineage, its inquiry relationship, and who authored the lines, in the caller's
 * transaction. Fresh line and document ids are generated here, after the caller has claimed
 * whatever identity guards the command. No catalog, pricing input, or pricing policy crosses
 * this boundary: the lines are final, and Fiona checks only that they form a valid document.
 */
class MaterializeInquiryFinancialDocument(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val authorship: FinancialDocumentAuthorshipRepository,
    private val newDocumentId: () -> UUID = UUID::randomUUID,
    private val newLineId: () -> UUID = UUID::randomUUID,
) {
    fun create(
        transaction: Transaction,
        inquiryId: InquiryId,
        stage: FirstSnapshotStage,
        lines: List<PricedLine>,
        author: PrincipalId,
        recordedAt: Instant,
        purpose: InquiryDocumentPurpose = InquiryDocumentPurpose.RELATED,
    ): FinancialDocument {
        require(purpose != InquiryDocumentPurpose.INITIAL_ESTIMATE || stage == FirstSnapshotStage.ESTIMATE) {
            "An initial estimate must start at Estimate"
        }
        requireDocumentLines(lines)
        requireNonnegativeTotal(lines)
        val items = lines.map { it.withId(newLineId()) }
        val id = newDocumentId()
        val first =
            when (stage) {
                FirstSnapshotStage.ESTIMATE -> FinancialDocument.Estimate.create(id, items)
                FirstSnapshotStage.QUOTE -> FinancialDocument.Quote.create(id, items)
                FirstSnapshotStage.INVOICE -> FinancialDocument.Invoice.create(id, items)
            }
        val created = ledger.create(transaction, first)
        associations.associate(transaction, InquiryDocumentAssociation(inquiryId, created.id, recordedAt, purpose))
        authorship.insert(transaction, created.reference, LineAuthorship(author, recordedAt))
        return created
    }
}

/** Where a new inquiry-owned lineage begins: a later stage is a legitimate start, not skipped history. */
enum class FirstSnapshotStage { ESTIMATE, QUOTE, INVOICE }
