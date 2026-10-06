package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.http.DashboardTotalQualifierResponse
import io.github.castab.fionas.commerce.http.StaffDashboardResponse
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationAttention
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalState
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.communicationHistory
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.proposalId
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Status
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DashboardAttentionSpec :
    FunSpec({
        lateinit var app: TestApplication
        val inquiries = JdbiInquiryRepository()
        val owners = JdbiInquiryFinancialDocumentRepository()
        val fulfillment = JdbiInquiryFulfillmentRepository()
        val communications = JdbiInquiryCommunicationRepository()
        val policy = DashboardAttentionPolicy()
        val zone = ZoneId.of("America/Los_Angeles")
        val noEmail = InquiryCommunicationAttention(null, null)

        fun operational() = ReadInquiryOperationalStates(app.transactor, inquiries, owners, app.context.financialLedger, fulfillment)

        fun state(id: InquiryId): InquiryOperationalState = operational()().states.single { it.inquiryId == id }

        fun read(
            at: Instant,
            calendarZone: ZoneId = zone,
        ) = ReadStaffDashboard(
            app.transactor,
            operational(),
            inquiries,
            JdbiCustomerRepository(),
            Clock.fixed(at, ZoneId.of("UTC")),
            communications,
            calendarZone,
        )()

        fun recorder(at: Instant = STORED_INSTANT) = RecordInquiryCommunication(app.transactor, communications, Clock.fixed(at, zone))

        fun actor() = app.authorization.findUserByUsername("admin")!!.id

        fun requested() = InquiryId(UUID.fromString(app.createInquiry()))

        fun document(id: InquiryId) = app.initialEstimateOf(id.value.toString())

        fun quote(id: InquiryId) {
            app.issueProposal(id)
        }

        fun book(id: InquiryId) {
            quote(id)
            app
                .adminPost(
                    "/financial-documents/${document(id)}/payments",
                    """{"documentVersion":2,"amount":"50.00","method":"CARD","expectedProposalId":"${app.proposalId(
                        UUID.fromString(document(id)),
                    )!!.value}"}""",
                ).status shouldBe Status.CREATED
        }

        fun serve(id: InquiryId) {
            app.adminPost("/inquiries/${id.value}/served", "").status shouldBe Status.OK
        }

        fun settle(
            id: InquiryId,
            extra: String = "0",
        ) {
            val current = state(id)
            val amount =
                current.financial.reconciliation.balance.amount
                    .add(BigDecimal(extra))
            app
                .adminPost(
                    "/financial-documents/${document(id)}/payments",
                    """{"documentVersion":${current.financial.latestVersion.document.version.number},"amount":"$amount","method":"CARD"}""",
                ).status shouldBe
                Status.CREATED
        }

        fun resolution(
            current: InquiryOperationalState,
            date: EventDate,
            at: Instant,
            email: InquiryCommunicationAttention = noEmail,
        ) = policy.needsResolution(current, date, email, at, zone)

        beforeTest {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterTest { app.close() }

        test("requested quoting attention shares summary predicate and creation anchor; quote leaves it") {
            val id = requested()
            val result = read(STORED_INSTANT)
            result.summary.new shouldBe result.workQueue.needsQuote.items.size
            result.workQueue.needsQuote.items
                .single()
                .attentionSince shouldBe STORED_INSTANT
            result.workQueue.needsQuote.items
                .single()
                .reasons shouldBe setOf(StaffAttentionReason.NEEDS_QUOTE)
            quote(id)
            read(STORED_INSTANT).workQueue.needsQuote.items shouldBe emptyList()
        }

        listOf(false, true).forEach { minimum ->
            test("total qualifier follows canonical Estimate minimum flag then becomes EXACT at Quote and Invoice: minimum=$minimum") {
                val id =
                    InquiryId(
                        UUID.fromString(
                            app.createInquiry(pricing = {
                                pricingBody(it).replace("\"guestCount\":", "\"guestCountIsMinimum\":$minimum,\"guestCount\":")
                            }),
                        ),
                    )
                recorder().customerEmailReceived(id, STORED_INSTANT)
                val expected = if (minimum) DashboardTotalQualifier.FROM else DashboardTotalQualifier.EXACT
                val estimate = read(STORED_INSTANT)
                listOf(estimate.workQueue.needsQuote, estimate.workQueue.needsReply).forEach {
                    it.items.single().totalQualifier shouldBe expected
                }

                fun http() = CommerceJson.asA(app.adminGet("/staff/dashboard").bodyString(), StaffDashboardResponse.serializer())
                listOf(http().workQueue.needsQuote, http().workQueue.needsReply).forEach {
                    it.items.single().totalQualifier shouldBe DashboardTotalQualifierResponse.valueOf(expected.name)
                }
                quote(id)
                val stale =
                    state(id)
                        .financial.latestVersion.createdAt
                        .plus(Duration.ofDays(3))
                val quoted = read(stale)
                listOf(quoted.workQueue.needsReply, quoted.workQueue.needsResolution).forEach {
                    it.items.single().totalQualifier shouldBe DashboardTotalQualifier.EXACT
                }
                http()
                    .workQueue.needsReply.items
                    .single()
                    .totalQualifier shouldBe DashboardTotalQualifierResponse.EXACT
                app.transactor.inTransaction { app.context.financialLedger.issueInvoice(it, UUID.fromString(document(id))) }
                serve(id)
                val invoice = read(STORED_INSTANT)
                listOf(invoice.workQueue.needsReply, invoice.workQueue.needsResolution).forEach {
                    it.items.single().totalQualifier shouldBe DashboardTotalQualifier.EXACT
                }
                listOf(http().workQueue.needsReply, http().workQueue.needsResolution).forEach {
                    it.items.single().totalQualifier shouldBe DashboardTotalQualifierResponse.EXACT
                }
            }
        }

        test("backdated inbound ingested after acknowledgement needs reply but quote inactivity uses actual email time") {
            val id = requested()
            quote(id)
            val issued = state(id).financial.latestVersion.createdAt
            recorder(issued.plus(Duration.ofDays(10))).acknowledge(RecordInquiryCommunication.Acknowledge(id, actor()))
            val backdated = issued.plusSeconds(3600)
            recorder().customerEmailReceived(id, backdated)
            val at = backdated.plus(Duration.ofDays(3))
            val result = read(at)
            result.workQueue.needsReply.items
                .single()
                .attentionSince shouldBe backdated
            result.workQueue.needsResolution.items
                .single()
                .attentionSince shouldBe at
            result.workQueue.needsResolution.items
                .single()
                .reasons shouldBe setOf(StaffAttentionReason.QUOTE_STALE)
            read(at.minusNanos(1000)).workQueue.needsResolution.items shouldBe emptyList()
        }

        test("quote threshold is inclusive; inbound and sent reset it, acknowledgement does not, booking removes it") {
            val id = requested()
            quote(id)
            val quoted = state(id)
            val issuedAt = quoted.financial.latestVersion.createdAt
            val staleAt = issuedAt.plus(Duration.ofDays(3))
            resolution(quoted, EventDate.of("2020-01-01"), staleAt.minusNanos(1000)) shouldBe null
            listOf(staleAt, staleAt.plusSeconds(86400)).forEach { at ->
                val attention = resolution(quoted, EventDate.of("2020-01-01"), at)!!
                attention.reasons shouldBe setOf(StaffAttentionReason.QUOTE_STALE)
                attention.attentionSince shouldBe staleAt
            }
            val receivedAt = issuedAt.plusSeconds(86400)
            recorder().customerEmailReceived(id, receivedAt)
            read(staleAt).workQueue.needsResolution.items shouldBe emptyList()
            read(staleAt)
                .workQueue.needsReply.items
                .single()
                .attentionSince shouldBe receivedAt
            recorder(receivedAt.plusSeconds(3600)).acknowledge(RecordInquiryCommunication.Acknowledge(id, actor()))
            val afterAcknowledgement = read(receivedAt.plus(Duration.ofDays(3)))
            afterAcknowledgement.workQueue.needsReply.items shouldBe emptyList()
            afterAcknowledgement.workQueue.needsResolution.items
                .single()
                .attentionSince shouldBe receivedAt.plus(Duration.ofDays(3))
            val sentAt = receivedAt.plusSeconds(7200)
            recorder().staffEmailSent(id, sentAt, actor())
            read(receivedAt.plus(Duration.ofDays(3))).workQueue.needsResolution.items shouldBe emptyList()
            read(sentAt.plus(Duration.ofDays(3)))
                .workQueue.needsResolution.items
                .single()
                .attentionSince shouldBe
                sentAt.plus(Duration.ofDays(3))
            app
                .adminPost(
                    "/financial-documents/${document(id)}/payments",
                    """{"documentVersion":2,"amount":"50.00","method":"CARD","expectedProposalId":"${app.proposalId(
                        UUID.fromString(document(id)),
                    )!!.value}"}""",
                ).status shouldBe Status.CREATED
            read(sentAt.plus(Duration.ofDays(10))).workQueue.needsResolution.items shouldBe emptyList()
        }

        test("event attention begins after scheduled day in explicit event calendar zone, including DST, and only for BOOKED") {
            val id = requested()
            val yesterday = EventDate.of("2026-03-08")
            val today = EventDate.of("2026-03-09")
            val tomorrow = EventDate.of("2026-03-10")
            val at = Instant.parse("2026-03-09T07:30:00Z")
            val onset = Instant.parse("2026-03-09T07:00:00Z")
            resolution(state(id), yesterday, at) shouldBe null
            quote(id)
            resolution(state(id), yesterday, at) shouldBe null
            app.transactor.inTransaction { app.context.financialLedger.issueInvoice(it, UUID.fromString(document(id))) }
            val booked = state(id)
            resolution(booked, today, at) shouldBe null
            resolution(booked, tomorrow, at) shouldBe null
            resolution(booked, yesterday, onset.minusNanos(1000)) shouldBe null
            resolution(booked, yesterday, at)!!.attentionSince shouldBe onset
            resolution(booked, yesterday, at)!!.reasons shouldBe setOf(StaffAttentionReason.EVENT_DATE_PASSED_UNSERVED)
            serve(id)
            resolution(state(id), yesterday, at)!!.reasons shouldBe setOf(StaffAttentionReason.SERVED_WITH_BALANCE_DUE)
        }

        test("served balance uses exact sign; reply overlaps resolution and acknowledgement leaves financial/lifecycle facts unchanged") {
            val id = requested()
            book(id)
            serve(id)
            val before = state(id)
            val first = STORED_INSTANT.minusSeconds(86400)
            recorder().customerEmailReceived(id, first)
            recorder().customerEmailReceived(id, first.plusSeconds(3600))
            val result = read(STORED_INSTANT)
            result.workQueue.needsReply.items
                .single()
                .attentionSince shouldBe first
            result.workQueue.needsResolution.items
                .single()
                .reasons shouldBe setOf(StaffAttentionReason.SERVED_WITH_BALANCE_DUE)
            result.workQueue.needsResolution.items
                .single()
                .attentionSince shouldBe STORED_INSTANT
            result.workQueue.needsReply.items
                .single()
                .inquiryId shouldBe
                result.workQueue.needsResolution.items
                    .single()
                    .inquiryId
            recorder().acknowledge(RecordInquiryCommunication.Acknowledge(id, actor()))
            read(STORED_INSTANT).workQueue.needsReply.items shouldBe emptyList()
            state(id).financial.latestVersion.document shouldBe before.financial.latestVersion.document
            state(id).financial.reconciliation.balance shouldBe before.financial.reconciliation.balance
            state(id).lifecycle.fulfillment shouldBe before.lifecycle.fulfillment
            recorder().customerEmailReceived(id, STORED_INSTANT.plusSeconds(1))
            read(STORED_INSTANT.plusSeconds(1))
                .workQueue.needsReply.items
                .single()
                .attentionSince shouldBe STORED_INSTANT.plusSeconds(1)
            settle(id)
            val paid = read(STORED_INSTANT)
            paid.workQueue.needsResolution.items
                .single()
                .reasons shouldBe setOf(StaffAttentionReason.READY_TO_CLOSE)
            paid.summary.needsClosing shouldBe
                paid.workQueue.needsResolution.items
                    .count { StaffAttentionReason.READY_TO_CLOSE in it.reasons }
            app.adminPost("/inquiries/${id.value}/close", "").status shouldBe Status.OK
            read(STORED_INSTANT).workQueue.needsResolution.items shouldBe emptyList()
            val negative = requested()
            book(negative)
            serve(negative)
            settle(negative, "1.00")
            read(STORED_INSTANT).workQueue.needsResolution.items shouldBe emptyList()
        }

        test("resolution aggregation retains every reason and earliest onset without duplicate cards") {
            val attention =
                StaffAttention(
                    linkedMapOf(
                        StaffAttentionReason.QUOTE_STALE to STORED_INSTANT,
                        StaffAttentionReason.EVENT_DATE_PASSED_UNSERVED to STORED_INSTANT.minusSeconds(1),
                    ),
                )
            attention.reasons.size shouldBe 2
            attention.attentionSince shouldBe STORED_INSTANT.minusSeconds(1)
        }

        test("communication source writes round trip provenance, truncate precision, reject unknown inquiries and roll back") {
            val id = requested()
            val received = recorder().customerEmailReceived(id, testClock.instant())
            received.occurredAt shouldBe STORED_INSTANT
            received.principalId shouldBe null
            val sent = recorder().staffEmailSent(id, STORED_INSTANT, actor())
            app.transactor.inTransaction { communicationHistory(it, id).map { row -> row.activity }.toSet() } shouldBe setOf(received, sent)
            shouldThrow<CommerceFailure.NotFound> { recorder().customerEmailReceived(InquiryId(UUID.randomUUID()), STORED_INSTANT) }
            shouldThrow<IllegalStateException> {
                app.transactor.inTransaction {
                    communications.append(
                        it,
                        received.copy(
                            id =
                                InquiryCommunicationId(UUID.randomUUID()),
                        ),
                    )
                    error("rollback")
                }
            }
            app.transactor.inTransaction { communicationHistory(it, id).size } shouldBe 2
        }
    })
