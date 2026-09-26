package io.github.castab.fionas.commerce.inquiry

import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import java.time.Instant
import java.util.UUID

/** Identifies a Fiona [Inquiry]. */
@JvmInline
value class InquiryId(
    val value: UUID,
)

/**
 * What the customer wrote with their inquiry, for example the occasion they have in mind.
 *
 * The constructor accepts only the canonical form (trimmed, non-blank, at most
 * [MAX_LENGTH] characters); [ofOptional] produces it from submitted text.
 */
@JvmInline
value class InquiryMessage(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Message must not be blank" }
        require(value == value.trim()) { "Message must not start or end with whitespace" }
        require(value.length <= MAX_LENGTH) { "Message must be at most $MAX_LENGTH characters" }
    }

    companion object {
        const val MAX_LENGTH = 4000

        /** The message in [submitted], or `null` when nothing but whitespace was submitted. */
        fun ofOptional(submitted: String?): InquiryMessage? = submitted?.trim()?.takeIf { it.isNotEmpty() }?.let(::InquiryMessage)
    }
}

/**
 * A prospective customer's initial request to Fiona's, before any booking exists.
 *
 * An inquiry is not a booking and implements no booking lifecycle phase. Relating it to a
 * lifecycle phase, an estimate, or a booking is future application policy.
 */
data class Inquiry(
    val id: InquiryId,
    val customerId: CustomerId,
    val message: InquiryMessage?,
    val createdAt: Instant,
)

/** An [inquiry] together with the [customer] who made it, as the inquiry operations return it. */
data class InquiryDetails(
    val inquiry: Inquiry,
    val customer: Customer,
) {
    init {
        require(inquiry.customerId == customer.id) { "Inquiry ${inquiry.id.value} belongs to another customer" }
    }
}
