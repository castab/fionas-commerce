package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
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
            "Optional reviewed quote composition, exactly as previewed. Absent issues the Estimate unchanged. " +
                "Present requires `reviewToken`.",
    )
    val composition: QuoteCompositionRequest? = null,
    @ApiProperty(
        description = "The preview's reviewToken for `composition`; required with it and forbidden without it.",
        pattern = "^[0-9a-f]{64}$",
    )
    val reviewToken: String? = null,
)

@Serializable
data class ReviseInquiryQuoteProposalRequest(
    @ApiProperty(description = "Exact reviewed current Quote version.") val expectedDocumentVersion: Int,
    @ApiProperty(description = "Exact reviewed active deposit revision.") val expectedDepositRequirementRevision: Int,
    val pricingInputs: CreateInquiryEstimateRequest,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
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
    val principalKind: String,
    @ApiProperty(format = "uuid") val principalId: String,
    @ApiProperty(description = "INITIAL, QUOTE_REVISED, or DEPOSIT_REVISED; never communication delivery.") val issuanceKind: String,
)

@Serializable
data class IssuedInquiryProposalResponse(
    val proposal: InquiryProposalResponse,
    val financial: FinancialDocumentResponse,
    val depositRequirement: CurrentDepositRequirementResponse,
    @ApiProperty(description = "The approved service plan of the published Quote; present only when issued with a composition.")
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
        when (principalId) {
            is UserId -> "USER"
            is ServiceId -> "SERVICE"
        },
        when (val actor = principalId) {
            is UserId -> actor.value.toString()
            is ServiceId -> actor.value.toString()
        },
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
            "USER",
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
    returning(Status.OK, proposalBody to example)
    returningError(
        ErrorCategory.FORBIDDEN,
        "Requires BOTH commerce.financial-document.create and commerce.deposit-requirement.manage. Unsafe cookie requests require a trusted Origin.",
        UNTRUSTED_ORIGIN,
    )
    returningError(ErrorCategory.MALFORMED_REQUEST, "Unreadable inquiry UUID or request shape.", "Malformed request")
    returningError(
        ErrorCategory.NOT_FOUND,
        "Inquiry/canonical lineage or catalog revision does not exist.",
        "Canonical inquiry financial lineage was not found",
    )
    returningError(
        ErrorCategory.CONFLICT,
        "Stale document version, deposit revision or catalog revision. Reload and review. " +
            "Also illegal_transition for an ineligible stage or historical applied payment." + conflictExtra,
        "Stale expected version",
    )
    returningError(
        ErrorCategory.VALIDATION_FAILED,
        "Invalid positive versions, decimal terms, pricing, or a no-op revision. " +
            "Also invariant_violated for shared financial/pricing invariants." + validationExtra,
        "The revised deposit terms produce no change",
    )
    returningError(
        ErrorCategory.INTERNAL_FAILURE,
        "Corrupt canonical proposal/deposit state or unexpected failure; the entire operation rolls back.",
        INTERNAL_FAILURE,
    )
}

private fun AccessControl.proposalAccess() =
    requirePermission(CommercePermissions.FinancialDocumentCreate)
        .then(requirePermission(CommercePermissions.DepositRequirementManage))
        .then(catalogRevisionStaleResponses)

internal fun issueInquiryProposalRoute(
    issue: (IssueInquiryProposal.Command) -> IssuedInquiryProposal,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "proposals" meta {
        operationId = "issueInquiryProposal"
        summary = "Publish the initial inquiry proposal"
        description =
            "Derives the canonical lineage from inquiryId. Without `composition`, atomically issues Estimate to Quote without " +
            "repricing. With a reviewed `composition` and its preview `reviewToken`, re-evaluates it under the canonical " +
            "association lock and requires the identical result (otherwise 409 QUOTE_REVIEW_STALE); appends an " +
            "intermediate Estimate only when charges change, then the Quote, its immutable service plan (servicePlan), " +
            "the deposit approved against that final Quote, and the publication, all in one transaction. A Quote total " +
            "must be positive. Explicitly approves deposit terms against the new Quote and records INITIAL issuance with " +
            "authenticated provenance. The suggested deposit is 20%; terms are required. Issued is not sent: no " +
            "communication, payment or booking follows. Stale attempts conflict. Cache-Control: no-store."
        receiving(initialBody to IssueInquiryProposalRequest(1, defaultTerms))
        proposalErrors(
            proposalExample,
            " With a composition, CATALOG_REVISION_STALE for a stale reviewed catalog revision and QUOTE_REVIEW_STALE when the " +
                "composition no longer produces the reviewed result; preview again (both no-store).",
            " With a composition: " + COMPOSITION_REJECTED,
        )
    } bindContract Method.POST to { id: String, _: String ->
        access.proposalAccess().then(quoteReviewStaleResponses).then { request: Request ->
            val inquiry = inquiryId(id)
            val body = initialBody(request)
            if ((body.composition == null) != (body.reviewToken == null)) {
                throw LensFailure(Invalid(initialBody.metas.single().copy(name = "reviewToken")))
            }
            val composition = body.composition?.domain(initialBody)
            val command =
                validating {
                    IssueInquiryProposal.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        body.terms.domain(),
                        authenticatedPrincipal(request),
                        composition?.let { ReviewedQuoteComposition(it, QuoteReviewToken(body.reviewToken!!)) },
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
        summary = "Reprice and reissue an unpaid inquiry proposal"
        description =
            "Exact current Quote/deposit tokens are required. Reprices the current catalog, rejects no-op charges and negative resulting document totals, appends a same-stage Quote and a replacement deposit approved against that new version even when terms are unchanged, then records QUOTE_REVISED issuance atomically. Supersedes every prior payment-link target. Any historical gross allocation blocks revision, including fully refunded payments. A zero-total Quote cannot publish a positive deposit; the complete revision rolls back. Cache-Control: no-store."
        receiving(
            quoteRevisionBody to
                ReviseInquiryQuoteProposalRequest(
                    2,
                    1,
                    CreateInquiryEstimateRequest(20, 100, false, 120, exampleQuote.pricing!!.selections),
                    defaultTerms,
                ),
        )
        proposalErrors(quoteRevisionExample)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.proposalAccess().then { request: Request ->
            val inquiry = inquiryId(id)
            val body = quoteRevisionBody(request)
            val input = body.pricingInputs
            val command =
                validating {
                    ReviseInquiryQuoteProposal.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        DepositRequirementRevision.of(body.expectedDepositRequirementRevision),
                        pricingInputs(
                            input.catalogRevision,
                            input.guestCount,
                            input.guestCountIsMinimum,
                            input.durationMinutes,
                            input.selections.map {
                                it.category to
                                    it.offerings
                            },
                        ),
                        body.terms.domain(),
                        authenticatedPrincipal(request),
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
            "Exact current Quote/deposit tokens are required. Rejects semantically unchanged terms, keeps the Quote version, appends replacement approved deposit and DEPOSIT_REVISED issuance atomically. Different term forms are meaningful even at equal resolved amounts. Supersedes every prior payment-link target. Any historical gross allocation blocks revision even after refunds. Cache-Control: no-store."
        receiving(depositRevisionBody to ReviseInquiryProposalDepositRequest(2, 1, DepositTermsRequest.Fixed("100.00", "USD")))
        proposalErrors(depositRevisionExample)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.proposalAccess().then { request: Request ->
            val inquiry = inquiryId(id)
            val body = depositRevisionBody(request)
            val command =
                validating {
                    ReviseInquiryProposalDeposit.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        DepositRequirementRevision.of(body.expectedDepositRequirementRevision),
                        body.terms.domain(),
                        authenticatedPrincipal(request),
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(proposalBody of revise(command).toResponse())
        }
    }
