package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.offering.FionasPricing
import java.util.UUID

/** A composition and the identity of the preview result its approver reviewed. */
data class ReviewedQuoteComposition(
    val composition: QuoteComposition,
    val reviewToken: QuoteReviewToken,
)

/** Typed conflict context: the composed result differs from the one reviewed. HTTP gives it a stable code. */
class QuoteReviewStale : RuntimeException()

const val QUOTE_REVIEW_STALE_MESSAGE = "The quote changed since it was reviewed; preview it again and review the result"

/**
 * Loads the authoritative basis of a quote composition in the caller's transaction and hands it
 * to the one pure [QuoteComposer], for both the write-free preview and the atomic issuance.
 *
 * The basis is the reviewed canonical Estimate, the effective configuration that produced its
 * lines, and the current catalog snapshot, observed once. An inquiry-materialized Estimate v1
 * was priced from the inquiry's requested inputs; any later Estimate version was repriced by
 * staff, whose inputs its pricing source records. Neither is ever re-read to rebuild a line.
 */
class InquiryQuoteComposition(
    private val inquiries: InquiryRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
    private val pricing: FionasPricing,
    newLineId: () -> UUID = UUID::randomUUID,
) {
    private val composer = QuoteComposer(pricing, newLineId)

    internal fun compose(
        transaction: Transaction,
        inquiryId: InquiryId,
        estimate: FinancialDocument.Estimate,
        composition: QuoteComposition,
        terms: DepositTerms,
    ): ComposedQuote {
        val requested =
            checkNotNull(inquiries.findRequested(transaction, inquiryId)) { "The inquiry of a canonical lineage is missing" }
        val effective =
            pricingSources.find(transaction, estimate.reference)
                ?: requested.pricingInputs.takeIf { estimate.version == Version.INITIAL }
                ?: error("Canonical Estimate ${estimate.reference} has no effective service configuration")
        val basis = QuoteCompositionBasis(inquiryId, estimate, effective, pricing.currentSnapshot(transaction))
        return composer.compose(basis, composition, terms)
    }
}
