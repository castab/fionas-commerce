package io.github.castab.fionas.commerce.staff

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StaffRequestSearchSpec :
    FunSpec({
        test("search normalization handles blank whitespace Unicode and literal punctuation") {
            listOf(null, "", "  ", "\t\r\n ").forEach { StaffRequestSearch.of(it) shouldBe null }
            StaffRequestSearch.of("  MaRiA  ")!!.value shouldBe "maria"
            StaffRequestSearch.of("🍦 %_.*")!!.value shouldBe "🍦 %_.*"
        }
        test("search and page size are bounded and malformed Unicode or embedded controls fail") {
            listOf("a".repeat(201), "a\u0000b", "a\nb", "\uD800", "\uDC00").forEach {
                shouldThrow<IllegalArgumentException> { StaffRequestSearch.of(it) }
            }
            shouldThrow<IllegalArgumentException> { StaffRequestSearch("Unnormalized") }
            listOf(0, -1, 101).forEach { shouldThrow<IllegalArgumentException> { ListStaffRequests.Command(limit = it) } }
        }
    })
