package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.RecordedInquiryCommunication
import java.time.OffsetDateTime
import java.util.UUID

/** Test-only observation of source rows; dashboard persistence exposes only bounded aggregates. */
fun communicationHistory(
    transaction: Transaction,
    inquiryId: InquiryId,
): List<RecordedInquiryCommunication> =
    transaction.handle
        .createQuery("SELECT * FROM fionas.inquiry_communications WHERE inquiry_id = :id ORDER BY recorded_order")
        .bind("id", inquiryId.value)
        .map { row, _ ->
            RecordedInquiryCommunication(
                InquiryCommunication(
                    InquiryCommunicationId(row.getObject("id", UUID::class.java)),
                    inquiryId,
                    InquiryCommunicationKind.valueOf(row.getString("kind")),
                    row.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                    when (row.getString("principal_kind")) {
                        "USER" -> UserId(row.getObject("principal_id", UUID::class.java))
                        "SERVICE" -> ServiceId(row.getObject("principal_id", UUID::class.java))
                        null -> null
                        else -> error("Invalid principal kind")
                    },
                ),
                row.getLong("recorded_order"),
            )
        }.list()
