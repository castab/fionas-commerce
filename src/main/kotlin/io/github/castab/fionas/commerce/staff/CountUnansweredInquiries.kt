package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationAttention
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates

/**
 * How many inquiries are [DashboardAttentionPolicy.awaitingAnswer] right now, from the same
 * operational snapshot and communication facts the dashboard reads, in one unlocked
 * REPEATABLE_READ transaction. Only a number leaves this read.
 */
class CountUnansweredInquiries(
    private val transactor: Transactor,
    private val operational: ReadInquiryOperationalStates,
    private val communications: InquiryCommunicationRepository,
    private val policy: DashboardAttentionPolicy = DashboardAttentionPolicy(),
) {
    operator fun invoke(): Int =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val snapshot =
                try {
                    operational(transaction)
                } catch (failure: CommerceFailure.NotFound) {
                    throw IllegalStateException("Canonical inquiry financial data is missing", failure)
                }
            val ids = snapshot.states.map { it.inquiryId }.toSet()
            val attention = communications.attentionFor(transaction, ids)
            check(ids.containsAll(attention.keys)) { "Communication activity is outside the operational population or corrupt" }
            snapshot.states.count { state ->
                policy.awaitingAnswer(state, attention[state.inquiryId] ?: InquiryCommunicationAttention(null, null))
            }
        }
}
