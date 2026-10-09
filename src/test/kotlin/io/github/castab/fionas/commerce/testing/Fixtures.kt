package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.ZipCode
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** [TEST_INSTANT] as PostgreSQL stores it, in microseconds. */
val STORED_INSTANT: Instant = TEST_INSTANT.truncatedTo(ChronoUnit.MICROS)

fun customer(
    email: String = "jane-${UUID.randomUUID()}@example.com",
    name: String = "Jane Doe",
    createdAt: Instant = STORED_INSTANT,
) = Customer(CustomerId(UUID.randomUUID()), CustomerName.of(name), Email.of(email), createdAt)

fun inquiry(
    customer: Customer,
    message: String? = "Ice cream for a birthday party",
    createdAt: Instant = STORED_INSTANT,
) = Inquiry(
    InquiryId(UUID.randomUUID()),
    customer.id,
    InquiryMessage.ofOptional(message),
    createdAt,
    ZipCode("92626"),
    EventDate.of("2026-12-05"),
    EventType.BIRTHDAY,
)

/**
 * Repository-level fixture for persistence specs: writes [inquiry] with the requested service
 * its row requires, in the caller's transaction. It does not materialize the initial Estimate that
 * `CreateInquiry` always does; specs about accepted inquiries use `CreateInquiry` or
 * [TestApplication.createInquiry] instead.
 */
fun insertInquiryRecord(
    transaction: Transaction,
    inquiry: Inquiry,
    requested: RequestedService = requestedService(),
) {
    JdbiInquiryRepository().insert(transaction, inquiry, requested)
}

/** The PostgreSQL SQLSTATE of this failure or one of its causes, if any. */
fun Throwable.sqlState(): String? =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .firstNotNullOfOrNull { it.sqlState }
