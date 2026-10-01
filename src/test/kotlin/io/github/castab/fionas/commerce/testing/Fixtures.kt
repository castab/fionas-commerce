package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
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
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryPricingRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ZipCode
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.sql.SQLException
import java.time.Duration
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

/** Requested pricing inputs for repository-level inquiry fixtures; they name no real catalog revision. */
fun requestedPricing(revision: Int = 1) =
    FionasPricingInputs(
        OfferingsRevision.of(revision),
        OfferingSelections(
            listOf(
                OfferingCategorySelection(OfferingCategoryKey("soft-serve-flavor"), listOf(OfferingKey("vanilla"))),
                OfferingCategorySelection(OfferingCategoryKey("topping"), TOPPINGS.take(4).map(::OfferingKey)),
                OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("cup"))),
            ),
        ),
        FionasOfferingsContext(75, false, Duration.ofMinutes(120)),
    )

/**
 * Repository-level fixture for persistence specs: writes [inquiry] with the requested pricing inputs
 * the schema requires of every inquiry, in the caller's transaction. It neither prices them nor
 * materializes the initial Estimate that `CreateInquiry` always does; specs about accepted inquiries
 * use `CreateInquiry` or [TestApplication.createInquiry] instead.
 */
fun insertInquiryRecord(
    transaction: Transaction,
    inquiry: Inquiry,
    inputs: FionasPricingInputs = requestedPricing(),
) {
    JdbiInquiryRepository().insert(transaction, inquiry)
    JdbiInquiryPricingRepository().insert(transaction, inquiry.id, inputs)
}

/** The PostgreSQL SQLSTATE of this failure or one of its causes, if any. */
fun Throwable.sqlState(): String? =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .firstNotNullOfOrNull { it.sqlState }
