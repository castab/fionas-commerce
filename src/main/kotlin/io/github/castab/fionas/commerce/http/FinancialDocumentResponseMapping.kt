package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.PricedSnapshot
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

internal fun InquiryFinancialDocument.toResponse() = latest.toResponse(inquiryId, reconciliation)

internal fun PricedSnapshot.toResponse(
    inquiryId: InquiryId,
    reconciliation: FinancialDocumentReconciliation?,
) = FinancialDocumentResponse(
    id = document.id.toString(),
    version = document.version.number,
    createdAt = createdAt.toString(),
    previousVersion = document.previousVersion?.number,
    stage =
        when (document) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        },
    inquiryId = inquiryId.value.toString(),
    pricing = pricing?.toResponse(),
    lines = document.lineItems.map { it.toResponse() },
    subtotal = document.subtotal.decimal(),
    taxAmount = document.taxAmount.decimal(),
    total = document.total.decimal(),
    currency = document.currency.currencyCode,
    reconciliation = reconciliation?.toResponse(),
)

private fun FionasPricingInputs.toResponse() =
    DocumentPricing(
        catalogRevision = catalogRevision.number,
        guestCount = context.guestCount,
        guestCountIsMinimum = context.guestCountIsMinimum,
        durationMinutes = Math.toIntExact(context.duration.toMinutes()),
        selections = selections.categories.map { block -> PricingSelection(block.category.value, block.offerings.map { it.value }) },
    )

private fun LineItem.toResponse() =
    FinancialDocumentLine(
        id = id.toString(),
        description = description,
        subDescription = subDescription,
        quantity = quantity?.stripTrailingZeros()?.toPlainString(),
        unitPrice = price.decimal(),
        subtotal = subtotal.decimal(),
        taxAmount = taxAmount.decimal(),
        total = total.decimal(),
        currency = currency.currencyCode,
    )

internal fun FinancialDocumentReconciliation.toResponse() =
    DocumentReconciliation(
        grossAllocated = grossAllocated.decimal(),
        netApplied = netApplied.decimal(),
        balance = balance.decimal(),
        currency = currency.currencyCode,
    )
