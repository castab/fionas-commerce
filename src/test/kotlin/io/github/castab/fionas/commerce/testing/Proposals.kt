package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financial.InquiryProposals
import io.github.castab.fionas.commerce.financial.IssueInquiryProposal
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.PreviewInquiryQuoteRevision
import io.github.castab.fionas.commerce.financial.ReviseInquiryQuoteProposal
import io.github.castab.fionas.commerce.http.QuoteRevisionPreviewResponse
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.matchers.shouldBe
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/** Fiona's proposal publication mechanics, as the composition root builds them. */
fun TestApplication.inquiryProposals() =
    InquiryProposals(
        context.financialLedger,
        JdbiInquiryFinancialDocumentRepository(),
        JdbiFinancialDocumentAuthorshipRepository(),
        JdbiInquiryProposalRepository(),
        JdbiInquiryServicePlanRepository(),
        testClock,
    )

/** Initial fixture publication through the same atomic application operation as staff, by the bootstrap administrator. */
fun TestApplication.issueProposal(
    inquiryId: InquiryId,
    amount: String = "50.00",
    version: Int = 1,
) = IssueInquiryProposal(transactor, inquiryProposals())(
    IssueInquiryProposal.Command(
        inquiryId,
        Version.of(version),
        DepositTerms.Fixed(Money(BigDecimal(amount), Currency.getInstance("USD"))),
        adminId,
    ),
)

/** Reviewed fixture identity, obtained before invoking a payment operation. */
fun TestApplication.proposalId(documentId: UUID): InquiryProposalId? =
    transactor.inTransaction { transaction ->
        JdbiInquiryFinancialDocumentRepository().inquiryOf(transaction, documentId)?.let {
            JdbiInquiryProposalRepository().latest(transaction, it)?.id
        }
    }

/** Exercise the production preview before approving a revision fixture. */
fun ReviseInquiryQuoteProposal.Command.reviewed(app: TestApplication): ReviseInquiryQuoteProposal.Command =
    copy(
        reviewToken =
            PreviewInquiryQuoteRevision(
                app.transactor,
                app.context.financialLedger,
                JdbiInquiryFinancialDocumentRepository(),
                JdbiInquiryProposalRepository(),
                JdbiInquiryServicePlanRepository(),
            )(intent).composition.reviewToken,
    )

fun TestApplication.reviseQuoteReviewed(
    inquiryId: String,
    body: String,
): Response {
    val preview = adminPost("/staff/requests/$inquiryId/quote-revision-preview", body)
    preview.status shouldBe Status.OK
    val token = CommerceJson.asA(preview.bodyString(), QuoteRevisionPreviewResponse.serializer()).quote.reviewToken
    return adminPost("/staff/requests/$inquiryId/proposals/quote-revisions", body.dropLast(1) + ",\"reviewToken\":\"$token\"}")
}
