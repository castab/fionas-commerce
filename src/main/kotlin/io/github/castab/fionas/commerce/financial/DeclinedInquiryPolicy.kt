package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryDeclineRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/**
 * A DECLINED inquiry admits no further commercial progression on any lineage it owns: no new
 * lineage, change order, Quote or Invoice issuance, proposal or deposit approval. Settlement of
 * what already exists (payments, refunds, deposit withdrawal) and every read stay available.
 *
 * Decline serializes on the inquiry's canonical association row. So does this check: it holds
 * that row (a canonical mutation already does; a RELATED one locks it after its own lineage row)
 * before it reads the decline, so in READ COMMITTED a contender observes a committed decline, and
 * a decline waiting behind a progression observes what that progression committed. Callers invoke
 * it after their own lineage lock and before any ledger write. Nothing locks a RELATED row after
 * the canonical one, so the order cannot deadlock.
 */
internal class DeclinedInquiryPolicy(
    private val associations: InquiryFinancialDocumentRepository,
    private val declines: InquiryDeclineRepository,
) {
    /** [locked] is the lineage whose association the caller already holds; the canonical one is not locked twice. */
    fun rejectProgression(
        transaction: Transaction,
        inquiryId: InquiryId,
        locked: UUID?,
    ) {
        associations.initialEstimateOf(transaction, inquiryId)?.takeIf { it != locked }?.let { canonical ->
            check(associations.lockInquiryOf(transaction, canonical) == inquiryId) { "Canonical inquiry ownership changed" }
        }
        if (declines.find(transaction, inquiryId) != null) throw declined()
    }

    companion object {
        fun declined() = CommerceFailure.IllegalTransition(DECLINED_INQUIRY_MESSAGE)
    }
}

internal const val DECLINED_INQUIRY_MESSAGE = "The inquiry was declined; it admits no further quotes, documents or terms"
