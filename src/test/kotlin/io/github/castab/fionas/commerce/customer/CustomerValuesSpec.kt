package io.github.castab.fionas.commerce.customer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CustomerValuesSpec :
    FunSpec({
        test("a customer name is trimmed from what was submitted") {
            CustomerName.of("  Jane Doe ").value shouldBe "Jane Doe"
        }

        test("a customer name must not be blank or too long") {
            shouldThrow<IllegalArgumentException> { CustomerName.of("   ") }.message shouldBe "Name must not be blank"
            CustomerName.of("x".repeat(CustomerName.MAX_LENGTH)).value.length shouldBe CustomerName.MAX_LENGTH
            shouldThrow<IllegalArgumentException> { CustomerName.of("x".repeat(CustomerName.MAX_LENGTH + 1)) }
        }

        test("a customer name is constructed only in canonical form") {
            shouldThrow<IllegalArgumentException> { CustomerName(" Jane") }
        }

        test("an email is normalized for matching: trimmed and lowercased") {
            Email.of("  Jane.Doe@Example.COM ").value shouldBe "jane.doe@example.com"
            Email.of("Jane@example.com") shouldBe Email.of("jane@EXAMPLE.com")
        }

        test("an email is constructed only in normalized form") {
            shouldThrow<IllegalArgumentException> { Email("Jane@example.com") }
            shouldThrow<IllegalArgumentException> { Email(" jane@example.com") }
        }

        test("text that is plainly not an email address is rejected") {
            listOf(
                "",
                "jane",
                "@example.com",
                "jane@",
                "jane@example",
                "jane@.com",
                "jane@example.",
                "jane@@example.com",
                "jane@exa@mple.com",
                "ja ne@example.com",
            ).forEach { submitted ->
                shouldThrow<IllegalArgumentException> { Email.of(submitted) }
            }
        }

        test("an email may be at most 254 characters") {
            val domain = "@example.com"
            Email.of("a".repeat(Email.MAX_LENGTH - domain.length) + domain).value.length shouldBe Email.MAX_LENGTH
            shouldThrow<IllegalArgumentException> { Email.of("a".repeat(Email.MAX_LENGTH - domain.length + 1) + domain) }
        }
    })
