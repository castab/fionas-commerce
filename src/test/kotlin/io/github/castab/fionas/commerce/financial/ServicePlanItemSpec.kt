package io.github.castab.fionas.commerce.financial

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServicePlanItemSpec :
    FunSpec({
        test("submitted selections normalize text without interpreting categories or opaque keys") {
            ServicePlanItem.of(" Chocolate Chip ", " Hand-scooped ", " menu:key/1 ") shouldBe
                ServicePlanItem("Chocolate Chip", "Hand-scooped", "menu:key/1")
            ServicePlanItem.of(" Custom flavor ", " ", "\t") shouldBe ServicePlanItem("Custom flavor", null, null)
            ServicePlanItem.of("Custom flavor", "Equipment") shouldBe ServicePlanItem("Custom flavor", "Equipment", null)
            ServicePlanItem.of("Custom flavor", key = "opaque") shouldBe ServicePlanItem("Custom flavor", null, "opaque")
        }

        test("canonical constructors and copies reject blank noncanonical and oversized text") {
            listOf("", " ", "a".repeat(201)).forEach { label ->
                shouldThrow<IllegalArgumentException> { ServicePlanItem.of(label) }
            }
            listOf("", " ", " padded ", "a".repeat(121)).forEach { text ->
                shouldThrow<IllegalArgumentException> { ServicePlanItem("Flavor", text, null) }
                shouldThrow<IllegalArgumentException> { ServicePlanItem("Flavor", null, text) }
            }
            shouldThrow<IllegalArgumentException> { ServicePlanItem(" padded ", null, null) }
            shouldThrow<IllegalArgumentException> { ServicePlanItem.of("Flavor", "a".repeat(121)) }
            shouldThrow<IllegalArgumentException> { ServicePlanItem.of("Flavor", key = "a".repeat(121)) }
            shouldThrow<IllegalArgumentException> { ServicePlanItem.of("Flavor").copy(label = "") }
            ServicePlanItem.of("a".repeat(200), "b".repeat(120), "c".repeat(120)).label.length shouldBe 200
        }

        test("commitments preserve order and duplicates with at most fifty selections") {
            val a = ServicePlanItem.of("Flavor", "Desserts", "same")
            val b = ServicePlanItem.of("Topping", "Desserts", "same")
            val items = listOf(b, a, b)
            ServiceCommitment("Service", null, null, items).items shouldBe items
            ServiceCommitment("Service", null, null, List(50) { a }).items.size shouldBe 50
            shouldThrow<IllegalArgumentException> { ServiceCommitment("Service", null, null, List(51) { a }) }
        }

        test("existing service description guest and duration constraints remain enforced") {
            val base = ServiceCommitment("Service", null, null, emptyList())
            listOf("", " ", " padded ", "a".repeat(2001)).forEach { description ->
                shouldThrow<IllegalArgumentException> { base.copy(description = description) }
            }
            listOf(0, -1, 100001).forEach { guests -> shouldThrow<IllegalArgumentException> { base.copy(guestCount = guests) } }
            listOf(0, -1, 1441).forEach { minutes -> shouldThrow<IllegalArgumentException> { base.copy(durationMinutes = minutes) } }
            base.copy(description = "a".repeat(2000), guestCount = 100000, durationMinutes = 1440).guestCount shouldBe 100000
            base.copy(guestCount = 1, durationMinutes = 1).durationMinutes shouldBe 1
        }
    })
