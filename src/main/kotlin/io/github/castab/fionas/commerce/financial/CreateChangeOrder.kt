package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryDeclineRepository
import io.github.castab.fionas.commerce.inquiry.InquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryDeclineRepository
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
 * no lineage of a declined inquiry changes;
 * the resulting total must not be negative; lines identical to the current ones are no change.
 */
class CreateChangeOrder(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    private val fulfillment: InquiryFulfillmentRepository,
    private val clock: Clock,
    declines: InquiryDeclineRepository = JdbiInquiryDeclineRepository(),
    private val servicePlans: InquiryServicePlanRepository = JdbiInquiryServicePlanRepository(),
    private val amendments: InquiryInvoiceAmendmentRepository = JdbiInquiryInvoiceAmendmentRepository(),
    private val newAmendmentId: () -> UUID = UUID::randomUUID,
) {
    data class Command(
        val documentId: UUID,
        val expectedVersion: Version,
        val lines: LineProposal,
        /** The verified staff user committing the lines, from authentication. */
        val author: UserId,
        val expectedInvoiceRevision: Int? = null,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, authorship)
    private val declined = DeclinedInquiryPolicy(associations, declines)

    operator fun invoke(command: Command): InquiryFinancialDocument {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.expectedVersion)
            documents.rejectCanonicalQuoteMutation(transaction, current)
            declined.rejectProgression(transaction, current.inquiryId, current.document.id)
            if (documents.isCanonical(transaction, current) && fulfillment.find(transaction, current.inquiryId)?.closed != null) {
                throw CommerceFailure.IllegalTransition("A closed inquiry requires a separate post-close adjustment workflow")
            }
            if (current.document is FinancialDocument.Invoice && documents.isCanonical(transaction, current)) {
                val expected =
                    command.expectedInvoiceRevision
                        ?: throw CommerceFailure.ValidationFailed("Canonical Invoice change orders require expectedInvoiceRevision")
                if (amendments.latest(transaction, current.inquiryId)?.invoiceRevision != expected) {
                    throw CommerceFailure.Conflict("Stale expected Invoice business revision; reload and retry")
                }
            }
            val resolved = command.lines.resolveAgainst(current.document)
            if (documents.isCanonical(transaction, current)) {
                requireRetainedNotes(servicePlans.latest(transaction, current.inquiryId)?.content(), resolved)
            }
            val changed = documents.commitLines(transaction, current, resolved, command.author, now)
            if (current.document is FinancialDocument.Invoice &&
                documents.isCanonical(transaction, current)
            ) {
                amendments.recordAmendment(
                    transaction,
                    newAmendmentId(),
                    current.inquiryId,
                    current.document,
                    changed as FinancialDocument.Invoice,
                    servicePlans.latest(transaction, current.inquiryId),
                    now,
                    command.author,
                    false,
                )
            }
            documents.describeLocked(transaction, current.inquiryId, command.documentId)
        }
    }
}
