package io.github.castab.fionas.commerce.customer

import java.time.Instant
import java.util.UUID

/** Identifies a Fiona [Customer]. */
@JvmInline
value class CustomerId(
    val value: UUID,
)

/**
 * A customer's name as they gave it, for example `Jane Doe`: one display string rather
 * than structured given and family names.
 *
 * The constructor accepts only the canonical form (trimmed, non-blank, at most
 * [MAX_LENGTH] characters); [of] produces it from submitted text.
 */
@JvmInline
value class CustomerName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Name must not be blank" }
        require(value == value.trim()) { "Name must not start or end with whitespace" }
        require(value.length <= MAX_LENGTH) { "Name must be at most $MAX_LENGTH characters" }
    }

    companion object {
        const val MAX_LENGTH = 200

        /** The name in [submitted], without surrounding whitespace. */
        fun of(submitted: String): CustomerName = CustomerName(submitted.trim())
    }
}

/**
 * An email address in the normalized form customers are matched by: trimmed and
 * lowercased, so `Jane@Example.com ` and `jane@example.com` are the same address.
 *
 * Validation is deliberately shallow. It rejects text that is plainly not an address; it
 * does not attempt full RFC 5322 parsing or prove that the mailbox exists.
 */
@JvmInline
value class Email(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH) { "Email must be at most $MAX_LENGTH characters" }
        require(value == value.trim().lowercase()) { "Email must be trimmed and lowercase" }
        require(value.none { it.isWhitespace() }) { "Email must not contain whitespace" }
        val local = value.substringBefore('@', missingDelimiterValue = "")
        val domain = value.substringAfter('@', missingDelimiterValue = "")
        require(local.isNotEmpty() && '@' !in domain) { "Email must contain exactly one @ after a non-empty local part" }
        require('.' in domain && !domain.startsWith('.') && !domain.endsWith('.')) {
            "Email must have a domain such as example.com"
        }
    }

    companion object {
        const val MAX_LENGTH = 254

        /** The normalized address in [submitted]. */
        fun of(submitted: String): Email = Email(submitted.trim().lowercase())
    }
}

/**
 * The person who makes an inquiry and enters into the commercial relationship with Fiona's.
 *
 * Deliberately small: a customer's own identity, not the contextual people of a particular
 * event. Event and booking contacts are separate, purgeable application data and are never
 * stored here (see AGENTS.md).
 */
data class Customer(
    val id: CustomerId,
    val name: CustomerName,
    val email: Email,
    val createdAt: Instant,
)
