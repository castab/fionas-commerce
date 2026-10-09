package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Fiona's precision policy for authored lines: a per-quantity unit rate may be more precise than
 * the currency's minor units, but every settlement amount (a flat price, the extended subtotal,
 * the tax) must be exact in them. Nothing is ever rounded.
 */
class PricedLineSpec :
    FunSpec({
        fun money(
            amount: String,
            currency: String = "USD",
        ) = Money(BigDecimal(amount), Currency.getInstance(currency))

        fun line(
            unitPrice: String,
            quantity: String?,
            tax: String = "0.00",
            currency: String = "USD",
        ) = PricedLine("Service", null, quantity?.let(::BigDecimal), money(unitPrice, currency), money(tax, currency))

        test("a precise unit rate is kept exactly when its extended subtotal settles in minor units") {
            val rated = line("0.125", "8")
            rated.unitPrice.amount.toPlainString() shouldBe "0.125"
            rated.total.amount.compareTo(BigDecimal("1.00")) shouldBe 0
            val item = rated.withId(UUID(0, 1))
            item.price.amount.toPlainString() shouldBe "0.125"
            item.subtotal.amount.compareTo(BigDecimal("1")) shouldBe 0
        }

        test("an extended subtotal that does not settle exactly is rejected, never rounded") {
            shouldThrow<IllegalArgumentException> { line("0.125", "3") }.message shouldContain "minor units"
        }

        test("a flat price is its own subtotal, so it must already be in minor units") {
            shouldThrow<IllegalArgumentException> { line("0.125", null) }.message shouldContain "flat line"
            line("0.13", null).total.amount.compareTo(BigDecimal("0.13")) shouldBe 0
        }

        test("signed precise rates are valid when the extended credit settles exactly") {
            val credit = line("-0.125", "16")
            credit.total.amount.compareTo(BigDecimal("-2.00")) shouldBe 0
            shouldThrow<IllegalArgumentException> { line("-0.125", "5") }
        }

        test("settlement follows each currency's own minor units") {
            // JPY has no minor units: a fractional yen rate is fine only if the subtotal is whole yen.
            line("12.5", "2", tax = "0", currency = "JPY").total.amount.compareTo(BigDecimal("25")) shouldBe 0
            shouldThrow<IllegalArgumentException> { line("12.5", "3", tax = "0", currency = "JPY") }
            shouldThrow<IllegalArgumentException> { line("25.5", null, tax = "0", currency = "JPY") }
            // BHD has three decimal places.
            line("0.0125", "8", tax = "0.000", currency = "BHD").total.amount.compareTo(BigDecimal("0.100")) shouldBe 0
            line("1.125", null, tax = "0.000", currency = "BHD").total.amount.compareTo(BigDecimal("1.125")) shouldBe 0
            shouldThrow<IllegalArgumentException> { line("0.0125", "1", tax = "0.000", currency = "BHD") }
        }

        test("tax is a whole-line settlement amount in minor units and in the line's currency") {
            line("0.125", "8", tax = "0.08").total.amount.compareTo(BigDecimal("1.08")) shouldBe 0
            shouldThrow<IllegalArgumentException> { line("0.125", "8", tax = "0.005") }
            shouldThrow<IllegalArgumentException> {
                PricedLine("Service", null, BigDecimal("8"), money("0.125"), money("0.00", "EUR"))
            }
        }

        test("unit rate precision and magnitude stay bounded") {
            // Twelve decimal places is the limit, when the extended subtotal still settles: 1.25e-10 × 8e7 = 0.01.
            line("0.000000000125", "80000000").total.amount.compareTo(BigDecimal("0.01")) shouldBe 0
            shouldThrow<IllegalArgumentException> { line("0.0000000000125", "800000000") }.message shouldContain "12 decimal places"
            shouldThrow<IllegalArgumentException> { line("1000000000000.00", "1") }.message shouldContain "integer digits"
            shouldThrow<IllegalArgumentException> { line("0.125", "0") }
        }
    })
