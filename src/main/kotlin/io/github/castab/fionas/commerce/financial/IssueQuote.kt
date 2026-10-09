package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Issues the latest estimate of a RELATED Fiona lineage as a quote, without repricing: the quote is
 * a new immutable snapshot with the same concrete lines. Fiona carries the
 * line authorship forward when present.
 *
 * In one runtime transaction: the lineage must belong to an inquiry, its latest version must
 * be the one the caller acted on ([CommerceFailure.Conflict] otherwise), the runtime appends
 * the quote (`CommerceFailure.IllegalTransition` when the latest snapshot is not an
 * estimate), and the line authorship is carried to the new version.
 */
class IssueQuote(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(
        documentId: UUID,
        expectedVersion: Version,
    ): InquiryFinancialDocument =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, documentId, expectedVersion)
            if (documents.isCanonical(transaction, current)) {
                throw CommerceFailure.IllegalTransition("Canonical Quote issuance requires POST /staff/requests/{inquiryId}/proposals")
            }
            documents.quote(transaction, current)
            documents.describeLocked(transaction, current.inquiryId, documentId)
        }
}
