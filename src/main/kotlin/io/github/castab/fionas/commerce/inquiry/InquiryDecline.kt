package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import java.time.Instant

/**
 * Staff's internal explanation of why Fiona's will not pursue an inquiry, for example
 * "Requested date is unavailable." Operational history, never a customer-facing message.
 *
 * The constructor accepts only the canonical form (trimmed, non-blank, at most [MAX_LENGTH]
 * characters); [of] produces it from submitted text.
 */
@JvmInline
value class DeclineReason(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Decline reason must not be blank" }
        require(value == value.trim()) { "Decline reason must not start or end with whitespace" }
        require(value.length <= MAX_LENGTH) { "Decline reason must be at most $MAX_LENGTH characters" }
    }

    companion object {
        const val MAX_LENGTH = 500

        fun of(submitted: String): DeclineReason = DeclineReason(submitted.trim())
    }
}

/**
 * A durable, terminal staff decision not to pursue a REQUESTED inquiry: who decided, when, why,
 * and the exact canonical Estimate snapshot ([estimate]) they reviewed. Immutable; there is no
 * reopening. The inquiry, its customer, requested service and financial lineage stay unchanged.
 */
data class InquiryDecline(
    val inquiryId: InquiryId,
    val estimate: FinancialDocumentReference,
    val declinedAt: Instant,
    /** The verified staff user who declined, from authentication. */
    val declinedBy: UserId,
    val reason: DeclineReason,
)

/**
 * Decline facts inside the caller's [Transaction]. Callers that act on them hold the inquiry's
 * canonical association lock, which serializes decline with every commercial progression.
 */
interface InquiryDeclineRepository {
    fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryDecline?

    /** One set-based read; undeclined inquiries are absent and empty input returns an empty map. */
    fun findAll(
        transaction: Transaction,
        inquiryIds: Collection<InquiryId>,
    ): Map<InquiryId, InquiryDecline>

    /** Inserts the one decline of its inquiry; a second is [io.github.castab.commerce.runtime.operation.CommerceFailure.Conflict]. */
    fun insert(
        transaction: Transaction,
        decline: InquiryDecline,
    )
}
