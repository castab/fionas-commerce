package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.financial.FIONAS_DEFAULT_DEPOSIT_TERMS
import io.github.castab.fionas.commerce.financial.InquiryProposal
import io.github.castab.fionas.commerce.financial.IssueInquiryProposal
import io.github.castab.fionas.commerce.financial.IssuedInquiryProposal
import io.github.castab.fionas.commerce.financial.QuoteReviewToken
import io.github.castab.fionas.commerce.financial.ReviewedQuoteComposition
import io.github.castab.fionas.commerce.financial.ReviseInquiryProposalDeposit
import io.github.castab.fionas.commerce.financial.ReviseInquiryQuoteProposal
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure

@Serializable
data class IssueInquiryProposalRequest(
    @ApiProperty(description = "Exact reviewed current Estimate version.") val expectedDocumentVersion: Int,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
    @ApiProperty(
        description =
            "Optional complete final Quote lines, exactly as previewed. Absent issues the Estimate unchanged. Present " +
                "requires `reviewToken`.",
    )
    val lines: List<ProposedLineRequest>? = null,
    @ApiProperty(description = "Optional service plan, exactly as previewed; only together with `lines`.")
    val servicePlan: ServicePlanRequest? = null,
    @ApiProperty(
        description = "The preview's reviewToken for `lines`; required with them and forbidden without them.",
        pattern = "^[0-9a-f]{64}$",
    )
    val reviewToken: String? = null,
)

@Serializable
data class ReviseInquiryQuoteProposalRequest(
    @ApiProperty(description = "Exact reviewed current Quote version.") val expectedDocumentVersion: Int,
    @ApiProperty(description = "Exact reviewed active deposit revision.") val expectedDepositRequirementRevision: Int,
    @ApiProperty(
        description =
            "The complete revised final Quote lines: existing lines by `lineItemId` (carried or overridden), new lines by " +
                "`key`; lines left out are removed. Lines equal to the current Quote's are no change.",
    )
    val lines: List<ProposedLineRequest>,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
    @ApiProperty(description = "Optional service plan approved with the revised Quote.")
    val servicePlan: ServicePlanRequest? = null,
)

@Serializable
data class ReviseInquiryProposalDepositRequest(
    @ApiProperty(description = "Exact reviewed current Quote version.") val expectedDocumentVersion: Int,
    @ApiProperty(description = "Exact reviewed active deposit revision.") val expectedDepositRequirementRevision: Int,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
)

@Serializable
data class InquiryProposalResponse(
    @ApiProperty(format = "uuid") val id: String,
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val documentId: String,
    val documentVersion: Int,
    val depositRequirementRevision: Int,
    @ApiProperty(format = "date-time") val issuedAt: String,
    @ApiProperty(description = "The verified staff user who published the proposal.", format = "uuid") val issuedBy: String,
    @ApiProperty(description = "INITIAL, QUOTE_REVISED, or DEPOSIT_REVISED; never communication delivery.") val issuanceKind: String,
)

@Serializable
data class IssuedInquiryProposalResponse(
    val proposal: InquiryProposalResponse,
    val financial: FinancialDocumentResponse,
    val depositRequirement: CurrentDepositRequirementResponse,
    @ApiProperty(description = "The service plan approved with the published Quote; present only when one was submitted.")
    val servicePlan: ServicePlanResponse? = null,
)

internal fun InquiryProposal.toResponse(): InquiryProposalResponse =
    InquiryProposalResponse(
        id.value.toString(),
        inquiryId.value.toString(),
        documentReference.id.toString(),
        documentReference.version.number,
        depositRequirementRevision.number,
        issuedAt.toString(),
        issuedBy.value.toString(),
        kind.name,
    )

private fun IssuedInquiryProposal.toResponse() =
    IssuedInquiryProposalResponse(
        proposal.toResponse(),
        financial.toResponse(),
        deposit.currentDepositResponse(),
        servicePlan?.toResponse(),
    )

private val proposalBody = jsonBody(IssuedInquiryProposalResponse.serializer())
private val initialBody = jsonBody(IssueInquiryProposalRequest.serializer())
private val quoteRevisionBody = jsonBody(ReviseInquiryQuoteProposalRequest.serializer())
private val depositRevisionBody = jsonBody(ReviseInquiryProposalDepositRequest.serializer())
private val defaultTerms = FIONAS_DEFAULT_DEPOSIT_TERMS.toResponse()
private val proposalExample =
    IssuedInquiryProposalResponse(
        InquiryProposalResponse(
            "34b41196-38b8-4e27-a48d-e2aaf896f570",
            exampleInquiry.id,
            exampleInquiry.lifecycle.documentId,
            2,
            1,
            "2026-10-05T17:00:00Z",
            "580a28a1-7417-480a-9089-8f5f3c25c1cd",
            "INITIAL",
        ),
        exampleEstimate.copy(id = exampleInquiry.lifecycle.documentId, version = 2, previousVersion = 1, stage = "QUOTE"),
        CurrentDepositRequirementResponse.Active(
            exampleInquiry.lifecycle.documentId,
            1,
            createdAt = "2026-10-05T17:00:00Z",
            approvalDocumentVersion = 2,
            terms = defaultTerms,
            requiredAmount = DepositMoneyResponse("136.25", "USD"),
            satisfied = false,
        ),
    )

/** The churro example published: Estimate v2 holds the staff lines, Quote v3 is published with an 80.00 deposit. */
private val composedProposalExample =
    proposalExample.copy(
        proposal = proposalExample.proposal.copy(documentVersion = 3),
        financial =
            proposalExample.financial.copy(
                version = 3,
                previousVersion = 2,
                lines =
                    listOf(
                        FinancialDocumentLine(
                            "8f1d2c3b-4a5e-3f60-9a7b-1c2d3e4f5a6b",
                            "Churro catering service",
                            "Prepared on site",
                            "1",
                            "450.00",
                            "450.00",
                            "0.00",
                            "450.00",
                            "USD",
                        ),
                        FinancialDocumentLine(
                            "2a3b4c5d-6e7f-3081-92a3-b4c5d6e7f809",
                            "Courtesy discount",
                            null,
                            null,
                            "-50.00",
                            "-50.00",
                            "0.00",
                            "-50.00",
                            "USD",
                        ),
                    ),
                subtotal = "400.00",
                total = "400.00",
                reconciliation = DocumentReconciliation("0.00", "0.00", "400.00", "USD"),
            ),
        depositRequirement =
            (proposalExample.depositRequirement as CurrentDepositRequirementResponse.Active).copy(
                approvalDocumentVersion = 3,
                requiredAmount = DepositMoneyResponse("80.00", "USD"),
            ),
        servicePlan = exampleServicePlan,
    )

private val quoteRevisionExample =
    proposalExample.copy(
        proposal =
            proposalExample.proposal.copy(
                id = "079e95d9-4c3c-4b39-8008-778e6538d965",
                documentVersion = 3,
                depositRequirementRevision = 2,
                issuanceKind = "QUOTE_REVISED",
            ),
        financial = exampleQuote.copy(id = proposalExample.financial.id),
        depositRequirement =
            (proposalExample.depositRequirement as CurrentDepositRequirementResponse.Active).copy(
                revision = 2,
                previousRevision = 1,
                approvalDocumentVersion = 3,
                requiredAmount = DepositMoneyResponse("165.00", "USD"),
            ),
    )
private val depositRevisionExample =
    proposalExample.copy(
        proposal =
            proposalExample.proposal.copy(
                id = "a0abfa61-3350-4b8b-87d4-3c3e3fb6db25",
                depositRequirementRevision = 2,
                issuanceKind = "DEPOSIT_REVISED",
            ),
        depositRequirement =
            (proposalExample.depositRequirement as CurrentDepositRequirementResponse.Active).copy(
                revision = 2,
                previousRevision = 1,
                terms = DepositTermsRequest.Fixed("100.00", "USD"),
                requiredAmount = DepositMoneyResponse("100.00", "USD"),
            ),
    )

private fun RouteMetaDsl.proposalErrors(
    example: IssuedInquiryProposalResponse = proposalExample,
    conflictExtra: String = "",
    validationExtra: String = "",
) {
    tags += Tag("Staff proposals", "Atomic publication of an exact canonical Quote and approved deposit pair.")
    principalAuthentication()
    security = staffSessionSecurity
    returning(Status.OK, proposalBody to example)
    returningError(ErrorCategory.FORBIDDEN, STAFF_COMPOSITION_FORBIDDEN, STAFF_USER_REQUIRED)
    returningError(
        ErrorCategory.MALFORMED_REQUEST,
        "Unreadable inquiry or line UUID, a line or note naming both or neither of `lineItemId` and `key`, or request shape.",
        "Malformed request",
    )
    returningError(
        ErrorCategory.NOT_FOUND,
        "Inquiry or canonical lineage does not exist.",
        "Canonical inquiry financial lineage was not found",
    )
    returningError(
        ErrorCategory.CONFLICT,
        "Stale document version or deposit revision; reload and review, never rebased. " +
            "Also illegal_transition for an ineligible stage or historical applied payment." + conflictExtra,
        "Stale expected version",
    )
    returningError(
        ErrorCategory.VALIDATION_FAILED,
        "Invalid positive versions, decimal terms, lines, or a no-op revision. " +
            "Also invariant_violated for shared financial invariants." + validationExtra,
        "The revised deposit terms produce no change",
    )
    returningError(
        ErrorCategory.INTERNAL_FAILURE,
        "Corrupt canonical proposal/deposit state or unexpected failure; the entire operation rolls back.",
        INTERNAL_FAILURE,
    )
}

internal fun issueInquiryProposalRoute(
    issue: (IssueInquiryProposal.Command) -> IssuedInquiryProposal,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "proposals" meta {
        operationId = "issueInquiryProposal"
        summary = "Publish the initial inquiry proposal"
        description =
            "Derives the canonical lineage from inquiryId. Without `lines`, atomically issues the Estimate to a Quote " +
            "unchanged. With the staff user's final `lines` (and optional `servicePlan`) and their preview `reviewToken`, " +
            "resolves them again under the canonical association lock and requires the identical reviewed result " +
            "(otherwise 409 QUOTE_REVIEW_STALE); appends an intermediate Estimate only when the lines differ, then the " +
            "Quote of exactly that version, its immutable service plan, the deposit approved against that final Quote, " +
            "and the publication, all in one transaction. No catalog or pricing policy is consulted. The Quote total must " +
            "be positive. Explicitly approves deposit terms and records INITIAL issuance by the authenticated staff user. " +
            "The suggested deposit is 20%; terms are required. Issued is not sent: no communication, payment or booking " +
            "follows. Stale attempts conflict. Cache-Control: no-store."
        receiving(
            initialBody to
                IssueInquiryProposalRequest(
                    1,
                    defaultTerms,
                    exampleProposedLines,
                    exampleServicePlanRequest,
                    "9f2c4a1b8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b",
                ),
        )
        proposalErrors(
            composedProposalExample,
            " With lines, QUOTE_REVIEW_STALE (no-store) when they no longer produce the reviewed result; preview again.",
            " With lines: " + COMPOSITION_REJECTED,
        )
    } bindContract Method.POST to { id: String, _: String ->
        access.staffComposition().then(quoteReviewStaleResponses).then { request: Request ->
            val inquiry = inquiryId(id)
            val body = initialBody(request)
            if ((body.lines == null) != (body.reviewToken == null)) {
                throw LensFailure(Invalid(initialBody.metas.single().copy(name = "reviewToken")))
            }
            if (body.servicePlan != null && body.lines == null) {
                throw LensFailure(Invalid(initialBody.metas.single().copy(name = "servicePlan")))
            }
            val lineIdentities = body.lines?.identities(initialBody)
            val noteIdentities =
                body.servicePlan
                    ?.lineNotes
                    ?.identities(initialBody)
                    .orEmpty()
            val command =
                validating {
                    IssueInquiryProposal.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        body.terms.domain(),
                        staffUser(request),
                        body.lines?.let { lines ->
                            ReviewedQuoteComposition(
                                lines.domain(lineIdentities!!),
                                body.servicePlan?.domain(noteIdentities),
                                QuoteReviewToken(body.reviewToken!!),
                            )
                        },
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(proposalBody of issue(command).toResponse())
        }
    }

internal fun reviseInquiryQuoteProposalRoute(
    revise: (ReviseInquiryQuoteProposal.Command) -> IssuedInquiryProposal,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "proposals" / "quote-revisions" meta {
        operationId = "reviseInquiryQuoteProposal"
        summary = "Revise the lines of an unpaid inquiry proposal and reissue it"
        description =
            "Exact current Quote/deposit tokens are required. Commits the staff user's complete revised final lines " +
            "(existing lines carried or overridden by `lineItemId`, new lines by `key`, omitted lines removed) as a " +
            "same-stage Quote of exactly the reviewed version, with an optional service plan, a replacement deposit " +
            "approved against that new version even when terms are unchanged, and QUOTE_REVISED issuance, atomically. " +
            "No catalog or pricing policy is consulted. Rejects lines equal to the current Quote's (NO_FINANCIAL_CHANGE; " +
            "use deposit-revisions) and negative or zero totals. Supersedes every prior payment-link target. Any " +
            "historical gross allocation blocks revision, including fully refunded payments. Cache-Control: no-store."
        receiving(
            quoteRevisionBody to
                ReviseInquiryQuoteProposalRequest(2, 1, exampleProposedLines, defaultTerms, exampleServicePlanRequest),
        )
        proposalErrors(quoteRevisionExample, validationExtra = " " + COMPOSITION_REJECTED)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.staffComposition().then { request: Request ->
            val inquiry = inquiryId(id)
            val body = quoteRevisionBody(request)
            val lineIdentities = body.lines.identities(quoteRevisionBody)
            val noteIdentities =
                body.servicePlan
                    ?.lineNotes
                    ?.identities(quoteRevisionBody)
                    .orEmpty()
            val command =
                validating {
                    ReviseInquiryQuoteProposal.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        DepositRequirementRevision.of(body.expectedDepositRequirementRevision),
                        body.lines.domain(lineIdentities),
                        body.terms.domain(),
                        staffUser(request),
                        body.servicePlan?.domain(noteIdentities),
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(proposalBody of revise(command).toResponse())
        }
    }

internal fun reviseInquiryProposalDepositRoute(
    revise: (ReviseInquiryProposalDeposit.Command) -> IssuedInquiryProposal,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "proposals" / "deposit-revisions" meta {
        operationId = "reviseInquiryProposalDeposit"
        summary = "Reissue an unpaid inquiry proposal with new deposit terms"
        description =
            "Exact current Quote/deposit tokens are required. Rejects semantically unchanged terms, keeps the Quote version, " +
            "appends replacement approved deposit and DEPOSIT_REVISED issuance by the staff user atomically. Different term " +
            "forms are meaningful even at equal resolved amounts. Supersedes every prior payment-link target. Any historical " +
            "gross allocation blocks revision even after refunds. Cache-Control: no-store."
        receiving(depositRevisionBody to ReviseInquiryProposalDepositRequest(2, 1, DepositTermsRequest.Fixed("100.00", "USD")))
        proposalErrors(depositRevisionExample)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.staffComposition().then { request: Request ->
            val inquiry = inquiryId(id)
            val body = depositRevisionBody(request)
            val command =
                validating {
                    ReviseInquiryProposalDeposit.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        DepositRequirementRevision.of(body.expectedDepositRequirementRevision),
                        body.terms.domain(),
                        staffUser(request),
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(proposalBody of revise(command).toResponse())
        }
    }
