package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/** How Fiona turns revised, server-priced lines into a commerce-domain change order. */
class RepricingSpec :
    FunSpec({
        val usd = Currency.getInstance("USD")

        fun money(amount: String) = Money(BigDecimal(amount), usd)

        fun line(
            description: String,
            amount: String,
            quantity: String? = null,
            subDescription: String? = null,
        ) = LineItem(UUID.randomUUID(), description, subDescription, quantity?.let(::BigDecimal), money(amount), money("0.00"))

        val current = listOf(line("Base service", "250.00"), line("Ice cream service", "4.00", "75"))

        test("removes every current line, then adds every revised line in its evaluated order") {
            val revised =
                listOf(line("Base service", "250.00"), line("Ice cream service", "4.00", "100"), line("Waffle cones", "0.75", "100"))

            repricing(current, revised).changes shouldBe
                current.map { ChangeOrder.Change.RemoveLineItem(it.id) } + revised.map { ChangeOrder.Change.AddLineItem(it) }
        }

        test("applied by the domain, the successor holds exactly the revised lines and the source is untouched") {
            val revised = listOf(line("Ice cream service", "4.00", "100"), line("Base service", "300.00"))
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), current)

            val next = estimate.changeOrder(repricing(current, revised))

            next.lineItems shouldBe revised
            next.total shouldBe money("700.00")
            estimate.lineItems shouldBe current
        }

        test("the same charges with new line ids, or amounts at another scale, are no financial change") {
            val same =
                listOf(
                    LineItem(UUID.randomUUID(), "Base service", null, null, money("250.0"), money("0")),
                    LineItem(UUID.randomUUID(), "Ice cream service", null, BigDecimal("75.00"), money("4"), money("0.000")),
                )

            shouldThrow<CommerceFailure.ValidationFailed> { repricing(current, same) }.message shouldBe
                "The revised pricing produces no financial change"
        }

        test("any change to what a line charges, or to the order of the lines, is a financial change") {
            listOf(
                listOf(line("Base service", "250.00", subDescription = "2 hours"), line("Ice cream service", "4.00", "75")),
                listOf(line("Base service", "250.00"), line("Ice cream service", "4.00", "76")),
                listOf(line("Base service", "250.01"), line("Ice cream service", "4.00", "75")),
                listOf(line("Base", "250.00"), line("Ice cream service", "4.00", "75")),
                listOf(line("Ice cream service", "4.00", "75"), line("Base service", "250.00")),
                listOf(line("Base service", "250.00")),
            ).forEach { revised -> repricing(current, revised).changes.size shouldBe current.size + revised.size }
        }
    })
