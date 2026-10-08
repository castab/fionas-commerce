package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financial.InquiryProposals
import io.github.castab.fionas.commerce.financial.InquiryQuoteComposition
import io.github.castab.fionas.commerce.financial.IssueInquiryProposal
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryServicePlanRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/** Fiona's pricing over the application's transaction-bound current catalog read, as the composition root builds it. */
fun TestApplication.fionasPricing() =
    FionasPricing(FionasOfferingsEngine(FIONAS_PRICING_POLICY), context.offeringsSnapshotRepository::retrieveLatestVersion)

/** Initial fixture publication through the same atomic application operation as staff. */
fun TestApplication.issueProposal(
    inquiryId: InquiryId,
    amount: String = "50.00",
    version: Int = 1,
) = IssueInquiryProposal(
    transactor,
    InquiryProposals(
        context.financialLedger,
        JdbiInquiryFinancialDocumentRepository(),
        JdbiFinancialDocumentPricingRepository(),
        JdbiInquiryProposalRepository(),
        JdbiInquiryServicePlanRepository(),
        InquiryQuoteComposition(JdbiInquiryRepository(), JdbiFinancialDocumentPricingRepository(), fionasPricing()),
        fionasPricing(),
        testClock,
    ),
)(
    IssueInquiryProposal.Command(
        inquiryId,
        Version.of(version),
        DepositTerms.Fixed(Money(BigDecimal(amount), Currency.getInstance("USD"))),
        UserId(UUID.randomUUID()),
    ),
)

/** Reviewed fixture identity, obtained before invoking a payment operation. */
fun TestApplication.proposalId(documentId: UUID): InquiryProposalId? =
    transactor.inTransaction { transaction ->
        JdbiInquiryFinancialDocumentRepository().inquiryOf(transaction, documentId)?.let {
            JdbiInquiryProposalRepository().latest(transaction, it)?.id
        }
    }
