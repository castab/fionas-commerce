package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.inquiry.InquiryDetails

internal fun InquiryDetails.toResponse() =
    InquiryResponse(
        lifecycle = lifecycle.toResponse(),
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
        requestedService = requestedService.toResponse(),
        zipCode = inquiry.zipCode.value,
        eventDate = inquiry.eventDate.value.toString(),
        eventType = InquiryEventType.valueOf(inquiry.eventType.name),
    )
