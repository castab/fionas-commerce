package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryFulfillmentRepository
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Fiona's staff change order: commits the complete final lines a verified staff user authored
 * for the exact version they reviewed, as a same-stage successor (an estimate, a RELATED quote,
 * or an invoice). Existing lines keep their ids when carried or edited; omitted lines are
 * removed; new lines are added ([resolveAgainst]). Nothing is priced or checked against a
 * catalog, and old lines are never reconstructed: the immutable predecessor stands alone.
 *
 * In one runtime transaction: the lineage must belong to an inquiry and its latest version must
 * be the one the caller reviewed ([CommerceFailure.Conflict] otherwise); canonical Quotes require
 * the atomic proposal workflow; a CLOSED canonical lineage needs a separate post-close workflow;
 * the resulting total must not be negative; lines identical to the current ones are no change.
 */
class CreateChangeOrder(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    private val fulfillment: InquiryFulfillmentRepository,
    private val clock: Clock,
) {
    data class Command(
        val documentId: UUID,
        val expectedVersion: Version,
        val lines: LineProposal,
        /** The verified staff user committing the lines, from authentication. */
        val author: UserId,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(command: Command): InquiryFinancialDocument {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.expectedVersion)
            documents.rejectCanonicalQuoteMutation(transaction, current)
            if (documents.isCanonical(transaction, current) && fulfillment.find(transaction, current.inquiryId)?.closed != null) {
                throw CommerceFailure.IllegalTransition("A closed inquiry requires a separate post-close adjustment workflow")
            }
            documents.commitLines(transaction, current, command.lines.resolveAgainst(current.document), command.author, now)
            documents.describeLocked(transaction, current.inquiryId, command.documentId)
        }
    }
}
