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
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricing
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Transaction-taking Fiona publication mechanics. The three application operations own their boundaries. */
class InquiryProposals(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
    private val proposals: InquiryProposalRepository,
    private val servicePlans: InquiryServicePlanRepository,
    private val compositions: InquiryQuoteComposition,
    private val pricing: FionasPricing,
    private val clock: Clock,
    private val newId: () -> InquiryProposalId = { InquiryProposalId(UUID.randomUUID()) },
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    /**
     * Publishes the initial proposal. Without a composition the Estimate is issued unchanged.
     * With one, under the same association lock and in the same transaction: the composition is
     * evaluated again from authoritative state and must reproduce the reviewed result exactly;
     * an intermediate Estimate is appended only when charges change; the Quote, its immutable
     * service plan, the deposit approved against that final Quote and the publication follow.
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
            return publish(transaction, command.inquiryId, quote, command.principalId, ProposalIssuanceKind.INITIAL)
        }
        val composed = compositions.compose(transaction, command.inquiryId, estimate, reviewed.composition, command.terms)
        if (composed.reviewToken != reviewed.reviewToken) {
            throw CommerceFailure.Conflict(QUOTE_REVIEW_STALE_MESSAGE, QuoteReviewStale())
        }
        val quote = documents.composedQuote(transaction, current, composed)
        val approvedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val plan = composed.servicePlan(quote, approvedAt, command.principalId)
        servicePlans.insert(transaction, plan)
        documents.approveDeposit(transaction, quote, command.terms, null)
        return publish(transaction, command.inquiryId, quote, command.principalId, ProposalIssuanceKind.INITIAL).copy(servicePlan = plan)
    }

    internal fun reviseQuote(
        transaction: Transaction,
        command: ReviseInquiryQuoteProposal.Command,
    ): IssuedInquiryProposal {
        val current = canonical(transaction, command.inquiryId, command.expectedDocumentVersion)
        reviewed(transaction, current, command.expectedDepositRequirementRevision)
        val quote = documents.reprice(transaction, current, command.pricingInputs, pricing)
        documents.approveDeposit(transaction, quote, command.terms, command.expectedDepositRequirementRevision)
        return publish(transaction, command.inquiryId, quote, command.principalId, ProposalIssuanceKind.QUOTE_REVISED)
    }

    internal fun reviseDeposit(
        transaction: Transaction,
        command: ReviseInquiryProposalDeposit.Command,
    ): IssuedInquiryProposal {
        val current = canonical(transaction, command.inquiryId, command.expectedDocumentVersion)
        val active = reviewed(transaction, current, command.expectedDepositRequirementRevision)
        if (sameTerms(active.terms, command.terms)) throw CommerceFailure.ValidationFailed("The revised deposit terms produce no change")
        documents.approveDeposit(transaction, current.document, command.terms, command.expectedDepositRequirementRevision)
        return publish(transaction, command.inquiryId, current.document, command.principalId, ProposalIssuanceKind.DEPOSIT_REVISED)
    }

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
        principal: PrincipalId,
        kind: ProposalIssuanceKind,
    ): IssuedInquiryProposal {
        val view: FinancialLineageView = ledger.financialLineages(transaction, listOf(quote.id)).single()
        val requirement = view.depositRequirement!!.requirement as DepositRequirement.Active
        val proposal =
            InquiryProposal(
                newId(),
                inquiryId,
                quote.reference,
                requirement.revision,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                principal,
                kind,
            )
        proposals.append(transaction, proposal)
        requireCoherentProposal(inquiryId, proposal, view)
        return IssuedInquiryProposal(proposal, documents.describeLocked(transaction, inquiryId, view), view)
    }
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
