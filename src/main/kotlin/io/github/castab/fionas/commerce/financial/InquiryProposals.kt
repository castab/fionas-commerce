package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Staff-committed final lines, their optional service plan, and the identity of the preview its approver reviewed. */
data class ReviewedQuoteComposition(
    val lines: LineProposal,
    val servicePlan: ProposedServicePlan?,
    val reviewToken: QuoteReviewToken,
)

/**
 * Transaction-taking Fiona publication mechanics. The three application operations own their
 * boundaries; every publication is authored by a verified staff user.
 */
class InquiryProposals(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    private val proposals: InquiryProposalRepository,
    private val servicePlans: InquiryServicePlanRepository,
    private val clock: Clock,
    private val newId: () -> InquiryProposalId = { InquiryProposalId(UUID.randomUUID()) },
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    /**
     * Publishes the initial proposal. Without a composition the Estimate is issued unchanged.
     * With one, under the same association lock and in the same transaction: the committed lines
     * are composed again from the authoritative Estimate and must reproduce the reviewed result
     * exactly; an intermediate Estimate is appended only when the lines change; the Quote, its
     * optional immutable service plan, the deposit approved against that final Quote and the
     * publication follow. No catalog or pricing policy is consulted.
     */
    internal fun issue(
        transaction: Transaction,
        command: IssueInquiryProposal.Command,
    ): IssuedInquiryProposal {
        val current = canonical(transaction, command.inquiryId, command.expectedDocumentVersion)
        val estimate =
            current.document as? FinancialDocument.Estimate
                ?: throw CommerceFailure.IllegalTransition("Initial proposal requires an Estimate")
        val view = ledger.financialLineages(transaction, listOf(estimate.id)).single()
        requireCoherentProposal(command.inquiryId, proposals.latest(transaction, command.inquiryId), view)
        val reviewed = command.composition
        if (reviewed == null) {
            val quote = documents.quote(transaction, current)
            documents.approveDeposit(transaction, quote, command.terms, null)
            return publish(transaction, command.inquiryId, quote, command.issuedBy, ProposalIssuanceKind.INITIAL)
        }
        val composed = QuoteComposer.compose(command.inquiryId, estimate, reviewed.lines, reviewed.servicePlan, command.terms)
        if (composed.reviewToken != reviewed.reviewToken) {
            throw CommerceFailure.Conflict(QUOTE_REVIEW_STALE_MESSAGE, QuoteReviewStale())
        }
        val now = now()
        val quote = documents.composedQuote(transaction, current, composed, command.issuedBy, now)
        val plan = composed.approve(transaction, quote, estimate.version, now, command.issuedBy)
        documents.approveDeposit(transaction, quote, command.terms, null)
        return publish(transaction, command.inquiryId, quote, command.issuedBy, ProposalIssuanceKind.INITIAL).copy(servicePlan = plan)
    }

    /**
     * Revises an unpaid published Quote from staff-committed final lines: a same-stage Quote of
     * exactly the reviewed version, its optional service plan, a replacement deposit approved
     * against that new version (even when the terms are unchanged), and the QUOTE_REVISED
     * publication, atomically. Lines equal to the current Quote are no change.
     */
    internal fun reviseQuote(
        transaction: Transaction,
        command: ReviseInquiryQuoteProposal.Command,
    ): IssuedInquiryProposal {
        val current = canonical(transaction, command.inquiryId, command.expectedDocumentVersion)
        reviewed(transaction, current, command.expectedDepositRequirementRevision)
        val composed = QuoteComposer.compose(command.inquiryId, current.document, command.lines, command.servicePlan, command.terms)
        val now = now()
        val quote = documents.commitLines(transaction, current, composed.lines, command.issuedBy, now) as FinancialDocument.Quote
        check(quote == composed.quote) { "The persisted Quote differs from the composed Quote" }
        val plan = composed.approve(transaction, quote, current.document.version, now, command.issuedBy)
        documents.approveDeposit(transaction, quote, command.terms, command.expectedDepositRequirementRevision)
        return publish(transaction, command.inquiryId, quote, command.issuedBy, ProposalIssuanceKind.QUOTE_REVISED)
            .copy(servicePlan = plan)
    }

    internal fun reviseDeposit(
        transaction: Transaction,
        command: ReviseInquiryProposalDeposit.Command,
    ): IssuedInquiryProposal {
        val current = canonical(transaction, command.inquiryId, command.expectedDocumentVersion)
        val active = reviewed(transaction, current, command.expectedDepositRequirementRevision)
        if (sameTerms(active.terms, command.terms)) throw CommerceFailure.ValidationFailed("The revised deposit terms produce no change")
        documents.approveDeposit(transaction, current.document, command.terms, command.expectedDepositRequirementRevision)
        return publish(transaction, command.inquiryId, current.document, command.issuedBy, ProposalIssuanceKind.DEPOSIT_REVISED)
    }

    /** Records the composed service plan, if any, as approved for exactly [quote]. */
    private fun ComposedQuote.approve(
        transaction: Transaction,
        quote: FinancialDocument.Quote,
        reviewedVersion: Version,
        approvedAt: Instant,
        approvedBy: UserId,
    ): InquiryServicePlan? =
        servicePlan
            ?.let { InquiryServicePlan(inquiryId, quote.reference, reviewedVersion, it.service, it.lineNotes, approvedAt, approvedBy) }
            ?.also { check(it.describes(quote)) { "The service plan must describe the persisted Quote" } }
            ?.also { servicePlans.insert(transaction, it) }

    private fun canonical(
        transaction: Transaction,
        inquiryId: InquiryId,
        version: Version,
    ): FionaFinancialDocuments.Current {
        val id =
            associations.initialEstimateOf(transaction, inquiryId)
                ?: throw CommerceFailure.NotFound("Canonical inquiry financial lineage was not found")
        return documents.expectLatest(transaction, id, version)
    }

    private fun reviewed(
        transaction: Transaction,
        current: FionaFinancialDocuments.Current,
        revision: DepositRequirementRevision,
    ): DepositRequirement.Active {
        if (current.document !is FinancialDocument.Quote) throw CommerceFailure.IllegalTransition("Proposal revision requires a Quote")
        val view = ledger.financialLineages(transaction, listOf(current.document.id)).single()
        requireCoherentProposal(current.inquiryId, proposals.latest(transaction, current.inquiryId), view)
        val active = view.depositRequirement!!.requirement as DepositRequirement.Active
        if (active.revision != revision) {
            throw CommerceFailure.Conflict("Deposit requirement has a stale expected revision; reload and retry")
        }
        if (view.reconciliation.grossAllocated.amount
                .signum() > 0
        ) {
            throw CommerceFailure.IllegalTransition("Historical applied payment prevents ordinary proposal revision")
        }
        return active
    }

    private fun publish(
        transaction: Transaction,
        inquiryId: InquiryId,
        quote: FinancialDocument,
        issuedBy: UserId,
        kind: ProposalIssuanceKind,
    ): IssuedInquiryProposal {
        val view: FinancialLineageView = ledger.financialLineages(transaction, listOf(quote.id)).single()
        val requirement = view.depositRequirement!!.requirement as DepositRequirement.Active
        val proposal = InquiryProposal(newId(), inquiryId, quote.reference, requirement.revision, now(), issuedBy, kind)
        proposals.append(transaction, proposal)
        requireCoherentProposal(inquiryId, proposal, view)
        return IssuedInquiryProposal(proposal, documents.describeLocked(transaction, inquiryId, view), view)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

private fun sameTerms(
    first: DepositTerms,
    second: DepositTerms,
): Boolean =
    when (first) {
        is DepositTerms.Percentage -> second is DepositTerms.Percentage && first.percentage.compareTo(second.percentage) == 0
        is DepositTerms.Fixed ->
            second is DepositTerms.Fixed &&
                first.amount.currency == second.amount.currency &&
                first.amount.amount.compareTo(second.amount.amount) == 0
    }
