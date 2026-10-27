package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant

enum class ServicePlanAction { RETAIN, REPLACE, CLEAR }

data class ServicePlanChange(
    val action: ServicePlanAction,
    val replacement: ProposedServicePlan? = null,
) {
    init {
        require((action == ServicePlanAction.REPLACE) == (replacement != null)) {
            "REPLACE requires a service plan; RETAIN and CLEAR forbid one"
        }
    }

    internal fun resolve(
        current: InquiryServicePlan?,
        lines: ResolvedLineProposal,
    ): ComposedServicePlan? =
        when (action) {
            ServicePlanAction.CLEAR -> null
            ServicePlanAction.REPLACE -> replacement!!.let { ComposedServicePlan(it.service, it.resolveNotes(lines)) }
            ServicePlanAction.RETAIN -> current?.content()?.also { requireRetainedNotes(it, lines) }
        }
}

internal fun InquiryServicePlan.content(): ComposedServicePlan? = service?.let { ComposedServicePlan(it, lineNotes) }

internal fun requireRetainedNotes(
    plan: ComposedServicePlan?,
    lines: ResolvedLineProposal,
) {
    if (plan != null && !lines.lines.map { it.line.id }.containsAll(plan.lineNotes.map { it.lineItemId })) {
        throw lineProposalFailure(
            listOf(SERVICE_PLAN_LINE_NOT_FOUND),
            "A retained service plan notes a removed line; replace or clear the plan",
        )
    }
}

/** Append only a meaningful commitment change. Financial-only amendments retain the earlier approval. */
internal fun InquiryServicePlanRepository.approveChange(
    transaction: Transaction,
    inquiryId: InquiryId,
    current: InquiryServicePlan?,
    proposed: ComposedServicePlan?,
    document: FinancialDocument,
    reviewed: Version,
    at: Instant,
    by: UserId,
): InquiryServicePlan? {
    if (current?.content() == proposed) return current
    return InquiryServicePlan(
        inquiryId,
        document.reference,
        reviewed,
        proposed?.service,
        proposed?.lineNotes.orEmpty(),
        at,
        by,
        (current?.revision ?: 0) + 1,
    ).also { insert(transaction, it) }
}
