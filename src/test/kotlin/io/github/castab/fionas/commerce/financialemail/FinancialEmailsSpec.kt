package io.github.castab.fionas.commerce.financialemail

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.conversation.MessageTextVisibility
import io.github.castab.fionas.commerce.http.DepositTermsRequest
import io.github.castab.fionas.commerce.http.FinancialEmailKindResponse
import io.github.castab.fionas.commerce.http.FinancialEmailPreviewResponse
import io.github.castab.fionas.commerce.http.FinancialEmailSendResponse
import io.github.castab.fionas.commerce.http.InvoiceRevisionPreviewRequest
import io.github.castab.fionas.commerce.http.InvoiceRevisionPreviewResponse
import io.github.castab.fionas.commerce.http.InvoiceRevisionRequest
import io.github.castab.fionas.commerce.http.PaymentAccessResponse
import io.github.castab.fionas.commerce.http.PreviewFinancialEmailRequest
import io.github.castab.fionas.commerce.http.ProposedLineRequest
import io.github.castab.fionas.commerce.http.QuoteRevisionPreviewRequest
import io.github.castab.fionas.commerce.http.QuoteRevisionPreviewResponse
import io.github.castab.fionas.commerce.http.ReviseInquiryQuoteProposalRequest
import io.github.castab.fionas.commerce.http.SendFinancialEmailRequest
import io.github.castab.fionas.commerce.http.ServicePlanActionRequest
import io.github.castab.fionas.commerce.http.ServicePlanItemDto
import io.github.castab.fionas.commerce.http.ServicePlanRequest
import io.github.castab.fionas.commerce.http.StaffReplyOutcomeResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.http.StaffTimelineResponse
import io.github.castab.fionas.commerce.http.TimelineItemResponse
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.payments.FakeStripe
import io.github.castab.fionas.commerce.payments.testStripeConfiguration
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.StubConversationProvider
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FinancialEmailsSpec :
    FunSpec({
        lateinit var app: TestApplication
        lateinit var provider: StubConversationProvider
        val config = testStripeConfiguration()
        beforeSpec {
            provider = StubConversationProvider()
            app =
                TestApplication.create(
                    conversationReads = provider.readConfiguration(),
                    conversationSends = provider.sendConfiguration(),
                    stripe = config,
                    stripeGateway = FakeStripe(config),
                )
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants + FionaPermissions.PaymentAccessManage)
        }
        afterSpec {
            app.close()
            provider.close()
        }
        afterTest {
            provider.lookupStatus = null
            provider.onSend = null
            provider.replayDelayMillis = 0
            provider.replayStatus = null
            provider.onLookup = null
            provider.script.clear()
        }

        fun path(id: String) = "/staff/requests/$id"

        fun detail(id: String) = CommerceJson.asA(app.adminGet(path(id)).bodyString(), StaffRequestResponse.serializer())

        fun retainedLines(id: String) =
            detail(id).financial.lines.map {
                ProposedLineRequest(it.id, null, it.description, it.subDescription, it.quantity, it.unitPrice, it.taxAmount, it.currency)
            }

        fun initial(
            email: String = "customer@example.com",
            name: String = "Jane Doe",
        ): String =
            app.createInquiry(email = email, name = name).also { id ->
                app
                    .adminPost(
                        path(id) + "/proposals",
                        """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                    ).status shouldBe
                    Status.OK
            }

        fun intent(
            id: String,
            message: String? = null,
        ): PreviewFinancialEmailRequest {
            val state = detail(id)
            val quote = state.financial.stage == "QUOTE"
            return PreviewFinancialEmailRequest(
                if (quote) FinancialEmailKindResponse.QUOTE else FinancialEmailKindResponse.INVOICE,
                state.financial.id,
                state.financial.version,
                checkNotNull(if (quote) state.currentQuoteRevision else state.currentInvoiceRevision),
                if (quote) checkNotNull(state.proposal).id else null,
                if (quote) checkNotNull(state.proposal).depositRequirementRevision else null,
                message,
            )
        }

        fun previewResponse(
            id: String,
            input: PreviewFinancialEmailRequest,
        ) = app.adminPost(path(id) + "/financial-emails/preview", CommerceJson.asFormatString(input))

        fun preview(
            id: String,
            input: PreviewFinancialEmailRequest,
        ): FinancialEmailPreviewResponse {
            val response = previewResponse(id, input)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            return CommerceJson.asA(response.bodyString(), FinancialEmailPreviewResponse.serializer())
        }

        fun send(
            id: String,
            input: PreviewFinancialEmailRequest,
            token: String,
            key: String = UUID.randomUUID().toString(),
            latest: String? = null,
        ): Response =
            app.http(
                Request(Method.POST, path(id) + "/financial-emails")
                    .header("Cookie", app.adminCookie)
                    .header("Origin", TEST_ORIGIN)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", key)
                    .body(CommerceJson.asFormatString(SendFinancialEmailRequest(input, token, latest, latest == null))),
            )

        fun Response.result() = CommerceJson.asA(bodyString(), FinancialEmailSendResponse.serializer())

        fun Response.code() = CommerceJson.asA(bodyString(), ErrorResponse.serializer()).code

        fun access(id: String) =
            CommerceJson.asA(app.adminGet(path(id) + "/payment-access").bodyString(), PaymentAccessResponse.serializer())

        fun timeline(id: String) = CommerceJson.asA(app.adminGet(path(id) + "/timeline").bodyString(), StaffTimelineResponse.serializer())

        fun book(id: String) {
            val state = detail(id)
            app
                .adminPost(
                    "/financial-documents/${state.financial.id}/payments",
                    """{"documentVersion":${state.financial.version},"amount":"136.25","method":"CASH","expectedProposalId":"${state.proposal!!.id}"}""",
                ).status shouldBe
                Status.CREATED
        }

        fun served(id: String) {
            book(id)
            app.adminPost("/inquiries/$id/served").status shouldBe Status.OK
        }

        fun invoicePayment(
            id: String,
            amount: String,
        ) {
            val state = detail(id)
            app
                .adminPost(
                    "/financial-documents/${state.financial.id}/payments",
                    """{"documentVersion":${state.financial.version},"amount":"$amount","method":"CASH"}""",
                ).status shouldBe
                Status.CREATED
        }

        test("Quote preview is write-free, exact, escaped, inert and uses the opening subject") {
            val id = initial()
            val before = app.database.count("fionas.inquiry_financial_emails")
            val input = intent(id, "Hello <script>alert('x')</script> & friends\n\nSecond paragraph 🍦")
            val review = preview(id, input)
            review.subject shouldBe "Your quote from Fiona's Ice Cream"
            review.text.substringBefore('\n') shouldBe "Hi Jane, here's your quote"
            review.html shouldContain "Hi Jane, here&#39;s your quote</h1>"
            review.text shouldContain "Customer: Jane Doe"
            review.text shouldContain "Your quote\n"
            review.html shouldContain ">Your quote</h2>"
            Regex("\\b(?:Quote|Invoice) v\\d+", RegexOption.IGNORE_CASE).find(review.text + review.html + review.subject) shouldBe null
            review.financial.version shouldBe input.expectedDocumentVersion
            review.businessRevision shouldBe input.expectedBusinessRevision
            review.html shouldContain "&lt;script&gt;"
            review.html shouldNotContain "<script>"
            review.html shouldContain "Second paragraph 🍦</p>"
            review.requiredPayment shouldBe "136.25"
            review.balanceAfterDeposit shouldBe "545.00"
            review.html shouldContain "$681.25"
            review.text shouldContain "$136.25"
            review.text shouldContain PREVIEW_PAYMENT_URL
            review.html shouldContain PREVIEW_PAYMENT_URL
            review.text shouldNotContain "due on"
            access(id).url shouldBe null
            app.database.count("fionas.inquiry_financial_emails") shouldBe before
        }

        test("new accepted send atomically issues a stable link; confirmed replay never sends again") {
            val id = initial()

            fun revision() =
                app.database
                    .strings("SELECT revision FROM fionas.inquiry_change_revisions WHERE inquiry_id = '$id'")
                    .single()
                    .toLong()
            val beforeReview = revision()
            val input = intent(id)
            val review = preview(id, input)
            revision() shouldBe beforeReview
            val key = UUID.randomUUID().toString()
            val response = send(id, input, review.reviewToken, key)
            response.status shouldBe Status.CREATED
            val result = response.result()
            result.outcome shouldBe StaffReplyOutcomeResponse.ACCEPTED
            result.sendState shouldBe "accepted"
            val url = checkNotNull(access(id).url)
            val message =
                provider.conversations
                    .getValue(id)
                    .messages
                    .single()
            message.text shouldContain url
            checkNotNull(message.html) shouldContain "href=\"$url\""
            response.bodyString() shouldNotContain url.substringAfter('#')
            val attempts = provider.sendAttempts.get()
            val afterAcceptance = revision()
            (afterAcceptance > beforeReview) shouldBe true
            val replay = send(id, input, review.reviewToken, key)
            replay.status shouldBe Status.OK
            replay.result().sendId shouldBe result.sendId
            replay.result().replayed shouldBe true
            provider.sendAttempts.get() shouldBe attempts
            revision() shouldBe afterAcceptance
            access(id).url shouldBe url
            val changed = send(id, input.copy(message = "Changed"), review.reviewToken, key)
            changed.status shouldBe Status.CONFLICT
            changed.code() shouldBe "IDEMPOTENCY_KEY_REUSED"
        }

        test("READY preserves subject, thread identity and active access; financial send never acknowledges inbound") {
            val id = initial()
            val inbound = provider.inbound(id, "Please review this request")
            val communications = JdbiInquiryCommunicationRepository()
            app.transactor.inTransaction { transaction ->
                communications.append(
                    transaction,
                    InquiryCommunication(
                        InquiryCommunicationId(UUID.randomUUID()),
                        InquiryId(UUID.fromString(id)),
                        InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                        java.time.Instant.parse("2026-10-01T12:00:00Z"),
                        null,
                    ),
                )
            }
            app.adminPost(path(id) + "/payment-access").status shouldBe Status.OK
            val original = access(id).url
            val input = intent(id)
            val review = preview(id, input)
            review.subject shouldBe "Your inquiry"
            val response = send(id, input, review.reviewToken, latest = inbound.toString())
            response.status shouldBe Status.CREATED
            response.result().conversationId shouldBe
                provider.conversations
                    .getValue(id)
                    .id
                    .toString()
            provider.requests.last().containsKey("topic") shouldBe false
            provider.requests.last().containsKey("subject") shouldBe false
            access(id).url shouldBe original
            detail(id).communication.needsReply shouldBe true
        }

        test("authorized timeline includes the complete financial email and quoted payment links with accepted and delivery metadata") {
            val id = initial()
            val input = intent(id)
            send(id, input, preview(id, input).reviewToken).status shouldBe Status.CREATED
            val secret = checkNotNull(access(id).url).substringAfter('#')
            provider.inbound(id, "Thanks!\n> ${provider.conversations.getValue(id).messages.first().text}\nBare token: $secret")
            val response = app.adminGet(path(id) + "/timeline")
            response.bodyString() shouldContain secret
            val read = timeline(id)
            val messages = read.items.filterIsInstance<TimelineItemResponse.Message>()
            messages.size shouldBe 2
            messages.all {
                it.text != null && !it.textOmittedForPaymentSecurity && it.textVisibility == MessageTextVisibility.READABLE
            } shouldBe
                true
            val providerMessages =
                provider.conversations
                    .getValue(id)
                    .messages
                    .associateBy { it.id.toString() }
            messages.forEach { it.text shouldBe providerMessages.getValue(it.messageId).text }
            messages.single { it.direction == "outbound" }.financialEmail!!.sendId shouldBe read.financialEmails.single().sendId
            read.financialEmails.single().current shouldBe true
            read.financialEmails.single().acceptedAt shouldNotBe null
            read.financialEmails.single().deliveryState shouldBe "unknown"
        }

        test("Quote and Invoice greetings share the escaped first name while retaining the authoritative full customer name") {
            listOf("  Sofia   Martinez  " to "Sofia", "José García" to "José", "<Ana&> López" to "<Ana&>").forEachIndexed {
                index,
                (name, first),
                ->
                val id = initial(email = "greeting-$index@example.com", name = name)
                val quote = preview(id, intent(id))
                quote.text.substringBefore('\n') shouldBe "Hi $first, here's your quote"
                quote.html shouldContain "${escapeEmailHtml("Hi $first, here's your quote")}</h1>"
                served(id)
                val invoice = preview(id, intent(id))
                invoice.text.substringBefore('\n') shouldBe "Thank you, $first — here's your invoice"
                invoice.html shouldContain "${escapeEmailHtml("Thank you, $first — here's your invoice")}</h1>"
                invoice.text shouldContain "Customer: ${name.trim()}"
            }
        }

        test("customer and ordinary staff replies after a Quote retain their complete provider text") {
            val id = initial()
            val input = intent(id)
            send(id, input, preview(id, input).reviewToken).status shouldBe Status.CREATED
            val latest = provider.inbound(id, "Could we replace the almonds with chopped peanuts?")
            app
                .http(
                    Request(Method.POST, path(id) + "/conversation/replies")
                        .header("Cookie", app.adminCookie)
                        .header("Origin", TEST_ORIGIN)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .body("""{"body":"Yes, we can do that.","expectedLatestMessageId":"$latest"}"""),
                ).status shouldBe Status.CREATED
            val messages = timeline(id).items.filterIsInstance<TimelineItemResponse.Message>()
            messages.size shouldBe 3
            val providerMessages =
                provider.conversations
                    .getValue(id)
                    .messages
                    .associateBy { it.id.toString() }
            messages.forEach {
                it.text shouldBe providerMessages.getValue(it.messageId).text
                it.textVisibility shouldBe MessageTextVisibility.READABLE
                it.textOmittedForPaymentSecurity shouldBe false
            }
        }

        test(
            "authorized Quote and Invoice correspondence preserves quoted, encoded, forwarded and wrapped payment links verbatim",
        ) {
            listOf(false, true).forEach { invoice ->
                val id = initial()
                if (invoice) served(id)
                val input = intent(id)
                send(id, input, preview(id, input).reviewToken).status shouldBe Status.CREATED
                val url = checkNotNull(access(id).url)
                val secret = url.substringAfter('#')
                val original =
                    provider.conversations
                        .getValue(id)
                        .messages
                        .single()
                        .text
                val fixtures =
                    listOf(
                        "Thanks!\n> $original",
                        "Please change the topping.\nOn Thu, Nov 5, Fiona's wrote:\n$original",
                        "Can we change it?\nFrom: Fiona's\nSent: Thursday\nTo: Customer\nSubject: Quote\n$original",
                        "---------- Forwarded message ---------\n$url",
                        "Please see\n${url.chunked(11).joinToString("\n ")}",
                        java.net.URLEncoder.encode(url, Charsets.UTF_8),
                        url.map { "&#${it.code};" }.joinToString(""),
                        secret.chunked(3).joinToString(" \u200b\n"),
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(url.toByteArray()),
                        "[Pay online]($url)",
                        "<a href=\"$url\">Pay online</a>",
                        "Ambiguous inline answer then $secret then more prose",
                    )
                fixtures.forEach { provider.inbound(id, it) }
                val response = app.adminGet(path(id) + "/timeline")
                val messages =
                    CommerceJson
                        .asA(response.bodyString(), StaffTimelineResponse.serializer())
                        .items
                        .filterIsInstance<TimelineItemResponse.Message>()
                messages.size shouldBe fixtures.size + 1
                val providerMessages =
                    provider.conversations
                        .getValue(id)
                        .messages
                        .associateBy { it.id.toString() }
                messages.forEach {
                    it.text shouldBe providerMessages.getValue(it.messageId).text
                    it.textOmittedForPaymentSecurity shouldBe false
                    it.textVisibility shouldBe MessageTextVisibility.READABLE
                }
                messages.single { it.direction == "outbound" }.financialEmail!!.kind shouldBe input.kind
            }
        }

        test("a financial intent committed during the provider read correlates without suppressing text even when UNKNOWN") {
            listOf(false, true).forEach { unknown ->
                val id = initial()
                val input = intent(id)
                val token = preview(id, input).reviewToken
                provider.onLookup = {
                    provider.onLookup = null
                    if (unknown) provider.script += StubConversationProvider.Behavior.INDETERMINATE
                    send(id, input, token).status shouldBe if (unknown) Status.ACCEPTED else Status.CREATED
                }
                val response = app.adminGet(path(id) + "/timeline")
                val secret = checkNotNull(access(id).url).substringAfter('#')
                response.bodyString() shouldContain secret
                val read = CommerceJson.asA(response.bodyString(), StaffTimelineResponse.serializer())
                val message = read.items.filterIsInstance<TimelineItemResponse.Message>().single()
                message.textVisibility shouldBe MessageTextVisibility.READABLE
                message.text shouldBe
                    provider.conversations
                        .getValue(id)
                        .messages
                        .single()
                        .text
                message.financialEmail!!.outcome shouldBe if (unknown) "UNKNOWN" else "ACCEPTED"
            }
        }

        test("definitively rejected first financial dispatch does not hide later ordinary correspondence") {
            val id = initial()
            val input = intent(id)
            provider.script += StubConversationProvider.Behavior.REJECT
            send(id, input, preview(id, input).reviewToken).status shouldBe Status.BAD_GATEWAY
            provider.inbound(id, "Could we replace the almonds with chopped peanuts?")
            val message = timeline(id).items.filterIsInstance<TimelineItemResponse.Message>().single()
            message.text shouldBe "Could we replace the almonds with chopped peanuts?"
            message.textVisibility shouldBe MessageTextVisibility.READABLE
            message.textOmittedForPaymentSecurity shouldBe false
        }

        test("staff-authored version references remain intact while generated headings omit versions and commands retain them") {
            listOf(false, true).forEach { invoice ->
                val id = initial(email = "custom-${UUID.randomUUID()}@example.com")
                if (invoice) served(id)
                val before = detail(id)
                val custom = "As discussed in Quote v99, compare Invoice v42.\n\nKeep this <exact> staff note."
                val input = intent(id, custom)
                val email = preview(id, input)
                email.text shouldContain custom
                email.html shouldContain "As discussed in Quote v99, compare Invoice v42."
                email.html shouldContain "Keep this &lt;exact&gt; staff note."
                email.financial.version shouldBe input.expectedDocumentVersion
                email.businessRevision shouldBe input.expectedBusinessRevision
                email.text.substringBefore('\n') shouldBe
                    if (invoice) "Thank you, Jane — here's your invoice" else "Hi Jane, here's your quote"
                detail(id) shouldBe before
            }
        }

        test("financial messages require conversation read permission while public payment responses remain token-protected and minimal") {
            val id = initial(name = "Private Customer")
            val input = intent(id)
            send(id, input, preview(id, input).reviewToken).status shouldBe Status.CREATED
            provider.inbound(id, "Private customer correspondence")
            val link = access(id)
            val url = checkNotNull(link.url)
            val secret = url.substringAfter('#')
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            try {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - FionaPermissions.CommunicationsRead)
                val restricted = app.adminGet(path(id) + "/timeline")
                restricted.status shouldBe Status.OK
                restricted.bodyString() shouldNotContain secret
                restricted.bodyString() shouldNotContain "Private customer correspondence"
                val read = CommerceJson.asA(restricted.bodyString(), StaffTimelineResponse.serializer())
                read.conversation.state.name shouldBe "FORBIDDEN"
                read.items.none { it is TimelineItemResponse.Message } shouldBe true
                read.financialEmails.single().messageId shouldNotBe null
                previewResponse(id, input).status shouldBe Status.FORBIDDEN
                send(id, input, "a".repeat(64)).status shouldBe Status.FORBIDDEN
                listOf(path(id), "/inquiries/$id", "/financial-documents/${input.documentId}").forEach { endpoint ->
                    val response = app.adminGet(endpoint)
                    response.status shouldBe Status.OK
                    response.bodyString() shouldNotContain secret
                    response.bodyString() shouldNotContain "Private customer correspondence"
                }
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            }
            val reader =
                app.serviceToken(
                    app.provisionService(
                        "no-message-reader-${UUID.randomUUID()}",
                        setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead),
                    ),
                )
            val restricted = app.http(Request(Method.GET, path(id) + "/timeline").withBearer(reader))
            restricted.bodyString() shouldNotContain secret
            CommerceJson
                .asA(restricted.bodyString(), StaffTimelineResponse.serializer())
                .conversation.state.name shouldBe "FORBIDDEN"
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
            logger.addAppender(logs)
            try {
                val authorized = app.adminGet(path(id) + "/timeline")
                authorized.bodyString() shouldContain secret
                val publicPath = "/public/payments/${link.publicId}"
                app.http(Request(Method.GET, path(id) + "/timeline")).status shouldBe Status.UNAUTHORIZED
                app.http(Request(Method.GET, publicPath)).status shouldBe Status.BAD_REQUEST
                val denied = app.http(Request(Method.GET, publicPath).header("X-Fionas-Payment-Token", "wrong-$secret"))
                denied.status shouldBe Status.NOT_FOUND
                denied.bodyString() shouldNotContain secret
                val summary = app.http(Request(Method.GET, publicPath).header("X-Fionas-Payment-Token", secret))
                summary.status shouldBe Status.OK
                summary.header("Cache-Control") shouldBe "no-store"
                summary.bodyString() shouldNotContain secret
                summary.bodyString() shouldNotContain "Private Customer"
                summary.bodyString() shouldNotContain "customer@example.com"
                summary.bodyString() shouldNotContain "Private customer correspondence"
                Json.parseToJsonElement(summary.bodyString()).jsonObject.keys shouldBe
                    setOf(
                        "inquiryId",
                        "eventDate",
                        "lifecycle",
                        "document",
                        "mode",
                        "requiredAmount",
                        "paidAmount",
                        "balance",
                        "canPay",
                        "paymentInProgress",
                        "reconciliationPending",
                        "history",
                        "checkoutRecoveryAvailable",
                    )
                app.adminGet(path(id) + "/payment-attempts").bodyString() shouldNotContain secret
            } finally {
                logger.detachAppender(logs)
            }
            logs.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") } shouldNotContain secret
        }

        test("UNKNOWN exact recovery uses the original request and expectation; later inbound stays unacknowledged") {
            val id = initial()
            val input = intent(id)
            val review = preview(id, input)
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.INDETERMINATE
            send(id, input, review.reviewToken, key).status shouldBe Status.ACCEPTED
            val first = timeline(id).financialEmails.single()
            first.outcome shouldBe "UNKNOWN"
            val attempts = provider.sendAttempts.get()
            provider.inbound(id)
            // Still the original EMPTY command: recovery does not manufacture a new reply under this key.
            send(id, input, review.reviewToken, key).status shouldBe Status.ACCEPTED
            provider.requests.count { it.toString().contains(id) } shouldBe 1
            provider.sendAttempts.get() shouldBe attempts + 1
            val blocked =
                send(
                    id,
                    input,
                    preview(id, input).reviewToken,
                    latest =
                        provider.conversations
                            .getValue(id)
                            .messages
                            .last()
                            .id
                            .toString(),
                )
            blocked.status shouldBe Status.CONFLICT
            blocked.code() shouldBe "financial_email_in_progress"
        }

        test("timeout after provider acceptance reconciles once under the exact same key") {
            val id = initial()
            val input = intent(id)
            provider.script += StubConversationProvider.Behavior.ERROR_AFTER_PERSIST
            send(id, input, preview(id, input).reviewToken).status shouldBe Status.CREATED
            provider.conversations
                .getValue(id)
                .messages
                .count { it.direction == "outbound" } shouldBe 1
        }

        test("definitive rejection is durable; original key cannot retry as a replacement") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.REJECT
            send(id, input, token, key).status shouldBe Status.BAD_GATEWAY
            val attempts = provider.sendAttempts.get()
            send(id, input, token, key).code() shouldBe "email_send_rejected"
            provider.sendAttempts.get() shouldBe attempts
            timeline(id).financialEmails.single().outcome shouldBe "REJECTED"
        }

        test("revocation requires deliberate reactivation and a new preview; rotation fails UNKNOWN reconstruction closed") {
            val id = initial()
            app.adminPost(path(id) + "/payment-access").status shouldBe Status.OK
            val original = access(id).url
            val input = intent(id)
            val token = preview(id, input).reviewToken
            app.adminPost(path(id) + "/payment-access/revoke").status shouldBe Status.OK
            send(id, input, token).code() shouldBe "payment_access_revoked"
            access(id).revoked shouldBe true
            app.adminPost(path(id) + "/payment-access").status shouldBe Status.OK
            access(id).url shouldNotBe original
            send(id, input, token).code() shouldBe "financial_email_review_stale"
            val reviewed = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.INDETERMINATE
            send(id, input, reviewed, key).status shouldBe Status.ACCEPTED
            app.adminPost(path(id) + "/payment-access/rotate").status shouldBe Status.OK
            val attempts = provider.sendAttempts.get()
            send(id, input, reviewed, key).code() shouldBe "financial_email_reconciliation_required"
            provider.sendAttempts.get() shouldBe attempts
        }

        test("Invoice email uses authoritative net payments and balance, only after served") {
            val id = initial()
            book(id)
            previewResponse(id, intent(id)).code() shouldBe "financial_email_ineligible"
            app.adminPost("/inquiries/$id/served").status shouldBe Status.OK
            invoicePayment(id, "45.00")
            val input = intent(id, "Thank you!\n\nPlease reach out with any questions.")
            val review = preview(id, input)
            review.text shouldContain "Your invoice\n"
            review.html shouldContain ">Your invoice</h2>"
            Regex("\\b(?:Quote|Invoice) v\\d+", RegexOption.IGNORE_CASE).find(review.text + review.html + review.subject) shouldBe null
            review.text shouldContain "Recorded payments (net applied): $181.25"
            review.text shouldContain "Remaining balance: $500.00"
            review.html shouldContain "Pay remaining balance"
            review.balanceAfterDeposit shouldBe null
            send(id, input, review.reviewToken).status shouldBe Status.CREATED
            val current = intent(id)
            val oldToken = preview(id, current).reviewToken
            invoicePayment(id, "10.00")
            send(
                id,
                current,
                oldToken,
                latest =
                    provider.conversations
                        .getValue(id)
                        .messages
                        .last()
                        .id
                        .toString(),
            ).code() shouldBe
                "financial_email_review_stale"
            invoicePayment(id, "490.00")
            previewResponse(id, intent(id)).code() shouldBe "financial_email_ineligible"
            app.adminPost("/inquiries/$id/close").status shouldBe Status.OK
            previewResponse(id, intent(id)).code() shouldBe "financial_email_ineligible"
        }

        test("booked Quote previews are stale and missing or unavailable conversations never open replacements") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val newer = provider.inbound(id)
            send(id, input, token).code() shouldBe "conversation_changed"
            send(id, input, token, latest = newer.toString()).status shouldBe Status.CREATED
            provider.conversations.remove(id)
            previewResponse(id, input).code() shouldBe "conversation_missing"
            provider.lookupStatus = 503
            previewResponse(id, input).code() shouldBe "email_provider_unavailable"
            provider.lookupStatus = null
            book(id)
            previewResponse(id, input).code() shouldBe "financial_email_review_stale"
        }

        test("crafted requests require every permission, a USER and a trusted Origin; invalid keys write nothing") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val body = CommerceJson.asFormatString(SendFinancialEmailRequest(input, token, expectEmptyConversation = true))
            val base =
                Request(
                    Method.POST,
                    path(id) + "/financial-emails",
                ).header("Content-Type", "application/json").header("Idempotency-Key", "test-key").body(body)
            app.http(base).status shouldBe Status.UNAUTHORIZED
            app.http(base.header("Cookie", app.adminCookie)).status shouldBe Status.FORBIDDEN
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            val required =
                listOf(
                    FionaPermissions.InquiriesRead,
                    CommercePermissions.FinancialDocumentRead,
                    FionaPermissions.CommunicationsRead,
                    FionaPermissions.CommunicationsSend,
                    FionaPermissions.PaymentAccessManage,
                )
            required.forEach { missing ->
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - missing)
                send(id, input, token).status shouldBe Status.FORBIDDEN
                previewResponse(id, input).status shouldBe Status.FORBIDDEN
            }
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            val service = app.serviceToken(app.provisionService("financial-sender", required.toSet()))
            app.http(base.withBearer(service)).status shouldBe Status.FORBIDDEN
            app
                .http(
                    base.header("Cookie", app.adminCookie).header("Origin", TEST_ORIGIN).replaceHeader("Idempotency-Key", "bad key"),
                ).status shouldBe
                Status.BAD_REQUEST
            app
                .http(
                    base.header("Cookie", app.adminCookie).header("Origin", TEST_ORIGIN).header("Idempotency-Key", "second"),
                ).status shouldBe
                Status.BAD_REQUEST
            provider.conversations.containsKey(id) shouldBe false
            previewResponse(id, input.copy(message = "x".repeat(2001))).status shouldBe Status.UNPROCESSABLE_ENTITY
        }

        test("concurrent duplicate commands share one provider identity and one communication fact") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            provider.onSend = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val first = CompletableFuture.supplyAsync { send(id, input, token, key) }
            check(entered.await(10, TimeUnit.SECONDS))
            val second = CompletableFuture.supplyAsync { send(id, input, token, key) }
            release.countDown()
            val results = listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
            results.all { it.status in setOf(Status.CREATED, Status.OK) } shouldBe true
            results.map { it.result().messageId }.distinct().size shouldBe 1
            provider.conversations
                .getValue(id)
                .messages.size shouldBe 1
            app.database.strings("SELECT count(*) FROM fionas.inquiry_financial_emails WHERE inquiry_id = '$id'").single() shouldBe "1"
            app.database
                .strings(
                    "SELECT count(*) FROM fionas.inquiry_communications WHERE inquiry_id = '$id' AND kind = 'STAFF_EMAIL_SENT'",
                ).single() shouldBe
                "1"
        }
        test("service-only Quote republication supersedes email review and renders the exact approved selections") {
            val id = initial()
            val old = intent(id)
            val oldReview = preview(id, old)
            send(id, old, oldReview.reviewToken).status shouldBe Status.CREATED
            val state = detail(id)
            val plan =
                ServicePlanRequest(
                    "Churros & ice cream <approved>",
                    75,
                    120,
                    listOf(ServicePlanItemDto("Vanilla & chocolate", "Ice cream"), ServicePlanItemDto("Cinnamon <sugar>", "Toppings")),
                )
            val revision =
                QuoteRevisionPreviewRequest(
                    state.financial.version,
                    state.proposal!!.depositRequirementRevision,
                    retainedLines(id),
                    DepositTermsRequest.Percentage("20"),
                    ServicePlanActionRequest.REPLACE,
                    plan,
                )
            val reviewed = app.adminPost(path(id) + "/quote-revision-preview", CommerceJson.asFormatString(revision))
            reviewed.status shouldBe Status.OK
            val token = CommerceJson.asA(reviewed.bodyString(), QuoteRevisionPreviewResponse.serializer()).quote.reviewToken
            app
                .adminPost(
                    path(id) + "/proposals/quote-revisions",
                    CommerceJson.asFormatString(
                        ReviseInquiryQuoteProposalRequest(
                            revision.expectedDocumentVersion,
                            revision.expectedDepositRequirementRevision,
                            revision.lines,
                            revision.terms,
                            plan,
                            token,
                            ServicePlanActionRequest.REPLACE,
                        ),
                    ),
                ).status shouldBe Status.OK
            detail(id).financial.version shouldBe old.expectedDocumentVersion
            send(
                id,
                old,
                oldReview.reviewToken,
                latest =
                    provider.conversations
                        .getValue(id)
                        .messages
                        .last()
                        .id
                        .toString(),
            ).code() shouldBe
                "financial_email_review_stale"
            val current = intent(id)
            val email = preview(id, current)
            email.text.substringBefore('\n') shouldBe "Hi Jane, here's your updated quote"
            email.html shouldContain "Hi Jane, here&#39;s your updated quote</h1>"
            Regex("\\b(?:Quote|Invoice) v\\d+", RegexOption.IGNORE_CASE).find(email.text + email.html + email.subject) shouldBe null
            email.financial.version shouldBe current.expectedDocumentVersion
            email.businessRevision shouldBe current.expectedBusinessRevision
            email.text shouldContain "Ice cream: Vanilla & chocolate"
            email.html shouldContain "Cinnamon &lt;sugar&gt;"
            val descriptions = detail(id).financial.lines.map { it.description }
            descriptions.zipWithNext().all { (a, b) -> email.text.indexOf(a) < email.text.indexOf(b) } shouldBe true
            timeline(id).financialEmails.single().current shouldBe false
            send(
                id,
                current,
                email.reviewToken,
                latest =
                    provider.conversations
                        .getValue(id)
                        .messages
                        .last()
                        .id
                        .toString(),
            ).status shouldBe
                Status.CREATED
            timeline(id).financialEmails.count { it.current } shouldBe 1
            java.io.File("build/reports/financial-email").mkdirs()
            java.io.File("build/reports/financial-email/quote.html").writeText(email.html)
        }

        test("Invoice service amendment invalidates a review even without a new ledger snapshot") {
            val id = initial()
            served(id)
            val old = intent(id)
            val token = preview(id, old).reviewToken
            val amendment =
                InvoiceRevisionPreviewRequest(
                    old.expectedDocumentVersion,
                    retainedLines(id),
                    ServicePlanActionRequest.REPLACE,
                    ServicePlanRequest("Final serving plan", 80, 90),
                )
            val reviewed = app.adminPost(path(id) + "/invoice-revision-preview", CommerceJson.asFormatString(amendment))
            reviewed.status shouldBe Status.OK
            val review = CommerceJson.asA(reviewed.bodyString(), InvoiceRevisionPreviewResponse.serializer())
            app
                .adminPost(
                    path(id) + "/invoice-revisions",
                    CommerceJson.asFormatString(
                        InvoiceRevisionRequest(
                            amendment.expectedDocumentVersion,
                            amendment.lines,
                            review.reviewToken,
                            amendment.servicePlanAction,
                            amendment.servicePlan,
                        ),
                    ),
                ).status shouldBe Status.OK
            send(id, old, token).code() shouldBe "financial_email_review_stale"
            val current = intent(id)
            current.expectedDocumentVersion shouldBe old.expectedDocumentVersion
            val email = preview(id, current)
            email.text shouldContain "Your invoice\n"
            Regex("\\b(?:Quote|Invoice) v\\d+", RegexOption.IGNORE_CASE).find(email.text + email.html + email.subject) shouldBe null
            email.text shouldContain "Remaining balance: $545.00"
            send(id, current, email.reviewToken).status shouldBe Status.CREATED
            java.io.File("build/reports/financial-email").mkdirs()
            java.io.File("build/reports/financial-email/invoice.html").writeText(email.html)
        }

        test("payment after an UNKNOWN Invoice attempt forbids reconstructing a replacement under its key") {
            val id = initial()
            served(id)
            val input = intent(id)
            val review = preview(id, input)
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.INDETERMINATE
            send(id, input, review.reviewToken, key).status shouldBe Status.ACCEPTED
            invoicePayment(id, "1.00")
            val attempts = provider.sendAttempts.get()
            send(id, input, review.reviewToken, key).code() shouldBe "financial_email_reconciliation_required"
            provider.sendAttempts.get() shouldBe attempts
        }

        test("a pending Checkout blocks an unreliable balance-due email") {
            val id = initial()
            served(id)
            app.adminPost(path(id) + "/payment-access").status shouldBe Status.OK
            val link = access(id)
            val state = detail(id)
            val checkout =
                app.http(
                    Request(Method.POST, "/public/payments/${link.publicId}/checkout-sessions")
                        .header("X-Fionas-Payment-Token", link.url!!.substringAfter('#'))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(
                            "Content-Type",
                            "application/json",
                        ).body(
                            """{"expectedDocumentVersion":${state.financial.version},"expectedBusinessRevision":${state.currentInvoiceRevision}}""",
                        ),
                )
            checkout.status shouldBe Status.OK
            val response = previewResponse(id, intent(id))
            response.code() shouldBe "financial_email_ineligible"
            response.bodyString() shouldContain "PAYMENT_IN_PROGRESS"
        }

        test("a payment racing the pre-send provider lookup is rechecked before durable intent") {
            val id = initial()
            served(id)
            val input = intent(id)
            val review = preview(id, input)
            provider.onLookup = {
                invoicePayment(id, "1.00")
                provider.onLookup = null
            }
            val attempts = provider.sendAttempts.get()
            send(id, input, review.reviewToken).code() shouldBe "financial_email_review_stale"
            provider.sendAttempts.get() shouldBe attempts
            app.database.strings("SELECT count(*) FROM fionas.inquiry_financial_emails WHERE inquiry_id = '$id'").single() shouldBe "0"
        }

        test("local acceptance failure leaves a durable command recoverable by the same exact provider request") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            app.transactor.inTransaction { transaction ->
                transaction.handle.execute(
                    """CREATE FUNCTION fionas.reject_email_acceptance() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'test acceptance failure'; END; ${'$'}${'$'}""",
                )
                transaction.handle.execute(
                    "CREATE TRIGGER test_email_acceptance BEFORE UPDATE OF outcome ON fionas.inquiry_financial_emails FOR EACH ROW EXECUTE FUNCTION fionas.reject_email_acceptance()",
                )
            }
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
            logger.addAppender(logs)
            try {
                send(id, input, token, key).status shouldBe Status.INTERNAL_SERVER_ERROR
            } finally {
                logger.detachAppender(logs)
                app.transactor.inTransaction {
                    it.handle.execute("DROP TRIGGER test_email_acceptance ON fionas.inquiry_financial_emails")
                    it.handle.execute("DROP FUNCTION fionas.reject_email_acceptance()")
                }
            }
            val secret = checkNotNull(access(id).url).substringAfter('#')
            logs.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") } shouldNotContain secret
            provider.conversations
                .getValue(id)
                .messages.size shouldBe 1
            send(id, input, token, key).status shouldBe Status.OK
            provider.conversations
                .getValue(id)
                .messages.size shouldBe 1
            timeline(id).financialEmails.single().outcome shouldBe "ACCEPTED"
        }

        test("provider participant mismatch refuses a crafted send to an established thread") {
            val id = initial("different-customer@example.com")
            val latest = provider.inbound(id)
            val input = intent(id)
            previewResponse(id, input).code() shouldBe "financial_email_recipient_mismatch"
            send(id, input, "a".repeat(64), latest = latest.toString()).status shouldBe Status.CONFLICT
            provider.conversations
                .getValue(id)
                .messages.size shouldBe 1
        }

        test("disabled sending, unverified tracking and recipient allow-list refuse preview without issuing access") {
            provider.onLookup = null
            listOf(
                null to "email_sending_disabled",
                provider.sendConfiguration(linksUntracked = false) to "financial_email_tracking_not_verified",
                provider.sendConfiguration(allowList = "allowed@example.com") to "email_recipient_not_allowed",
            ).forEach { (sends, code) ->
                TestApplication.create(conversationSends = sends, stripe = config, stripeGateway = FakeStripe(config)).use { other ->
                    val grants = checkNotNull(other.authorization.getRole(CommerceRoles.Administrator)).permissions
                    other.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants + FionaPermissions.PaymentAccessManage)
                    val id = other.createInquiry(email = "customer@example.com")
                    other
                        .adminPost(
                            path(id) + "/proposals",
                            """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                        ).status shouldBe
                        Status.OK
                    val state = CommerceJson.asA(other.adminGet(path(id)).bodyString(), StaffRequestResponse.serializer())
                    val input =
                        PreviewFinancialEmailRequest(
                            FinancialEmailKindResponse.QUOTE,
                            state.financial.id,
                            state.financial.version,
                            state.currentQuoteRevision!!,
                            state.proposal!!.id,
                            state.proposal.depositRequirementRevision,
                        )
                    val response = other.adminPost(path(id) + "/financial-emails/preview", CommerceJson.asFormatString(input))
                    response.code() shouldBe code
                    response.status shouldBe
                        if (code == "email_recipient_not_allowed") Status.UNPROCESSABLE_ENTITY else Status.SERVICE_UNAVAILABLE
                    other.database.count("fionas.inquiry_payment_access") shouldBe 0
                    other.database.count("fionas.inquiry_financial_emails") shouldBe 0
                }
            }
        }
        test("revocation racing the conversation lookup is observed before send intent and cannot reactivate access") {
            val id = initial()
            app.adminPost(path(id) + "/payment-access").status shouldBe Status.OK
            val input = intent(id)
            val token = preview(id, input).reviewToken
            provider.onLookup = {
                provider.onLookup = null
                app.adminPost(path(id) + "/payment-access/revoke").status shouldBe Status.OK
            }
            val attempts = provider.sendAttempts.get()
            send(id, input, token).code() shouldBe "payment_access_revoked"
            access(id).revoked shouldBe true
            provider.sendAttempts.get() shouldBe attempts
        }

        test("a changed exact provider digest fails UNKNOWN recovery closed without a replacement dispatch") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.INDETERMINATE
            send(id, input, token, key).status shouldBe Status.ACCEPTED
            app.transactor.inTransaction { transaction ->
                transaction.handle
                    .createUpdate(
                        "UPDATE fionas.inquiry_financial_emails SET provider_request = :digest WHERE inquiry_id = :id",
                    ).bind("digest", "0".repeat(64))
                    .bind("id", UUID.fromString(id))
                    .execute()
            }
            val attempts = provider.sendAttempts.get()
            send(id, input, token, key).code() shouldBe "financial_email_reconciliation_required"
            provider.sendAttempts.get() shouldBe attempts
            timeline(id).financialEmails.single().outcome shouldBe "UNKNOWN"
        }
        test("indeterminate provider identities remain visible and a later refused retry never erases uncertainty") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            provider.script += StubConversationProvider.Behavior.INDETERMINATE
            val response = send(id, input, token, key)
            response.status shouldBe Status.ACCEPTED
            val sent = response.result()
            sent.messageId shouldBe
                provider.conversations
                    .getValue(id)
                    .messages
                    .single()
                    .id
                    .toString()
            sent.sendState shouldBe "indeterminate"
            timeline(id).financialEmails.single().messageId shouldBe sent.messageId
            provider.replayStatus = 401
            val retry = send(id, input, token, key)
            retry.status shouldBe Status.ACCEPTED
            retry.result().outcome shouldBe StaffReplyOutcomeResponse.UNKNOWN
            retry.result().messageId shouldBe sent.messageId
            timeline(id).financialEmails.single().outcome shouldBe "UNKNOWN"
        }

        test("a duplicate accepted during the original lookup wins over apparent preview and conversation staleness") {
            val id = initial()
            val input = intent(id)
            val token = preview(id, input).reviewToken
            val key = UUID.randomUUID().toString()
            provider.onLookup = {
                provider.onLookup = null
                send(id, input, token, key).status shouldBe Status.CREATED
            }
            val result = send(id, input, token, key)
            result.status shouldBe Status.OK
            result.result().replayed shouldBe true
            provider.conversations
                .getValue(id)
                .messages.size shouldBe 1
        }
    })
