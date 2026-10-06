package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmInline
value class InquiryProposalId(
    val value: UUID,
)

enum class ProposalIssuanceKind { INITIAL, QUOTE_REVISED, DEPOSIT_REVISED }

/** Staff publication of an exact immutable Quote/deposit pair, not evidence of message delivery. */
data class InquiryProposal(
    val id: InquiryProposalId,
    val inquiryId: InquiryId,
    val documentReference: FinancialDocumentReference,
    val depositRequirementRevision: DepositRequirementRevision,
    val issuedAt: Instant,
    val principalId: PrincipalId,
    val kind: ProposalIssuanceKind,
) {
    init {
        require(documentReference.version.number >= 2) { "A canonical proposal requires an issued Quote" }
    }
}

/** Suggested application policy only: issuance always requires explicit approved terms. */
val FIONAS_DEFAULT_DEPOSIT_TERMS: DepositTerms = DepositTerms.Percentage(BigDecimal("20"))

data class IssuedInquiryProposal(
    val proposal: InquiryProposal,
    val financial: InquiryFinancialDocument,
    val deposit: FinancialLineageView,
)

/** A previous issuance becomes non-payable automatically when either authoritative identity changes. */
internal fun InquiryProposal.isCurrentPayable(
    latest: InquiryProposal?,
    view: FinancialLineageView,
): Boolean {
    val active = view.depositRequirement?.requirement as? DepositRequirement.Active ?: return false
    return id == latest?.id &&
        view.latestVersion.document is FinancialDocument.Quote &&
        documentReference == view.latestVersion.document.reference &&
        active.approvalReference == documentReference &&
        active.revision == depositRequirementRevision
}

/** Fail loudly for misleading canonical Quote projections; Invoice retains historical publication context. */
internal fun requireCoherentProposal(
    inquiryId: InquiryId,
    proposal: InquiryProposal?,
    view: FinancialLineageView,
) {
    val document = view.latestVersion.document
    if (document is FinancialDocument.Estimate) {
        check(proposal == null && view.depositRequirement == null) { "Unissued Estimate has proposal or deposit history" }
        return
    }
    checkNotNull(proposal) { "Canonical financial lineage has no proposal issuance" }
    check(proposal.inquiryId == inquiryId && proposal.documentReference.id == document.id) { "Proposal ownership disagrees" }
    if (document is FinancialDocument.Quote) {
        check(proposal.isCurrentPayable(proposal, view)) { "Canonical Quote and proposal deposit identities disagree" }
    } else {
        check(proposal.documentReference.version.number < document.version.number) { "Invoice must follow its issued Quote" }
    }
}
