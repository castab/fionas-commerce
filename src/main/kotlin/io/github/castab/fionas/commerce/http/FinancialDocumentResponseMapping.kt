package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.fionas.commerce.financial.DocumentSnapshot
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.inquiry.InquiryId

internal fun InquiryFinancialDocument.toResponse() = latest.toResponse(inquiryId, reconciliation)

internal fun DocumentSnapshot.toResponse(
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
    linesAuthoredBy = authorship?.toResponse(),
    lines = document.lineItems.map { it.toResponse() },
    subtotal = document.subtotal.decimal(),
    taxAmount = document.taxAmount.decimal(),
    total = document.total.decimal(),
    currency = document.currency.currencyCode,
    reconciliation = reconciliation?.toResponse(),
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
