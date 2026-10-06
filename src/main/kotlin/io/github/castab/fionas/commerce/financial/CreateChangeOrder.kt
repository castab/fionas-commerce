package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Repricing uses the current catalog only; inputs from an older revision fail with a conflict.
 * Existing document lines are immutable facts and are never reconstructed from old catalog inputs.
 *
 * Fiona's existing staff replacement action: materializes new lines from explicitly supplied
 * commercial inputs and replaces the prior snapshot's concrete line set. It never reads old
 * pricing inputs to reconstruct old lines. Appends a same-stage successor, whether an estimate, a
 * RELATED quote, or an invoice. Canonical Quotes require atomic proposal revision.
 *
 * In one runtime transaction: the lineage must belong to an inquiry, its latest version must
 * be the one the caller acted on ([CommerceFailure.Conflict] otherwise), the catalog revision
 * the inputs name, and no other, is read and priced by Fiona's [pricing], the new lines
 * become a commerce-domain [ChangeOrder] ([repricing]) that the runtime applies, and the
 * revised inputs are recorded as the new version's pricing source.
 */
class CreateChangeOrder(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
    private val pricing: FionasPricing,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    operator fun invoke(
        documentId: UUID,
        expectedVersion: Version,
        inputs: FionasPricingInputs,
    ): InquiryFinancialDocument =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, documentId, expectedVersion)
            documents.rejectCanonicalQuoteMutation(transaction, current)
            documents.reprice(transaction, current, inputs, pricing)
            documents.describeLocked(transaction, current.inquiryId, documentId)
        }
}

/**
 * The change order that replaces the [current] commercial lines with the [revised] ones:
 * every current line is removed, then every revised line is added in its evaluated order.
 * The domain applies it atomically and validates only the resulting document, and the
 * current snapshot is left untouched.
 *
 * Repricing replaces the whole line set on purpose. Fiona's engine gives every evaluation new
 * line ids, and matching lines by description or position would invent a line identity that
 * does not exist.
 *
 * Fails with [CommerceFailure.ValidationFailed] when the revised lines charge exactly what
 * the current ones do (descriptions, quantities, prices, currency, tax, and order, ignoring
 * ids): that is no financial change, and needs no successor.
 */
internal fun repricing(
    current: List<LineItem>,
    revised: List<LineItem>,
): ChangeOrder {
    if (current.map(::chargeOf) == revised.map(::chargeOf)) {
        throw CommerceFailure.ValidationFailed("The revised pricing produces no financial change")
    }
    return ChangeOrder(
        current.map { ChangeOrder.Change.RemoveLineItem(it.id) } + revised.map { ChangeOrder.Change.AddLineItem(it) },
    )
}

/** What a line charges, without its id; amounts compare numerically, whatever their scale. */
private data class Charge(
    val description: String,
    val subDescription: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal,
    val tax: BigDecimal,
    val currency: Currency,
)

private fun chargeOf(line: LineItem) =
    Charge(
        line.description,
        line.subDescription,
        line.quantity?.stripTrailingZeros(),
        line.price.amount.stripTrailingZeros(),
        line.taxAmount.amount.stripTrailingZeros(),
        line.currency,
    )
