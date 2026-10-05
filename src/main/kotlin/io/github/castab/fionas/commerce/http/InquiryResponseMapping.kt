package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

internal fun InquiryDetails.toResponse() =
    InquiryResponse(
        lifecycle = lifecycle.toResponse(),
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
        pricingInputs = pricingInputs.toResponse(),
        zipCode = inquiry.zipCode.value,
        eventDate = inquiry.eventDate.value.toString(),
        eventType = InquiryEventType.valueOf(inquiry.eventType.name),
    )

private fun FionasPricingInputs.toResponse() =
    InquiryRequestedPricing(
        catalogRevision = catalogRevision.number,
        guestCount = context.guestCount,
        guestCountIsMinimum = context.guestCountIsMinimum,
        durationMinutes = Math.toIntExact(context.duration.toMinutes()),
        selections = selections.categories.map { block -> PricingSelection(block.category.value, block.offerings.map { it.value }) },
    )
