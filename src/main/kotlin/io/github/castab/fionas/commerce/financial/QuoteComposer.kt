package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

/** The service plan a composition would approve: the commitment and its notes resolved to final ledger line ids. */
data class ComposedServicePlan(
    val service: ServiceCommitment,
    val lineNotes: List<ServicePlanLineNote>,
)

/**
 * The deterministic result of composing a canonical Quote from staff-committed final lines:
 * the resolved lines with their ledger ids and origins, the change order (absent when the lines
 * are exactly the reviewed ones), the domain Quote candidate whose totals commerce-domain
 * derives, the deposit the approved terms resolve to against that exact Quote, the service plan
 * that would be approved with it, and [reviewToken], the identity of everything a reviewer saw.
 */
data class ComposedQuote(
    val inquiryId: InquiryId,
    val reviewed: FinancialDocument,
    val lines: ResolvedLineProposal,
    val quote: FinancialDocument.Quote,
    val terms: DepositTerms,
    val requiredDeposit: Money,
    val servicePlan: ComposedServicePlan?,
    val reviewToken: QuoteReviewToken,
) {
    /** Whether a same-stage successor precedes (or, for a revision, is) the Quote. */
    val financialChange: Boolean get() = lines.changes != null
}

/** A preview's identity of its reviewed result; approval recomputes and compares it. */
@JvmInline
value class QuoteReviewToken(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "A quote review token is 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Typed conflict context: the composed result differs from the one reviewed. HTTP gives it a stable code. */
class QuoteReviewStale : RuntimeException()

const val QUOTE_REVIEW_STALE_MESSAGE = "The quote changed since it was reviewed; preview it again and review the result"

/** A Quote with a positive deposit needs a positive total. */
const val QUOTE_TOTAL_NOT_POSITIVE = "QUOTE_TOTAL_NOT_POSITIVE"

/**
 * Fiona's one Quote composition core, shared by the write-free preview, the atomic initial
 * publication, and Quote revision, so all derive identical lines, totals, deposit and review
 * identity. Pure and deterministic: it reads and writes nothing, consults no catalog or pricing
 * policy, and generates nothing random (new line ids derive from their keys).
 *
 * From an Estimate it composes the Estimate's Quote successor (after a same-stage revision when
 * the lines change). From a Quote it composes the same-stage revised Quote, which must change
 * the lines.
 */
internal object QuoteComposer {
    fun compose(
        inquiryId: InquiryId,
        reviewed: FinancialDocument,
        proposal: LineProposal,
        servicePlan: ProposedServicePlan?,
        terms: DepositTerms,
    ): ComposedQuote {
        val resolved = proposal.resolveAgainst(reviewed)
        val quote =
            when (reviewed) {
                is FinancialDocument.Estimate -> {
                    val successor = resolved.changes?.let { validateChangeOrder(reviewed, it) } ?: reviewed
                    (successor as FinancialDocument.Estimate).toQuote()
                }
                is FinancialDocument.Quote -> {
                    val changes =
                        resolved.changes
                            ?: throw lineProposalFailure(
                                listOf(LineProposalViolations.NO_FINANCIAL_CHANGE),
                                "The proposed lines are exactly the current Quote's; revise only the deposit instead",
                            )
                    validateChangeOrder(reviewed, changes) as FinancialDocument.Quote
                }
                is FinancialDocument.Invoice -> error("A canonical Quote is never composed from an Invoice")
            }
        if (quote.total.amount.signum() <= 0) {
            throw lineProposalFailure(
                listOf(QUOTE_TOTAL_NOT_POSITIVE),
                "A canonical Quote requires a positive total, which its positive deposit cannot exceed",
            )
        }
        if (terms is DepositTerms.Fixed) paymentMoney(terms.amount.amount, terms.amount.currency)
        val required = validating { terms.resolve(quote) }
        val plan = servicePlan?.let { ComposedServicePlan(it.service, it.resolveNotes(resolved)) }
        val token = reviewToken(inquiryId, reviewed, resolved, quote, terms, required, plan)
        return ComposedQuote(inquiryId, reviewed, resolved, quote, terms, required, plan, token)
    }

    private fun reviewToken(
        inquiryId: InquiryId,
        reviewed: FinancialDocument,
        resolved: ResolvedLineProposal,
        quote: FinancialDocument.Quote,
        terms: DepositTerms,
        required: Money,
        plan: ComposedServicePlan?,
    ): QuoteReviewToken {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(2)
            output.text(inquiryId.value.toString())
            output.text(reviewed.id.toString())
            output.writeInt(reviewed.version.number)
            output.text(reviewed.javaClass.simpleName)
            output.writeBoolean(resolved.changes != null)
            output.writeInt(resolved.lines.size)
            resolved.lines.forEach { line ->
                output.text(line.line.id.toString())
                output.text(line.origin.name)
                output.optional(line.key?.value)
                output.line(line.line)
            }
            output.text(quote.total.amount.decimalText())
            output.text(quote.currency.currencyCode)
            when (terms) {
                is DepositTerms.Fixed -> {
                    output.text("FIXED")
                    output.text(terms.amount.amount.decimalText())
                    output.text(terms.amount.currency.currencyCode)
                }
                is DepositTerms.Percentage -> {
                    output.text("PERCENTAGE")
                    output.text(terms.percentage.decimalText())
                }
            }
            output.text(required.amount.decimalText())
            output.text(required.currency.currencyCode)
            output.writeBoolean(plan != null)
            plan?.let {
                output.text(it.service.description)
                output.optional(it.service.guestCount?.toString())
                output.optional(it.service.durationMinutes?.toString())
                output.writeInt(it.service.items.size)
                it.service.items.forEach(output::text)
                output.writeInt(it.lineNotes.size)
                it.lineNotes.forEach { note ->
                    output.text(note.lineItemId.toString())
                    output.text(note.note)
                }
            }
        }
        return QuoteReviewToken(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())))
    }
}

private fun BigDecimal.decimalText(): String = stripTrailingZeros().toPlainString()

private fun DataOutputStream.line(line: LineItem) {
    text(line.description)
    optional(line.subDescription)
    optional(line.quantity?.decimalText())
    text(line.price.amount.decimalText())
    text(line.taxAmount.amount.decimalText())
    text(line.currency.currencyCode)
}

private fun DataOutputStream.optional(value: String?) {
    writeBoolean(value != null)
    value?.let { text(it) }
}

private fun DataOutputStream.text(value: String) {
    writeInt(value.length)
    writeChars(value)
}
