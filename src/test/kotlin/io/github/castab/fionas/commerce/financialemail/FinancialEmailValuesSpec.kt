package io.github.castab.fionas.commerce.financialemail

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class FinancialEmailValuesSpec :
    FunSpec({
        test("staff message normalization preserves paragraphs and non-BMP Unicode while rejecting invalid Unicode and controls") {
            FinancialEmailMessage.ofOptional(" \nHello 🍦\n\nAnother paragraph\t \n")!!.value shouldBe "Hello 🍦\n\nAnother paragraph"
            FinancialEmailMessage.ofOptional(" \r\n\t") shouldBe null
            shouldThrow<IllegalArgumentException> { FinancialEmailMessage.ofOptional("bad\u0000text") }
            shouldThrow<IllegalArgumentException> { FinancialEmailMessage.ofOptional("bad\uD800text") }
            shouldThrow<IllegalArgumentException> { FinancialEmailMessage.ofOptional("x".repeat(2001)) }
        }
        test("length-prefixed review digest distinguishes absence, empty content and ambiguous field concatenations") {
            financialEmailDigest(listOf("ab", "c")) shouldNotBe financialEmailDigest(listOf("a", "bc"))
            financialEmailDigest(listOf(null)) shouldNotBe financialEmailDigest(listOf(""))
            financialEmailDigest(listOf("a", "b")) shouldNotBe financialEmailDigest(listOf("b", "a"))
        }
        test("HTML escaping preserves the complete fragment while escaping every attribute and markup delimiter") {
            escapeEmailHtml("<a href=\"https://pay.test/pay/id#bearer_A-b\">&'</a>") shouldBe
                "&lt;a href=&quot;https://pay.test/pay/id#bearer_A-b&quot;&gt;&amp;&#39;&lt;/a&gt;"
            RenderedFinancialEmail("subject", "secret html", "secret text").toString() shouldBe "RenderedFinancialEmail([redacted])"
        }
    })
