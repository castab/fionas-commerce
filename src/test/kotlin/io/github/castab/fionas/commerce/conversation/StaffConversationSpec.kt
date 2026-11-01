package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.http.StaffReplyOutcomeResponse
import io.github.castab.fionas.commerce.http.StaffReplyResponse
import io.github.castab.fionas.commerce.http.StaffRequestListResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.http.StaffTimelineResponse
import io.github.castab.fionas.commerce.http.TimelineItemResponse
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.inquiry.RecordedInquiryCommunication
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.StubConversationProvider
import io.github.castab.fionas.commerce.testing.StubConversationProvider.Behavior
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class StaffConversationSpec :
    FunSpec({
        lateinit var provider: StubConversationProvider
        lateinit var app: TestApplication
        beforeTest {
            provider = StubConversationProvider()
            app = TestApplication.create(conversationReads = provider.readConfiguration(), conversationSends = provider.sendConfiguration())
        }
        afterTest {
            app.close()
            provider.close()
        }

        fun reply(
            id: String,
            body: String,
            expectation: String = "\"expectEmptyConversation\":true",
            key: String = UUID.randomUUID().toString(),
        ): Response =
            app.http(
                Request(Method.POST, "/staff/requests/$id/conversation/replies")
                    .header("Origin", TEST_ORIGIN)
                    .header("Cookie", app.adminCookie)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", key)
                    .body("""{"body":${CommerceJson.asFormatString(body)},$expectation}"""),
            )

        fun Response.result() = CommerceJson.asA(bodyString(), StaffReplyResponse.serializer())

        fun Response.code() = CommerceJson.asA(bodyString(), ErrorResponse.serializer()).code

        fun history(id: String) =
            app.transactor.inTransaction { JdbiInquiryCommunicationRepository().history(it, InquiryId(UUID.fromString(id))) }

        fun sent(id: String) = history(id).filter { it.activity.kind == InquiryCommunicationKind.STAFF_EMAIL_SENT }

        fun admin() = checkNotNull(app.authorization.findUserByUsername("admin")).id

        fun detail(id: String) = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())

        fun timeline(id: String) =
            CommerceJson.asA(app.adminGet("/staff/requests/$id/timeline").bodyString(), StaffTimelineResponse.serializer())

        fun latest(id: String) = "\"expectedLatestMessageId\":\"${timeline(id).conversation.latestMessageId}\""

        test("a first reply opens the topic conversation as the staff user, signed from the trusted profile, and replays converge") {
            val id = app.createInquiry()
            val key = UUID.randomUUID().toString()
            val revisions = ReadInquiryTimelineRevisions(app.transactor, InquiryTimelineRevisionRepository())
            val inquiryId = InquiryId(UUID.fromString(id))
            revisions(setOf(inquiryId)).getValue(inquiryId) shouldBe 0L
            val first = reply(id, "  Hi Jane,\n\n  we can do the 12th.  ", key = key)
            first.status shouldBe Status.CREATED
            first.header("Cache-Control") shouldBe "no-store"
            first.result().outcome shouldBe StaffReplyOutcomeResponse.ACCEPTED
            first.result().sendState shouldBe "accepted"
            revisions(setOf(inquiryId)).getValue(inquiryId) shouldBe 1L
            val opening = provider.requests.single()
            opening
                .getValue("topic")
                .jsonObject
                .getValue("externalId")
                .jsonPrimitive.content shouldBe id
            val message = opening.getValue("message").jsonObject
            message.getValue("text").jsonPrimitive.content shouldBe
                "Hi Jane,\n\n  we can do the 12th.\n\n— Sent from Test Administrator @ Fiona's Ice Cream"
            message
                .getValue("from")
                .jsonObject
                .getValue("address")
                .jsonPrimitive.content shouldBe "bookings@mail.fionas.test"
            message
                .getValue("replyTo")
                .jsonObject
                .getValue("address")
                .jsonPrimitive.content shouldBe "replies@mail.fionas.test"
            message.containsKey("to") shouldBe false
            sent(id).single().activity.principalId shouldBe admin()

            val replay = reply(id, "Hi Jane,\n\n  we can do the 12th.", key = key)
            replay.status shouldBe Status.OK
            replay.result().replayed shouldBe true
            replay.result().messageId shouldBe first.result().messageId
            revisions(setOf(inquiryId)).getValue(inquiryId) shouldBe 1L
            reply(id, "A different reply", key = key).let {
                it.status shouldBe Status.CONFLICT
                it.code() shouldBe "IDEMPOTENCY_KEY_REUSED"
            }
            provider.created.get() shouldBe 1
            sent(id).size shouldBe 1
        }

        test("a reply continues the existing topic and clears earlier inbound; a stale expectation sends nothing") {
            val id = app.createInquiry()
            val inbound = provider.inbound(id)
            app.transactor.inTransaction {
                JdbiInquiryCommunicationRepository().append(
                    it,
                    InquiryCommunication(
                        InquiryCommunicationId(UUID.randomUUID()),
                        InquiryId(UUID.fromString(id)),
                        InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                        Instant.parse("2026-10-01T12:00:00Z"),
                        null,
                    ),
                )
            }
            detail(id).communication.needsReply shouldBe true
            timeline(id).conversation.latestMessageId shouldBe inbound.toString()
            reply(id, "Stale", "\"expectedLatestMessageId\":\"${UUID.randomUUID()}\"").let {
                it.status shouldBe Status.CONFLICT
                it.code() shouldBe "conversation_changed"
            }
            reply(id, "Empty expected").code() shouldBe "conversation_changed"
            provider.sendAttempts.get() shouldBe 0
            val accepted = reply(id, "Yes, the 12th works.", latest(id))
            accepted.status shouldBe Status.CREATED
            provider.requests
                .single()
                .containsKey("topic") shouldBe false
            detail(id).communication.needsReply shouldBe false
            val items = timeline(id).items.filterIsInstance<TimelineItemResponse.Message>()
            items.map { it.direction } shouldBe listOf("inbound", "outbound")
            items.last().deliveryState shouldBe "unknown"
            items.last().sendState shouldBe "accepted"
        }

        fun appendInbound(
            id: String,
            at: Instant,
        ) = app.transactor.inTransaction {
            JdbiInquiryCommunicationRepository().append(
                it,
                InquiryCommunication(
                    InquiryCommunicationId(UUID.randomUUID()),
                    InquiryId(UUID.fromString(id)),
                    InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                    at,
                    null,
                ),
            )
        }

        fun listed(id: String) =
            CommerceJson
                .asA(app.adminGet("/staff/requests").bodyString(), StaffRequestListResponse.serializer())
                .items
                .single { it.inquiryId == id }

        test("inbound recorded while the provider accepts a reply stays outstanding in both attention projections") {
            val id = app.createInquiry()
            provider.inbound(id)
            val observed = appendInbound(id, Instant.parse("2026-10-01T12:00:00Z"))
            val later = Instant.parse("2026-10-01T12:05:00Z")
            val appended = CountDownLatch(1)
            val release = CountDownLatch(1)
            var writer: CompletableFuture<*>? = null
            // A customer email is ingested after the staff member's lookup, while the provider is accepting: its
            // append holds the inquiry lock, so the reply's acceptance waits for it and still must not clear it.
            provider.onSend = {
                provider.onSend = null
                writer =
                    CompletableFuture.runAsync {
                        app.transactor.inTransaction { transaction ->
                            JdbiInquiryCommunicationRepository().append(
                                transaction,
                                InquiryCommunication(
                                    InquiryCommunicationId(UUID.randomUUID()),
                                    InquiryId(UUID.fromString(id)),
                                    InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                                    later,
                                    null,
                                ),
                            )
                            appended.countDown()
                            release.await()
                        }
                    }
                appended.await()
            }
            val expectation = latest(id)
            val sending = CompletableFuture.supplyAsync { reply(id, "Yes, the 12th works.", expectation) }
            appended.await()
            Thread.sleep(300)
            sending.isDone shouldBe false
            release.countDown()
            sending.join().status shouldBe Status.CREATED
            checkNotNull(writer).join()
            sent(id).single().activity.clearsThrough shouldBe observed.recordedOrder
            detail(id).communication.needsReply shouldBe true
            detail(id).communication.unacknowledgedSince shouldBe later.toString()
            listed(id).needsReply shouldBe true
            listed(id).awaitingReplySince shouldBe later.toString()
            // Acknowledging what is now visible clears it, through the explicit watermark.
            val through = detail(id).communication.throughRecordedOrder
            app.adminPost("/inquiries/$id/communications/acknowledge", """{"throughRecordedOrder":$through}""").status shouldBe
                Status.NO_CONTENT
            detail(id).communication.needsReply shouldBe false
            listed(id).needsReply shouldBe false
        }

        test("a delayed same-key reconciliation never clears inbound recorded after the original observation") {
            val id = app.createInquiry()
            val earlier = appendInbound(id, Instant.parse("2026-10-01T12:00:00Z"))
            val key = UUID.randomUUID().toString()
            provider.script += Behavior.TIMEOUT_AFTER_PERSIST
            provider.replayDelayMillis = 1500
            reply(id, "Slow", key = key).result().outcome shouldBe StaffReplyOutcomeResponse.UNKNOWN
            provider.replayDelayMillis = 0
            val later = Instant.parse("2026-10-01T13:00:00Z")
            appendInbound(id, later)
            reply(id, "Slow", key = key).let {
                it.status shouldBe Status.OK
                it.result().outcome shouldBe StaffReplyOutcomeResponse.ACCEPTED
            }
            provider.created.get() shouldBe 1
            sent(id).single().activity.clearsThrough shouldBe earlier.recordedOrder
            detail(id).communication.unacknowledgedSince shouldBe later.toString()
            listed(id).awaitingReplySince shouldBe later.toString()
        }

        test("an accepted reply establishes the topic for sending and the timeline without the conversation consumer") {
            val id = app.createInquiry()
            reply(id, "Hello").status shouldBe Status.CREATED
            timeline(id).conversation.state.name shouldBe "READY"
            // The provider later answers 404 for the topic; no events were ever ingested (intake disabled).
            provider.conversations.remove(id)
            timeline(id).conversation.state.name shouldBe "MISSING"
            reply(id, "Hello again").let {
                it.status shouldBe Status.CONFLICT
                it.code() shouldBe "conversation_missing"
            }
            provider.created.get() shouldBe 1
            sent(id).size shouldBe 1
            // Without any evidence a 404 is still an empty thread.
            timeline(app.createInquiry()).conversation.state.name shouldBe "EMPTY"
        }

        test("provider unavailability and missing established threads never open a replacement conversation") {
            val id = app.createInquiry()
            provider.lookupStatus = 500
            reply(id, "Hello").let {
                it.status shouldBe Status.SERVICE_UNAVAILABLE
                it.code() shouldBe "email_provider_unavailable"
            }
            provider.lookupStatus = 401
            reply(id, "Hello").status shouldBe Status.SERVICE_UNAVAILABLE
            provider.lookupStatus = null
            // Ingested provider history proves a conversation existed; a 404 now must not be treated as a first reply.
            app.transactor.inTransaction {
                JdbiInquiryConversationRepository().claimEvent(
                    it,
                    InquiryId(UUID.fromString(id)),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    3,
                    "a".repeat(64),
                )
            }
            reply(id, "Hello").let {
                it.status shouldBe Status.CONFLICT
                it.code() shouldBe "conversation_missing"
            }
            provider.sendAttempts.get() shouldBe 0
            history(id) shouldBe emptyList()
            timeline(id).conversation.state.name shouldBe "MISSING"
        }

        test("ambiguous results replay the exact request once with the same key; an unconfirmed outcome is UNKNOWN, never failure") {
            val id = app.createInquiry()
            provider.script += Behavior.ERROR_AFTER_PERSIST
            reply(id, "Recovered").let {
                it.status shouldBe Status.CREATED
                it.result().outcome shouldBe StaffReplyOutcomeResponse.ACCEPTED
            }
            provider.sendAttempts.get() shouldBe 2
            provider.created.get() shouldBe 1

            val other = app.createInquiry()
            val key = UUID.randomUUID().toString()
            provider.script += Behavior.TIMEOUT_AFTER_PERSIST
            provider.replayDelayMillis = 1500
            reply(other, "Slow", key = key).let {
                it.status shouldBe Status.ACCEPTED
                it.result().outcome shouldBe StaffReplyOutcomeResponse.UNKNOWN
            }
            provider.replayDelayMillis = 0
            sent(other) shouldBe emptyList()
            // Staff retry after the unknown outcome reconciles to the one provider message, recorded once.
            reply(other, "Slow", key = key).let {
                it.status shouldBe Status.OK
                it.result().outcome shouldBe StaffReplyOutcomeResponse.ACCEPTED
            }
            provider.created.get() shouldBe 2
            sent(other).size shouldBe 1
            // A blind resend under a new key with the old (empty) expectation is refused: the first reply is now visible.
            reply(other, "Slow").code() shouldBe "conversation_changed"

            val ambiguous = app.createInquiry()
            provider.script += Behavior.INDETERMINATE
            val unknownKey = UUID.randomUUID().toString()
            reply(ambiguous, "Maybe", key = unknownKey).result().outcome shouldBe StaffReplyOutcomeResponse.UNKNOWN
            reply(ambiguous, "Maybe", key = unknownKey).result().outcome shouldBe StaffReplyOutcomeResponse.UNKNOWN
            sent(ambiguous) shouldBe emptyList()
        }

        test("a provider success followed by a local failure is repaired by the same-key retry without a second email") {
            val id = app.createInquiry()
            val communications = JdbiInquiryCommunicationRepository()
            val failing =
                object : InquiryCommunicationRepository by communications {
                    override fun append(
                        transaction: Transaction,
                        activity: InquiryCommunication,
                    ): RecordedInquiryCommunication = error("simulated database failure")
                }
            val inquiries = JdbiInquiryRepository()
            val operation =
                SendStaffReply(
                    app.transactor,
                    GetInquiry(
                        app.transactor,
                        JdbiCustomerRepository(),
                        inquiries,
                        ReadInquiryLifecycle(
                            app.context.financialLedger,
                            JdbiInquiryFinancialDocumentRepository(),
                            JdbiInquiryFulfillmentRepository(),
                        ),
                    ),
                    failing,
                    JdbiStaffReplyRepository(),
                    ConversationEstablishment(JdbiInquiryConversationRepository(), JdbiStaffReplyRepository()),
                    app.authorization::getUser,
                    ResendConversationSender(provider.sendConfiguration()),
                    testClock,
                )
            val key = UUID.randomUUID().toString()
            shouldThrow<IllegalStateException> {
                operation(
                    SendStaffReply.Command(
                        InquiryId(UUID.fromString(id)),
                        admin(),
                        StaffReplyKey(key),
                        StaffReplyBody.of("Persist me"),
                        ConversationExpectation.Empty,
                    ),
                )
            }
            provider.created.get() shouldBe 1
            sent(id) shouldBe emptyList()
            reply(id, "Persist me", key = key).status shouldBe Status.OK
            provider.created.get() shouldBe 1
            sent(id).size shouldBe 1
        }

        test("definitive provider rejection is recorded and replayed without another provider call") {
            val id = app.createInquiry()
            provider.script += Behavior.REJECT
            val key = UUID.randomUUID().toString()
            reply(id, "Rejected", key = key).let {
                it.status shouldBe Status.BAD_GATEWAY
                it.code() shouldBe "email_send_rejected"
            }
            val attempts = provider.sendAttempts.get()
            reply(id, "Rejected", key = key).code() shouldBe "email_send_rejected"
            provider.sendAttempts.get() shouldBe attempts
            provider.script += Behavior.FAILED
            reply(id, "Failed send").status shouldBe Status.BAD_GATEWAY
            sent(id) shouldBe emptyList()
        }

        test("validation, allow-list, principal kind and disabled sending refuse before any provider call") {
            val id = app.createInquiry()
            reply(id, "   ").status shouldBe Status.UNPROCESSABLE_ENTITY
            reply(id, "x".repeat(StaffReplyBody.MAX_LENGTH + 1)).status shouldBe Status.UNPROCESSABLE_ENTITY
            reply(id, "Both", "\"expectEmptyConversation\":true,\"expectedLatestMessageId\":\"${UUID.randomUUID()}\"").status shouldBe
                Status.UNPROCESSABLE_ENTITY
            reply(id, "Neither", "\"to\":\"attacker@example.com\"").status shouldBe Status.UNPROCESSABLE_ENTITY
            reply(id, "Bad", "\"expectedLatestMessageId\":\"nope\"").status shouldBe Status.BAD_REQUEST
            reply(UUID.randomUUID().toString(), "Unknown inquiry").status shouldBe Status.NOT_FOUND
            reply(id, "Bad key", key = "not a key!").status shouldBe Status.BAD_REQUEST
            val service =
                app.provisionService("sender", setOf(FionaPermissions.CommunicationsSend, FionaPermissions.InquiriesRead))
            app
                .http(
                    Request(Method.POST, "/staff/requests/$id/conversation/replies")
                        .withBearer(app.serviceToken(service))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "service-key")
                        .body("""{"body":"Impersonation","expectEmptyConversation":true}"""),
                ).status shouldBe Status.FORBIDDEN
            provider.sendAttempts.get() shouldBe 0

            app.close()
            app =
                TestApplication.create(
                    conversationReads = provider.readConfiguration(),
                    conversationSends = provider.sendConfiguration("other@example.com"),
                )
            val denied = app.createInquiry()
            reply(denied, "Hello").let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.code() shouldBe "email_recipient_not_allowed"
            }
            app.close()
            app = TestApplication.create(conversationReads = provider.readConfiguration())
            val disabled = app.createInquiry()
            reply(disabled, "Hello").let {
                it.status shouldBe Status.SERVICE_UNAVAILABLE
                it.code() shouldBe "email_sending_disabled"
            }
            provider.sendAttempts.get() shouldBe 0
            history(disabled) shouldBe emptyList()
        }

        test("the timeline keeps commerce history when message content is forbidden or the provider is unavailable") {
            val id = app.createInquiry()
            provider.inbound(id, "Private message")
            timeline(id).conversation.state.name shouldBe "READY"
            val reader =
                app.provisionService(
                    "reader",
                    setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead),
                )
            val restricted =
                CommerceJson.asA(
                    app.http(Request(Method.GET, "/staff/requests/$id/timeline").withBearer(app.serviceToken(reader))).bodyString(),
                    StaffTimelineResponse.serializer(),
                )
            restricted.conversation.state.name shouldBe "FORBIDDEN"
            restricted.items.none { it is TimelineItemResponse.Message } shouldBe true
            provider.lookupStatus = 503
            timeline(id).conversation.state.name shouldBe "UNAVAILABLE"
            provider.lookupStatus = null
            val text =
                timeline(id)
                    .items
                    .filterIsInstance<TimelineItemResponse.Message>()
                    .single()
                    .text
            text shouldEndWith "Private message"
        }
    })
