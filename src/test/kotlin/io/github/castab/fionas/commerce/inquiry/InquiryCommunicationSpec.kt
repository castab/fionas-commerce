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

        fun recorded(
            kind: InquiryCommunicationKind,
            at: Instant,
            order: Long,
        ) = RecordedInquiryCommunication(fact(kind, at), order)

        fun inbound(
            at: Instant,
            order: Long = 1,
        ) = recorded(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, at, order)

        test("every communication kind explicitly defines attention clearing, quote activity and principal provenance") {
            data class Semantics(
                val kind: InquiryCommunicationKind,
                val clears: Boolean,
                val quoteActivity: Boolean,
                val requiresPrincipal: Boolean,
            )

            InquiryCommunicationKind.entries.map {
                Semantics(it, it.clearsCustomerAttention, it.contributesToQuoteActivity, it.requiresActingPrincipal)
            } shouldBe
                listOf(
                    Semantics(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, false, true, false),
                    Semantics(InquiryCommunicationKind.STAFF_EMAIL_SENT, true, true, true),
                    Semantics(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, true, false, true),
                )
        }

        test("empty, one and multiple inbound preserve earliest outstanding and latest email activity regardless of read order") {
            InquiryCommunicationAttention.project(emptyList()) shouldBe InquiryCommunicationAttention(null, null)
            InquiryCommunicationAttention.project(listOf(inbound(monday))) shouldBe InquiryCommunicationAttention(monday, monday)
            InquiryCommunicationAttention.project(listOf(inbound(tuesday, 2), inbound(monday))) shouldBe
                InquiryCommunicationAttention(monday, tuesday)
        }
        listOf(InquiryCommunicationKind.STAFF_EMAIL_SENT, InquiryCommunicationKind.STAFF_ACKNOWLEDGED).forEach { kind ->
            test("$kind clears already recorded inbound and later ingestion re-enters regardless of occurredAt") {
                val clearing = recorded(kind, tuesday, 2)
                InquiryCommunicationAttention.project(listOf(inbound(monday), clearing)).unacknowledgedSince shouldBe null
                InquiryCommunicationAttention.project(listOf(inbound(tuesday), clearing)).unacknowledgedSince shouldBe null
                InquiryCommunicationAttention.project(listOf(inbound(monday, 3), clearing, inbound(tuesday))) shouldBe
                    InquiryCommunicationAttention(monday, tuesday)
            }
        }
        test("latest clearing wins and acknowledgement never advances meaningful email activity") {
            InquiryCommunicationAttention.project(
                listOf(
                    inbound(monday),
                    recorded(InquiryCommunicationKind.STAFF_EMAIL_SENT, tuesday, 2),
                    recorded(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, wednesday, 3),
                ),
            ) shouldBe InquiryCommunicationAttention(null, tuesday)
        }
        test("an explicit observed boundary clears only inbound recorded through it, however late the clearing fact") {
            val sent = InquiryCommunicationKind.STAFF_EMAIL_SENT
            val late = RecordedInquiryCommunication(fact(sent, wednesday).copy(clearsThrough = 1), 3)
            InquiryCommunicationAttention.project(listOf(inbound(monday), inbound(tuesday, 2), late)) shouldBe
                InquiryCommunicationAttention(tuesday, wednesday)
            // Boundaries combine by maximum: an earlier boundary recorded later never reopens what was cleared.
            val acknowledged =
                RecordedInquiryCommunication(fact(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, wednesday).copy(clearsThrough = 2), 4)
            InquiryCommunicationAttention
                .project(
                    listOf(inbound(monday), inbound(tuesday, 2), acknowledged, late),
                ).unacknowledgedSince shouldBe
                null
            RecordedInquiryCommunication(fact(sent, wednesday), 3).clearingOrder shouldBe 3
            late.clearingOrder shouldBe 1
            inbound(monday).clearingOrder shouldBe null
            shouldThrow<IllegalArgumentException> { fact(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, monday).copy(clearsThrough = 0) }
            shouldThrow<IllegalArgumentException> { fact(sent, monday).copy(clearsThrough = -1) }
            shouldThrow<IllegalArgumentException> { RecordedInquiryCommunication(fact(sent, monday).copy(clearsThrough = 3), 3) }
        }
        test("strict activity provenance and microsecond precision") {
            shouldThrow<IllegalArgumentException> {
                fact(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, monday).copy(principalId = staff)
            }
            shouldThrow<IllegalArgumentException> {
                fact(InquiryCommunicationKind.STAFF_ACKNOWLEDGED, monday).copy(principalId = null)
            }
            shouldThrow<IllegalArgumentException> {
                fact(InquiryCommunicationKind.STAFF_EMAIL_SENT, monday).copy(principalId = null)
            }
            shouldThrow<IllegalArgumentException> { fact(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, monday.plusNanos(1)) }
        }
    })
