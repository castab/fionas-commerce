package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import java.util.UUID

/*
 * Fixture shorthands for specs that arrange ledger state directly, not under review: each acts
 * on whatever version the lineage is at. Application code never does this; it always passes the
 * version its caller reviewed.
 */

fun FinancialLedger.quoteLatest(
    transaction: Transaction,
    id: UUID,
): FinancialDocument.Quote = issueQuote(transaction, id, latest(transaction, id).version)

fun FinancialLedger.invoiceLatest(
    transaction: Transaction,
    id: UUID,
): FinancialDocument.Invoice = issueInvoice(transaction, id, latest(transaction, id).version)

fun FinancialLedger.changeLatest(
    transaction: Transaction,
    id: UUID,
    changes: ChangeOrder,
): FinancialDocument = changeOrder(transaction, id, changes, latest(transaction, id).version)
