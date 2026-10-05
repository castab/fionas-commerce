package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class InquiryCommunicationSpec :
    FunSpec({
        val inquiry = InquiryId(UUID.randomUUID())
        val staff = UserId(UUID.randomUUID())
        val monday = Instant.parse("2026-10-05T10:00:00Z")
        val tuesday = monday.plusSeconds(86400)
        val wednesday = tuesday.plusSeconds(86400)

        fun fact(
            kind: InquiryCommunicationKind,
            at: Instant,
        ) = InquiryCommunication(
            InquiryCommunicationId(UUID.randomUUID()),
            inquiry,
            kind,
            at,
            if (kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED) null else staff,
        )

        fun inbound(at: Instant) = fact(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, at)

        test("empty, one and multiple inbound preserve earliest outstanding and latest email activity regardless of read order") {
            InquiryCommunicationAttention.project(emptyList()) shouldBe InquiryCommunicationAttention(null, null)
            InquiryCommunicationAttention.project(listOf(inbound(monday))) shouldBe InquiryCommunicationAttention(monday, monday)
            InquiryCommunicationAttention.project(listOf(inbound(tuesday), inbound(monday))) shouldBe
                InquiryCommunicationAttention(monday, tuesday)
        }
        listOf(InquiryCommunicationKind.STAFF_EMAIL_SENT, InquiryCommunicationKind.STAFF_ACKNOWLEDGED).forEach { kind ->
            test("$kind clears inbound at or before it and later inbound re-enters") {
                val clearing = fact(kind, tuesday)
                InquiryCommunicationAttention.project(listOf(inbound(monday), clearing)).unacknowledgedSince shouldBe null
                InquiryCommunicationAttention.project(listOf(inbound(tuesday), clearing)).unacknowledgedSince shouldBe null
                InquiryCommunicationAttention.project(listOf(inbound(wednesday), clearing, inbound(monday))) shouldBe
                    InquiryCommunicationAttention(wednesday, wednesday)
            }
        }
        test("latest clearing wins and acknowledgement never advances meaningful email activity") {
            InquiryCommunicationAttention.project(
                listOf(
                    inbound(monday),
                    fact(InquiryCommunicationKind.STAFF_EMAIL_SENT, tuesday),
                    fact(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, wednesday),
                ),
            ) shouldBe InquiryCommunicationAttention(null, tuesday)
        }
        test("strict activity provenance and microsecond precision") {
            shouldThrow<IllegalArgumentException> {
                fact(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, monday).copy(principalId = staff)
            }
            shouldThrow<IllegalArgumentException> {
                fact(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, monday).copy(principalId = null)
            }
            shouldThrow<IllegalArgumentException> { inbound(monday.plusNanos(1)) }
        }
    })
