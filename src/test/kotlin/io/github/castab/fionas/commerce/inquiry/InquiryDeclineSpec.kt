package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.events.FIONA_EVENT_PRODUCER
import io.github.castab.fionas.commerce.events.INQUIRY_DECLINED_SUBJECT
import io.github.castab.fionas.commerce.events.INQUIRY_DECLINED_TYPE
import io.github.castab.fionas.commerce.events.INQUIRY_PERSISTED_TYPE
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Aggregate
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Customer
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Decline
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Estimate
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Inquiry
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1Principal
import io.github.castab.fionas.commerce.events.InquiryDeclinedV1RequestedService
import io.github.castab.fionas.commerce.events.InquiryPersistedV3
import io.github.castab.fionas.commerce.events.InquiryPersistedV3Estimate
import io.github.castab.fionas.commerce.events.InquiryPersistedV3RequestedService
import io.github.castab.fionas.commerce.events.validateInquiryEventContract
import io.github.castab.fionas.commerce.financial.CreateChangeOrder
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.FirstSnapshotStage
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.InquiryProposal
import io.github.castab.fionas.commerce.financial.InquiryProposalRepository
import io.github.castab.fionas.commerce.financial.InquiryProposals
import io.github.castab.fionas.commerce.financial.IssueInquiryProposal
import io.github.castab.fionas.commerce.financial.IssueInvoice
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.PreviewInquiryQuote
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.materialize
import io.github.castab.fionas.commerce.testing.newLinesProposal
import io.github.castab.fionas.commerce.testing.proposalId
import io.github.castab.fionas.commerce.testing.sqlState
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InquiryDeclineSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        val owners = JdbiInquiryFinancialDocumentRepository()
        val authorship = JdbiFinancialDocumentAuthorshipRepository()
        val declines = JdbiInquiryDeclineRepository()
        val history = JdbiInquiryProposalRepository()
        val usd = Currency.getInstance("USD")
        val terms = DepositTerms.Fixed(Money(BigDecimal("50.00"), usd))

        fun newInquiry() = InquiryId(UUID.fromString(app.createInquiry()))

        fun canonical(id: InquiryId) = UUID.fromString(app.initialEstimateOf(id.value.toString()))

        fun decline(
            associations: InquiryFinancialDocumentRepository = owners,
            repository: InquiryDeclineRepository = declines,
            customers: CustomerRepository = JdbiCustomerRepository(),
        ) = DeclineInquiry(
            app.transactor,
            JdbiInquiryRepository(),
            customers,
            associations,
            app.context.financialLedger,
            JdbiInquiryFulfillmentRepository(),
            repository,
            app.context.outbox,
            testClock,
        )

        fun command(
            id: InquiryId,
            version: Int = 1,
            reason: String = "  Requested date is unavailable.  ",
        ) = DeclineInquiry.Command(id, Version.of(version), DeclineReason.of(reason), app.adminId)

        fun proposals(
            associations: InquiryFinancialDocumentRepository = owners,
            repository: InquiryProposalRepository = history,
        ) = InquiryProposals(
            app.context.financialLedger,
            associations,
            authorship,
            repository,
            JdbiInquiryServicePlanRepository(),
            testClock,
            declines,
        )

        fun issue(
            id: InquiryId,
            associations: InquiryFinancialDocumentRepository = owners,
            repository: InquiryProposalRepository = history,
        ) = IssueInquiryProposal(app.transactor, proposals(associations, repository))(
            IssueInquiryProposal.Command(id, Version.INITIAL, terms, app.adminId),
        )

        fun stored(id: InquiryId) = app.transactor.inTransaction { declines.find(it, id) }

        fun lifecycle(id: InquiryId) =
            app.transactor.inTransaction {
                ReadInquiryLifecycle(app.context.financialLedger, owners, JdbiInquiryFulfillmentRepository(), declines).read(it, id)
            }

        /** The event types recorded for the inquiry aggregate, in authoritative sequence order. */
        fun events(id: InquiryId) =
            app.database.strings(
                "SELECT event_type || ':' || aggregate_sequence FROM commerce.outbox_events " +
                    "WHERE aggregate_id = '${id.value}' ORDER BY aggregate_sequence",
            )

        fun versions(id: InquiryId) =
            app.context.financialLedger
                .history(canonical(id))
                .map { it.version.number }

        fun observedOwners(pid: AtomicInteger) =
            object : InquiryFinancialDocumentRepository by owners {
                override fun lockInquiryOf(
                    transaction: Transaction,
                    documentId: UUID,
                ): InquiryId? {
                    pid.compareAndSet(
                        0,
                        transaction.handle
                            .createQuery("SELECT pg_backend_pid()")
                            .mapTo(Int::class.java)
                            .one(),
                    )
                    return owners.lockInquiryOf(transaction, documentId)
                }
            }

        fun awaitLockWait(pid: AtomicInteger) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (pid.get() == 0 ||
                app.database.strings("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ${pid.get()}").singleOrNull() != "Lock"
            ) {
                check(System.nanoTime() < deadline) { "Contender did not wait on the canonical association" }
                Thread.yield()
            }
        }

        /** Declines through a repository that pauses after the insert, inside the open transaction. */
        fun pausedDecline(
            id: InquiryId,
            entered: CountDownLatch,
            resume: CountDownLatch,
        ): CompletableFuture<InquiryLifecycle> {
            val pausing =
                object : InquiryDeclineRepository by declines {
                    override fun insert(
                        transaction: Transaction,
                        decline: InquiryDecline,
                    ) {
                        declines.insert(transaction, decline)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                    }
                }
            return CompletableFuture.supplyAsync { decline(repository = pausing)(command(id)) }
        }

        context("lifecycle projection") {
            val estimate =
                FinancialDocument.Estimate.create(
                    UUID.randomUUID(),
                    listOf(LineItem(UUID.randomUUID(), "Service", null, null, Money(BigDecimal("100"), usd), Money.zero(usd))),
                )
            val user = UserId(UUID.randomUUID())
            val inquiry = InquiryId(UUID.randomUUID())

            fun declineOf(document: FinancialDocument) =
                InquiryDecline(inquiry, document.reference, STORED_INSTANT, user, DeclineReason("No capacity."))

            test("Estimate without a decline is REQUESTED and with its exact decline DECLINED, keeping the canonical identity") {
                InquiryLifecycle.project(estimate, null).stage shouldBe InquiryStage.REQUESTED
                val declined = InquiryLifecycle.project(estimate, null, declineOf(estimate))
                declined.stage shouldBe InquiryStage.DECLINED
                declined.documentId shouldBe estimate.id
                declined.decline shouldBe declineOf(estimate)
                declined.fulfillment shouldBe null
            }

            test("a decline beside a Quote, an Invoice, fulfillment or another Estimate snapshot is contradictory and fails loudly") {
                val quote = estimate.toQuote()
                val invoice = quote.toInvoice()
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(quote, null, declineOf(estimate)) }
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(quote, null, declineOf(quote)) }
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(invoice, null, declineOf(invoice)) }
                val served = InquiryFulfillment(InquiryMilestone(STORED_INSTANT, user))
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(invoice, served, declineOf(invoice)) }
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(estimate, served, declineOf(estimate)) }
                val other =
                    FinancialDocument.Estimate.create(
                        UUID.randomUUID(),
                        listOf(LineItem(UUID.randomUUID(), "Other", null, null, Money(BigDecimal("1"), usd), Money.zero(usd))),
                    )
                shouldThrow<IllegalStateException> { InquiryLifecycle.project(estimate, null, declineOf(other)) }
                InquiryLifecycle.project(invoice, served).stage shouldBe InquiryStage.SERVED
            }

            test("a decline reason is trimmed, required and bounded; internal punctuation is preserved") {
                DeclineReason.of("  Outside our service area; sorry!\n").value shouldBe "Outside our service area; sorry!"
                DeclineReason.of("x".repeat(500)).value.length shouldBe 500
                listOf("", "   ", "\n\t", "x".repeat(501), " ${"x".repeat(501)} ").forEach { reason ->
                    shouldThrow<IllegalArgumentException> { DeclineReason.of(reason) }
                }
                shouldThrow<IllegalArgumentException> { DeclineReason(" padded") }
            }
        }

        test("a REQUESTED inquiry is declined with server time, authenticated staff and the trimmed reason; history is unchanged") {
            val id = newInquiry()
            val document = canonical(id)
            val inquiryRow = app.database.strings("SELECT row_to_json(i)::text FROM fionas.inquiries i WHERE id = '${id.value}'")
            val customers = app.database.strings("SELECT row_to_json(c)::text FROM fionas.customers c ORDER BY id")
            val before = app.context.financialLedger.history(document)
            val result = decline()(command(id))
            result.stage shouldBe InquiryStage.DECLINED
            result.documentId shouldBe document
            val recorded =
                InquiryDecline(id, before.single().reference, STORED_INSTANT, app.adminId, DeclineReason("Requested date is unavailable."))
            result.decline shouldBe recorded
            stored(id) shouldBe recorded
            lifecycle(id).stage shouldBe InquiryStage.DECLINED
            app.context.financialLedger.history(document) shouldBe before
            app.database.strings("SELECT row_to_json(i)::text FROM fionas.inquiries i WHERE id = '${id.value}'") shouldBe inquiryRow
            app.database.strings("SELECT row_to_json(c)::text FROM fionas.customers c ORDER BY id") shouldBe customers
            app.database.count("fionas.inquiry_fulfillment") shouldBe 0
        }

        test("one committed decline appends one complete InquiryDeclined v1 on the inquiry aggregate after InquiryPersisted v3") {
            val id = newInquiry()
            val persisted =
                Json.decodeFromString(
                    InquiryPersistedV3.serializer(),
                    app.database
                        .strings("SELECT payload::text FROM commerce.outbox_events WHERE aggregate_id = '${id.value}'")
                        .single(),
                )
            decline()(command(id))
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1", "$INQUIRY_DECLINED_TYPE:2")
            val row =
                app.database
                    .strings(
                        "SELECT id || '|' || producer_id || '|' || aggregate_type || '|' || destination || '|' || schema_version " +
                            "FROM commerce.outbox_events WHERE aggregate_id = '${id.value}' AND event_type = '$INQUIRY_DECLINED_TYPE'",
                    ).single()
                    .split('|')
            row.drop(1) shouldBe listOf(FIONA_EVENT_PRODUCER, "inquiry", INQUIRY_DECLINED_SUBJECT, "1")
            val payload =
                app.database
                    .strings(
                        "SELECT payload::text FROM commerce.outbox_events WHERE aggregate_id = '${id.value}' AND event_type = '$INQUIRY_DECLINED_TYPE'",
                    ).single()
            validateInquiryEventContract(payload, "InquiryDeclinedV1")
            val event = Json.decodeFromString(InquiryDeclinedV1.serializer(), payload)
            event.id shouldBe row.first()
            event.schemaVersion shouldBe 1
            event.type shouldBe INQUIRY_DECLINED_TYPE
            event.source shouldBe FIONA_EVENT_PRODUCER
            event.occurredAt shouldBe STORED_INSTANT.toString()
            event.aggregate shouldBe InquiryDeclinedV1Aggregate("inquiry", id.value.toString(), 2)
            event.aggregate.id shouldBe persisted.aggregate.id
            event.data.decline shouldBe
                InquiryDeclinedV1Decline(
                    STORED_INSTANT.toString(),
                    InquiryDeclinedV1Principal("USER", app.adminId.value.toString()),
                    "Requested date is unavailable.",
                )
            event.data.inquiry shouldBe
                InquiryDeclinedV1Inquiry(
                    id.value.toString(),
                    STORED_INSTANT.toString(),
                    "DECLINED",
                    persisted.data.inquiry.eventDate,
                    persisted.data.inquiry.eventType,
                    persisted.data.inquiry.zipCode,
                    persisted.data.inquiry.message,
                )
            event.data.customer shouldBe
                InquiryDeclinedV1Customer(persisted.data.customer.id, persisted.data.customer.name, persisted.data.customer.email)
            // The same facts as the persisted snapshot, in the decline event's own DTOs.
            Json.encodeToJsonElement(InquiryDeclinedV1RequestedService.serializer(), event.data.requestedService) shouldBe
                Json.encodeToJsonElement(InquiryPersistedV3RequestedService.serializer(), persisted.data.requestedService)
            Json.encodeToJsonElement(InquiryDeclinedV1Estimate.serializer(), event.data.estimate) shouldBe
                Json.encodeToJsonElement(InquiryPersistedV3Estimate.serializer(), persisted.data.initialEstimate)
            event.data.estimate.total shouldBe "681.25"
        }

        test("the decline event describes the exact Estimate version declined after staff edits") {
            val id = newInquiry()
            val document = canonical(id)
            CreateChangeOrder(
                app.transactor,
                app.context.financialLedger,
                owners,
                authorship,
                JdbiInquiryFulfillmentRepository(),
                testClock,
            )(
                CreateChangeOrder.Command(document, Version.INITIAL, newLinesProposal(listOf(CHURROS)), app.adminId),
            )
            shouldThrow<CommerceFailure.Conflict> { decline()(command(id, version = 1)) }
            decline()(command(id, version = 2)).decline!!.estimate.version shouldBe Version.of(2)
            val payload =
                app.database
                    .strings(
                        "SELECT payload::text FROM commerce.outbox_events WHERE aggregate_id = '${id.value}' AND event_type = '$INQUIRY_DECLINED_TYPE'",
                    ).single()
            val estimate = Json.decodeFromString(InquiryDeclinedV1.serializer(), payload).data.estimate
            estimate.version shouldBe 2
            estimate.lineItems.single().description shouldBe CHURROS.description
        }

        test("unknown, stale and repeated declines conflict or are not found, writing nothing and appending no event") {
            shouldThrow<CommerceFailure.NotFound> { decline()(command(InquiryId(UUID.randomUUID()))) }
            val id = newInquiry()
            shouldThrow<CommerceFailure.Conflict> { decline()(command(id, version = 2)) }
            stored(id) shouldBe null
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1")
            val first = decline()(command(id))
            shouldThrow<CommerceFailure.IllegalTransition> { decline()(command(id, reason = "A different reason")) }
            stored(id) shouldBe first.decline
            app.database.count("fionas.inquiry_declines WHERE inquiry_id = '${id.value}'") shouldBe 1
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1", "$INQUIRY_DECLINED_TYPE:2")
        }

        test("QUOTED, BOOKED, SERVED and CLOSED inquiries reject decline as illegal transitions without writing") {
            val id = newInquiry()
            val document = canonical(id)

            fun rejected(version: Int) {
                shouldThrow<CommerceFailure.IllegalTransition> { decline()(command(id, version)) }
                stored(id) shouldBe null
                events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1")
            }
            issue(id)
            lifecycle(id).stage shouldBe InquiryStage.QUOTED
            rejected(2)
            app
                .adminPost(
                    "/financial-documents/$document/payments",
                    """{"documentVersion":2,"amount":"50","method":"CARD","expectedProposalId":"${app.proposalId(document)!!.value}"}""",
                ).status shouldBe Status.CREATED
            lifecycle(id).stage shouldBe InquiryStage.BOOKED
            rejected(3)
            app.adminPost("/inquiries/${id.value}/served").status shouldBe Status.OK
            rejected(3)
            app
                .adminPost(
                    "/financial-documents/$document/payments",
                    """{"documentVersion":3,"amount":"631.25","method":"CARD"}""",
                ).status shouldBe
                Status.CREATED
            app.adminPost("/inquiries/${id.value}/close").status shouldBe Status.OK
            lifecycle(id).stage shouldBe InquiryStage.CLOSED
            rejected(3)
        }

        test("a failure after the decline insert rolls back the decline and its event together") {
            val id = newInquiry()
            val failing =
                object : CustomerRepository by JdbiCustomerRepository() {
                    override fun findById(
                        transaction: Transaction,
                        id: CustomerId,
                    ): Customer? = error("injected failure after the decline insert")
                }
            shouldThrow<IllegalStateException> { decline(customers = failing)(command(id)) }
            stored(id) shouldBe null
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1")
            app.database.execute(
                """
                CREATE FUNCTION fionas.reject_decline_outbox() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'injected outbox append failure'; END; ${'$'}${'$'};
                CREATE TRIGGER reject_decline_outbox AFTER INSERT ON commerce.outbox_events
                FOR EACH ROW EXECUTE FUNCTION fionas.reject_decline_outbox();
                """.trimIndent(),
            )
            try {
                shouldThrowAny { decline()(command(id)) }
                stored(id) shouldBe null
            } finally {
                app.database.execute(
                    "DROP TRIGGER reject_decline_outbox ON commerce.outbox_events; DROP FUNCTION fionas.reject_decline_outbox()",
                )
            }
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1")
            decline()(command(id)).stage shouldBe InquiryStage.DECLINED
            events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1", "$INQUIRY_DECLINED_TYPE:2")
        }

        test("the table admits one trimmed, bounded decline per inquiry by a real staff user, on its own canonical snapshot") {
            val id = newInquiry()
            val document = canonical(id)
            val decline =
                InquiryDecline(
                    id,
                    app.context.financialLedger
                        .latest(document)
                        .reference,
                    STORED_INSTANT,
                    app.adminId,
                    DeclineReason("No."),
                )
            app.transactor.inTransaction { declines.findAll(it, emptyList()) } shouldBe emptyMap()
            val service = ServiceId(UUID.randomUUID())
            shouldThrowAny {
                app.transactor.inTransaction { declines.insert(it, decline.copy(declinedBy = UserId(service.value))) }
            }.sqlState() shouldBe "23503"

            fun raw(reason: String) =
                "INSERT INTO fionas.inquiry_declines VALUES ('${id.value}', '$document', 1, now(), '${app.adminId.value}', '$reason')"
            listOf("", " padded", "x".repeat(501)).forEach { reason ->
                shouldThrowAny { app.database.execute(raw(reason)) }.sqlState() shouldBe "23514"
            }
            app.transactor.inTransaction { declines.insert(it, decline) }
            shouldThrow<CommerceFailure.Conflict> { app.transactor.inTransaction { declines.insert(it, decline) } }
            app.transactor.inTransaction { declines.findAll(it, listOf(id, InquiryId(UUID.randomUUID()))) } shouldBe mapOf(id to decline)
        }

        context("concurrency on the canonical association") {
            test("decline winning makes a waiting initial Quote issuance observe it and publish nothing") {
                val id = newInquiry()
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val declining = pausedDecline(id, entered, resume)
                try {
                    entered.await(30, TimeUnit.SECONDS) shouldBe true
                    val pid = AtomicInteger()
                    val issuing =
                        CompletableFuture.supplyAsync {
                            runCatching {
                                issue(
                                    id,
                                    associations = observedOwners(pid),
                                )
                            }.exceptionOrNull()
                        }
                    awaitLockWait(pid)
                    issuing.isDone shouldBe false
                    resume.countDown()
                    declining.get(30, TimeUnit.SECONDS).stage shouldBe InquiryStage.DECLINED
                    issuing.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.IllegalTransition>()
                } finally {
                    resume.countDown()
                }
                versions(id) shouldBe listOf(1)
                app.transactor.inTransaction { history.latest(it, id) } shouldBe null
                app.context.financialLedger
                    .depositRequirementHistory(canonical(id))
                    .size shouldBe 0
                lifecycle(id).stage shouldBe InquiryStage.DECLINED
                events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1", "$INQUIRY_DECLINED_TYPE:2")
            }

            test("Quote issuance winning makes a waiting decline observe the Quote and record nothing") {
                val id = newInquiry()
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val pausing =
                    object : InquiryProposalRepository by history {
                        override fun append(
                            transaction: Transaction,
                            proposal: InquiryProposal,
                        ) {
                            history.append(transaction, proposal)
                            entered.countDown()
                            check(resume.await(30, TimeUnit.SECONDS))
                        }
                    }
                val issuing = CompletableFuture.supplyAsync { issue(id, repository = pausing) }
                try {
                    entered.await(30, TimeUnit.SECONDS) shouldBe true
                    val pid = AtomicInteger()
                    val declining =
                        CompletableFuture.supplyAsync {
                            runCatching {
                                decline(
                                    associations = observedOwners(pid),
                                )(command(id))
                            }.exceptionOrNull()
                        }
                    awaitLockWait(pid)
                    declining.isDone shouldBe false
                    resume.countDown()
                    issuing.get(30, TimeUnit.SECONDS)
                    // The waiter observes the committed Quote: its reviewed Estimate version is stale.
                    declining.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                } finally {
                    resume.countDown()
                }
                stored(id) shouldBe null
                lifecycle(id).stage shouldBe InquiryStage.QUOTED
                events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1")
                shouldThrow<CommerceFailure.IllegalTransition> { decline()(command(id, version = 2)) }
            }

            test("two concurrent declines cannot both succeed; the loser appends no event") {
                val id = newInquiry()
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val first = pausedDecline(id, entered, resume)
                try {
                    entered.await(30, TimeUnit.SECONDS) shouldBe true
                    val pid = AtomicInteger()
                    val second =
                        CompletableFuture.supplyAsync {
                            runCatching { decline(associations = observedOwners(pid))(command(id, reason = "Second")) }.exceptionOrNull()
                        }
                    awaitLockWait(pid)
                    resume.countDown()
                    first
                        .get(30, TimeUnit.SECONDS)
                        .decline!!
                        .reason.value shouldBe "Requested date is unavailable."
                    second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.IllegalTransition>()
                } finally {
                    resume.countDown()
                }
                stored(id)!!.reason.value shouldBe "Requested date is unavailable."
                events(id) shouldBe listOf("$INQUIRY_PERSISTED_TYPE:1", "$INQUIRY_DECLINED_TYPE:2")
            }

            test("a RELATED lineage created concurrently waits on the canonical association and observes the decline") {
                val id = newInquiry()
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val declining = pausedDecline(id, entered, resume)
                try {
                    entered.await(30, TimeUnit.SECONDS) shouldBe true
                    val pid = AtomicInteger()
                    val creating =
                        CompletableFuture.supplyAsync {
                            runCatching {
                                CreateInquiryFinancialDocument(
                                    app.transactor,
                                    JdbiInquiryRepository(),
                                    app.context.financialLedger,
                                    observedOwners(pid),
                                    authorship,
                                    app.materialize(),
                                    testClock,
                                    declines,
                                )(
                                    CreateInquiryFinancialDocument.Command(
                                        id,
                                        FirstSnapshotStage.QUOTE,
                                        listOf(CHURROS.priced()),
                                        app.adminId,
                                    ),
                                )
                            }.exceptionOrNull()
                        }
                    awaitLockWait(pid)
                    resume.countDown()
                    declining.get(30, TimeUnit.SECONDS)
                    creating.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.IllegalTransition>()
                } finally {
                    resume.countDown()
                }
                app.database.count("fionas.inquiry_financial_documents WHERE inquiry_id = '${id.value}'") shouldBe 1
            }
        }

        test("after decline no lineage of the inquiry progresses, while reads, settlement and deposit withdrawal remain") {
            val id = newInquiry()
            val document = canonical(id)
            val createDocument =
                CreateInquiryFinancialDocument(
                    app.transactor,
                    JdbiInquiryRepository(),
                    app.context.financialLedger,
                    owners,
                    authorship,
                    app.materialize(),
                    testClock,
                    declines,
                )

            fun related(stage: FirstSnapshotStage) =
                createDocument(CreateInquiryFinancialDocument.Command(id, stage, listOf(CHURROS.priced()), app.adminId)).latest.document
            val relatedEstimate = related(FirstSnapshotStage.ESTIMATE)
            val relatedQuote = related(FirstSnapshotStage.QUOTE)
            val setDeposit = SetDepositRequirement(app.transactor, app.context.financialLedger, owners, authorship, history, declines)
            setDeposit(SetDepositRequirement.Command(relatedQuote.id, Version.INITIAL, null, terms))
            decline()(command(id))
            val changeOrder =
                CreateChangeOrder(
                    app.transactor,
                    app.context.financialLedger,
                    owners,
                    authorship,
                    JdbiInquiryFulfillmentRepository(),
                    testClock,
                    declines,
                )
            val rejections =
                listOf(
                    { issue(id) },
                    {
                        PreviewInquiryQuote(app.transactor, app.context.financialLedger, owners, history, declines)(
                            PreviewInquiryQuote.Command(id, Version.INITIAL, newLinesProposal(listOf(CHURROS)), null, terms),
                        )
                    },
                    { related(FirstSnapshotStage.INVOICE) },
                    { changeOrder(CreateChangeOrder.Command(document, Version.INITIAL, newLinesProposal(listOf(CHURROS)), app.adminId)) },
                    {
                        changeOrder(
                            CreateChangeOrder.Command(
                                relatedEstimate.id,
                                Version.INITIAL,
                                newLinesProposal(acceptanceLines()),
                                app.adminId,
                            ),
                        )
                    },
                    {
                        IssueQuote(
                            app.transactor,
                            app.context.financialLedger,
                            owners,
                            authorship,
                            declines,
                        )(relatedEstimate.id, Version.INITIAL)
                    },
                    {
                        IssueInvoice(
                            app.transactor,
                            app.context.financialLedger,
                            owners,
                            authorship,
                            declines,
                        )(relatedQuote.id, Version.INITIAL)
                    },
                    {
                        setDeposit(
                            SetDepositRequirement.Command(
                                relatedQuote.id,
                                Version.INITIAL,
                                app.context.financialLedger
                                    .depositRequirementHistory(relatedQuote.id)
                                    .last()
                                    .requirement.revision,
                                DepositTerms.Fixed(Money(BigDecimal("25.00"), usd)),
                            ),
                        )
                    },
                )
            val lineages =
                app.database.strings(
                    "SELECT document_id::text FROM fionas.inquiry_financial_documents WHERE inquiry_id = '${id.value}'",
                )

            fun snapshot() =
                lineages.map {
                    app.context.financialLedger.history(UUID.fromString(it)) to
                        app.context.financialLedger
                            .depositRequirementHistory(UUID.fromString(it))
                            .size
                }
            val before = snapshot()
            rejections.forEach { action ->
                shouldThrow<CommerceFailure.IllegalTransition> { action() }
            }
            snapshot() shouldBe before
            app.database.count("fionas.inquiry_financial_documents WHERE inquiry_id = '${id.value}'") shouldBe 3
            lifecycle(id).stage shouldBe InquiryStage.DECLINED
            // Reads and settlement of the existing RELATED Quote stay available.
            app.adminGet("/inquiries/${id.value}").status shouldBe Status.OK
            app.adminGet("/staff/requests/${id.value}").status shouldBe Status.OK
            app.adminGet("/inquiries/${id.value}/financial-documents").status shouldBe Status.OK
            app.adminGet("/financial-documents/$document/history").status shouldBe Status.OK
            app
                .adminPost(
                    "/financial-documents/${relatedQuote.id}/payments",
                    """{"documentVersion":1,"amount":"10","method":"CARD"}""",
                ).status shouldBe
                Status.CREATED
            val revision =
                app.context.financialLedger
                    .depositRequirementHistory(relatedQuote.id)
                    .last()
                    .requirement.revision.number
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/${relatedQuote.id}/deposit-requirement",
                    """{"expectedRequirementRevision":$revision}""",
                ).status shouldBe Status.OK
        }

        test("a declined inquiry leaves every work queue and count, even with outstanding customer email, and stays listed") {
            fun dashboard() = Json.parseToJsonElement(app.adminGet("/staff/dashboard").bodyString()).jsonObject

            fun queued(queue: String) =
                dashboard()
                    .getValue("workQueue")
                    .jsonObject
                    .getValue(queue)
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("inquiryId")
                            .jsonPrimitive.content
                    }

            fun count(name: String) =
                dashboard()
                    .getValue("summary")
                    .jsonObject
                    .getValue(name)
                    .jsonPrimitive.content
                    .toInt()
            val declined = newInquiry()
            val active = newInquiry()
            val communications = RecordInquiryCommunication(app.transactor, JdbiInquiryCommunicationRepository(), testClock)
            listOf(declined, active).forEach { communications.customerEmailReceived(it, STORED_INSTANT) }
            val ids = listOf(declined, active).map { it.value.toString() }
            listOf("needsReply", "needsQuote").forEach { queue -> queued(queue).filter { it in ids }.toSet() shouldBe ids.toSet() }
            val newBefore = count("new")
            decline()(command(declined))
            queued("needsReply").filter { it in ids } shouldBe listOf(active.value.toString())
            queued("needsQuote").filter { it in ids } shouldBe listOf(active.value.toString())
            queued("needsResolution").filter { it in ids } shouldBe emptyList()
            count("new") shouldBe newBefore - 1
            // History is retained: the email stays unacknowledged, and the inquiry is still listed and readable.
            app.transactor
                .inTransaction { JdbiInquiryCommunicationRepository().attentionFor(it, listOf(declined)) }
                .getValue(declined)
                .unacknowledgedSince shouldBe STORED_INSTANT
            val listed =
                Json
                    .parseToJsonElement(app.adminGet("/inquiries?limit=100").bodyString())
                    .jsonObject
                    .getValue("inquiries")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }
            listed.contains(declined.value.toString()) shouldBe true
            app.adminGet("/inquiries/${declined.value}").status shouldBe Status.OK
        }
    })
