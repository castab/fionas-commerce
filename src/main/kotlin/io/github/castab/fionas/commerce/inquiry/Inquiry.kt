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
 * A customer's durable request to Fiona's, retained through booking, service and closeout.
 *
 * Every inquiry is recorded with the [RequestedService] the customer asked for and the canonical
 * initial Estimate the public pricing authority priced for it, in one transaction.
 *
 * An inquiry is not a booking and implements no booking lifecycle phase. Its canonical initial
 * Estimate lineage drives its lifecycle projection; Invoice proves booking and Fiona owns
 * only served/closed operational facts.
 */
data class Inquiry(
    val id: InquiryId,
    val customerId: CustomerId,
    val message: InquiryMessage?,
    val createdAt: Instant,
    val zipCode: ZipCode,
    val eventDate: EventDate,
    val eventType: EventType,
)

/**
 * An [inquiry] together with the [customer] who made it and the [requestedService] recorded
 * with it, as staff read an inquiry. Every inquiry has them.
 *
 * [requestedService] is descriptive request history, never lines, amounts, or totals; the
 * initial Estimate holds the committed lines.
 */
data class InquiryDetails(
    val inquiry: Inquiry,
    val customer: Customer,
    val requestedService: RequestedService,
    val lifecycle: InquiryLifecycle,
) {
    init {
        require(inquiry.customerId == customer.id) { "Inquiry ${inquiry.id.value} belongs to another customer" }
    }
}

/** An [inquiry] and the [customer] who made it, as the staff inquiry list shows it. */
data class InquirySummary(
    val inquiry: Inquiry,
    val customer: Customer,
) {
    init {
        require(inquiry.customerId == customer.id) { "Inquiry ${inquiry.id.value} belongs to another customer" }
    }
}

/**
 * Where an inquiry stands in the newest-first inquiry list: its creation time, then its id,
 * which breaks ties between inquiries recorded at the same instant.
 */
data class InquiryListPosition(
    val createdAt: Instant,
    val id: InquiryId,
)

/**
 * One page of the newest-first inquiry list. [next] is the position of the page's last
 * inquiry when more inquiries follow it, and `null` on the last page.
 */
data class InquiryPage(
    val inquiries: List<InquirySummary>,
    val next: InquiryListPosition?,
)
