package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Creates the first snapshot of a new RELATED inquiry-owned lineage, at the chosen stage, from
 * the final lines a verified staff user committed. Nothing is priced or checked against a
 * catalog. The ledger snapshot, inquiry association and line authorship commit or roll back
 * together.
 */
class CreateInquiryFinancialDocument(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    private val materialize: MaterializeInquiryFinancialDocument,
    private val clock: Clock,
) {
    data class Command(
        val inquiryId: InquiryId,
        val stage: FirstSnapshotStage,
        val lines: List<PricedLine>,
        /** The verified staff user committing the lines, from authentication. */
        val author: UserId,
    ) {
        init {
            requireDocumentLines(lines)
            requireNonnegativeTotal(lines)
        }
    }

    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(command: Command): InquiryFinancialDocument {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            inquiries.findById(transaction, command.inquiryId)
                ?: throw CommerceFailure.NotFound("Inquiry ${command.inquiryId.value} was not found")
            val created = materialize.create(transaction, command.inquiryId, command.stage, command.lines, command.author, now)
            documents.describeLocked(transaction, command.inquiryId, created.id)
        }
    }
}
