package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financialemail.FinancialEmailId
import io.github.castab.fionas.commerce.financialemail.FinancialEmailKind
import io.github.castab.fionas.commerce.financialemail.FinancialEmailOutcome
import io.github.castab.fionas.commerce.financialemail.FinancialEmailRecord
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class MessageTextVisibilitySpec :
    FunSpec({
        val message =
            ConversationMessage(
                UUID.randomUUID(),
                "inbound",
                "received",
                null,
                "customer@example.com",
                null,
                "staff@example.com",
                "Could we replace the almonds with chopped peanuts?",
                Instant.EPOCH,
            )

        fun intent(
            outcome: FinancialEmailOutcome = FinancialEmailOutcome.UNKNOWN,
            messageId: UUID? = null,
            dispatches: Int = 1,
        ) = FinancialEmailRecord(
            FinancialEmailId(UUID.randomUUID()),
            InquiryId(UUID.randomUUID()),
            StaffReplyKey("command"),
            "digest",
            UserId(UUID.randomUUID()),
            FinancialEmailKind.QUOTE,
            UUID.randomUUID(),
            2,
            2,
            null,
            "digest",
            "digest",
            "Staff",
            true,
            Instant.EPOCH,
            0,
            null,
            outcome,
            messageId,
            dispatchCount = dispatches,
        )

        test("ordinary correspondence without financial sends retains exact plain text and an explicit readable state") {
            val result = message.withTextVisibility(emptyList())
            result.text shouldBe message.text
            result.textOmittedForPaymentSecurity shouldBe false
            result.textVisibility shouldBe MessageTextVisibility.READABLE
        }
        test("provider missing text is distinct from an empty or security-withheld customer message") {
            val absent = message.copy(text = null).withTextVisibility(listOf(intent()))
            absent.textVisibility shouldBe MessageTextVisibility.PROVIDER_CONTENT_UNAVAILABLE
            absent.textOmittedForPaymentSecurity shouldBe false
            message.copy(text = "").withTextVisibility(emptyList()).textVisibility shouldBe MessageTextVisibility.READABLE
        }
        test("matched financial identities remain protected regardless of direction, acceptance or missing provider body") {
            FinancialEmailOutcome.entries.forEach { outcome ->
                val result = message.copy(text = null).withTextVisibility(listOf(intent(outcome, message.id)))
                result.textVisibility shouldBe MessageTextVisibility.FINANCIAL_EMAIL_PROTECTED
                result.textOmittedForPaymentSecurity shouldBe true
            }
        }
        test("an unresolved command without provider message identity still protects raw inbound and ordinary outbound content") {
            listOf("inbound", "outbound").forEach { direction ->
                val result = message.copy(direction = direction).withTextVisibility(listOf(intent()))
                result.text shouldBe null
                result.textVisibility shouldBe MessageTextVisibility.PAYMENT_CONTENT_UNVERIFIED
                result.id shouldBe message.id
                result.createdAt shouldBe message.createdAt
            }
        }
        test("only a definitive first-dispatch rejection permits ordinary provider text after an intent") {
            message.withTextVisibility(listOf(intent(FinancialEmailOutcome.REJECTED))).text shouldBe message.text
            listOf(0, 2).forEach { dispatches ->
                message
                    .withTextVisibility(listOf(intent(FinancialEmailOutcome.REJECTED, dispatches = dispatches)))
                    .textVisibility shouldBe MessageTextVisibility.PAYMENT_CONTENT_UNVERIFIED
            }
            message
                .withTextVisibility(listOf(intent(FinancialEmailOutcome.REJECTED), intent(FinancialEmailOutcome.ACCEPTED)))
                .textVisibility shouldBe MessageTextVisibility.PAYMENT_CONTENT_UNVERIFIED
        }
    })
