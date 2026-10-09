package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Pure resolution of staff line proposals. The final lines are identity-bearing: an existing id
 * keeps that exact line, a key is a genuinely new line, omission removes, and order is the order.
 * Financially identical lines must therefore still change the snapshot when their identity or
 * order does.
 */
class LineProposalSpec :
    FunSpec({
        val usd = Currency.getInstance("USD")

        fun money(amount: String) = Money(BigDecimal(amount), usd)

        fun stored(
            id: Long,
            price: String = "100.00",
            quantity: String? = null,
        ) = LineItem(UUID(0, id), "Catering", null, quantity?.let(::BigDecimal), money(price), money("0.00"))

        val a = stored(1)
        val b = stored(2)
        val reviewed = FinancialDocument.Estimate.create(UUID.fromString("00000000-0000-0000-0000-0000000000e1"), listOf(a, b))

        fun keep(
            line: LineItem,
            price: String? = null,
            quantity: String? = null,
            tax: String = "0.00",
        ) = ProposedLine(
            ProposedLineIdentity.Existing(line.id),
            PricedLine(
                line.description,
                line.subDescription,
                quantity?.let(::BigDecimal) ?: line.quantity,
                price?.let(::money) ?: line.price,
                money(tax),
            ),
        )

        fun add(
            key: String,
            price: String = "100.00",
        ) = ProposedLine(ProposedLineIdentity.New(LineKey(key)), PricedLine("Catering", null, null, money(price), money("0.00")))

        /** The proposal's change order applied through commerce-domain, as the ledger will. */
        fun applied(resolved: ResolvedLineProposal) = reviewed.changeOrder(resolved.changes.shouldNotBeNull())

        test("reordering two financially identical lines is a change that persists the requested order") {
            val resolved = LineProposal(listOf(keep(b), keep(a))).resolveAgainst(reviewed)

            resolved.lines.map { it.line.id } shouldBe listOf(b.id, a.id)
            resolved.lines.map { it.origin } shouldBe listOf(ResolvedLineOrigin.CARRIED, ResolvedLineOrigin.CARRIED)
            val successor = applied(resolved)
            successor.lineItems.map { it.id } shouldBe listOf(b.id, a.id)
            successor.lineItems shouldBe listOf(b, a)
            successor.total shouldBe reviewed.total
            reviewed.lineItems shouldBe listOf(a, b)
        }

        test("omitting a line and adding an identical new one removes it and adds a distinct line, never a silent carry") {
            val resolved = LineProposal(listOf(keep(a), add("replacement"))).resolveAgainst(reviewed)

            val replacement = resolved.lines[1]
            replacement.origin shouldBe ResolvedLineOrigin.NEW
            replacement.key shouldBe LineKey("replacement")
            replacement.line.id shouldNotBe b.id
            replacement.line.id shouldBe proposedLineId(reviewed.id, reviewed.version, LineKey("replacement"))
            resolved.idOf(ProposedLineIdentity.New(LineKey("replacement"))) shouldBe replacement.line.id
            val successor = applied(resolved)
            successor.lineItems.map { it.id } shouldBe listOf(a.id, replacement.line.id)
            successor.total shouldBe reviewed.total
        }

        test("replacing the only line with an identical new line is still a remove-and-add") {
            val single = FinancialDocument.Estimate.create(UUID.fromString("00000000-0000-0000-0000-0000000000e2"), listOf(a))
            val resolved = LineProposal(listOf(add("replacement"))).resolveAgainst(single)

            val successor = single.changeOrder(resolved.changes.shouldNotBeNull())
            successor.lineItems.single().id shouldNotBe a.id
            successor.total shouldBe single.total
        }

        test("reordering while editing one line keeps the edited line's id, its new values and the requested order") {
            val resolved = LineProposal(listOf(keep(b, price = "120.00"), keep(a))).resolveAgainst(reviewed)

            resolved.lines.map { it.line.id } shouldBe listOf(b.id, a.id)
            resolved.lines.map { it.origin } shouldBe listOf(ResolvedLineOrigin.REPLACED, ResolvedLineOrigin.CARRIED)
            val successor = applied(resolved)
            successor.lineItems.map { it.id } shouldBe listOf(b.id, a.id)
            successor.lineItems[0].price shouldBe money("120.00")
            successor.lineItems[1] shouldBe a
            successor.total shouldBe money("220.00")
        }

        test("moving a reused id among several lines yields exactly the requested ordered ids") {
            val c = stored(3)
            val three = FinancialDocument.Estimate.create(UUID.fromString("00000000-0000-0000-0000-0000000000e3"), listOf(a, b, c))
            val resolved = LineProposal(listOf(keep(c), keep(a), add("extra"), keep(b))).resolveAgainst(three)

            val successor = three.changeOrder(resolved.changes.shouldNotBeNull())
            successor.lineItems.map { it.id } shouldBe resolved.lines.map { it.line.id }
            successor.lineItems.map { it.id }.take(2) shouldBe listOf(c.id, a.id)
            successor.lineItems.last().id shouldBe b.id
        }

        test("the same ordered ids with numerically equal values at other scales are no change and keep the stored lines") {
            val scaled = stored(4, price = "4.50", quantity = "10")
            val document = FinancialDocument.Estimate.create(UUID.fromString("00000000-0000-0000-0000-0000000000e4"), listOf(a, scaled))
            val resolved =
                LineProposal(listOf(keep(a, price = "100", tax = "0"), keep(scaled, price = "4.5", quantity = "10.000", tax = "0.0")))
                    .resolveAgainst(document)

            resolved.changes.shouldBeNull()
            resolved.lines.map { it.line } shouldBe document.lineItems
            resolved.lines.map { it.origin }.toSet() shouldBe setOf(ResolvedLineOrigin.CARRIED)
        }

        test("the review token binds line identity and order, not only the charges") {
            val inquiry = InquiryId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
            val percent = DepositTerms.Percentage(BigDecimal("20"))

            fun compose(vararg lines: ProposedLine) = QuoteComposer.compose(inquiry, reviewed, LineProposal(lines.toList()), null, percent)

            val kept = compose(keep(a), keep(b))
            val reordered = compose(keep(b), keep(a))
            val replaced = compose(keep(a), add("replacement"))

            kept.financialChange shouldBe false
            reordered.financialChange shouldBe true
            replaced.financialChange shouldBe true
            reordered.quote.lineItems.map { it.id } shouldBe listOf(b.id, a.id)
            setOf(kept.reviewToken, reordered.reviewToken, replaced.reviewToken).size shouldBe 3
            // Equal charges throughout: only identity distinguishes these reviews.
            listOf(kept, reordered, replaced).map { it.quote.total }.toSet() shouldBe setOf(money("200.00"))
        }

        test("a service plan note follows the actual final line, not a financially identical removed one") {
            val inquiry = InquiryId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
            val note = ProposedLineNote(ProposedLineIdentity.New(LineKey("replacement")), "Rebooked package")
            val composed =
                QuoteComposer.compose(
                    inquiry,
                    reviewed,
                    LineProposal(listOf(keep(a), add("replacement"))),
                    ProposedServicePlan(ServiceCommitment("Catering", null, null, emptyList()), listOf(note)),
                    DepositTerms.Percentage(BigDecimal("20")),
                )

            val target =
                composed.servicePlan
                    .shouldNotBeNull()
                    .lineNotes
                    .single()
                    .lineItemId
            target shouldBe composed.quote.lineItems[1].id
            target shouldNotBe b.id
        }
    })
