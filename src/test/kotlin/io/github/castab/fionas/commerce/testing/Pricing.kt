package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.LineKey
import io.github.castab.fionas.commerce.financial.LineProposal
import io.github.castab.fionas.commerce.financial.PricedLine
import io.github.castab.fionas.commerce.financial.ProposedLine
import io.github.castab.fionas.commerce.financial.ProposedLineIdentity
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.RequestedServiceItem
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/*
 * Test stand-in for the public pricing authority (the web server's SvelteKit backend): it decides
 * prices on its side and submits exact lines. Its default lines reproduce Fiona's former
 * acceptance estimate exactly ($681.25 for 75 guests over two hours with horchata, six toppings
 * and waffle cones), so amounts in specs stay meaningful. Fiona itself never computes any of it.
 */

/** One already-priced test line; amounts are exact decimal strings, as on the wire. */
data class TestLine(
    val description: String,
    val subDescription: String? = null,
    val quantity: String? = null,
    val unitPrice: String,
    val taxAmount: String = "0.00",
    val currency: String = "USD",
) {
    /** This line as a `PricedLineRequest` JSON object. */
    fun json(): String =
        "{" +
            listOfNotNull(
                "\"description\":${quote(description)}",
                subDescription?.let { "\"subDescription\":${quote(it)}" },
                quantity?.let { "\"quantity\":\"$it\"" },
                "\"unitPrice\":\"$unitPrice\"",
                "\"taxAmount\":\"$taxAmount\"",
                "\"currency\":\"$currency\"",
            ).joinToString(",") + "}"

    /** This line as a staff `ProposedLineRequest` JSON object keeping existing line [lineItemId]. */
    fun existing(lineItemId: String): String = "{\"lineItemId\":\"$lineItemId\"," + json().drop(1)

    /** This line as a staff `ProposedLineRequest` JSON object for a new line [key]. */
    fun new(key: String): String = "{\"key\":\"$key\"," + json().drop(1)

    /** This line as a domain [PricedLine]. */
    fun priced(): PricedLine {
        val code = Currency.getInstance(currency)
        return PricedLine.of(
            description,
            subDescription,
            quantity?.let(::BigDecimal),
            Money(BigDecimal(unitPrice), code),
            Money(BigDecimal(taxAmount), code),
        )
    }
}

private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The six toppings of the acceptance request; four are included in the ice cream service. */
val TOPPINGS = listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")

/**
 * The lines the test pricing authority prices for an ice cream event: base service ($150 plus
 * $50 an hour), ice cream service ($4 a guest), horchata ($0.50 a guest) when chosen, waffle cones
 * ($0.75 a guest) when chosen, and $0.25 a guest for each topping beyond four.
 */
fun acceptanceLines(
    guests: Int = 75,
    minutes: Int = 120,
    horchata: Boolean = true,
    toppings: Int = 6,
    waffleCones: Boolean = true,
    guestCountIsMinimum: Boolean = false,
): List<TestLine> {
    val hours = BigDecimal(minutes).divide(BigDecimal(60))
    val hoursText = hours.stripTrailingZeros().toPlainString() + if (hours.compareTo(BigDecimal.ONE) == 0) " hour" else " hours"
    val base = BigDecimal("150.00") + BigDecimal("50.00") * hours
    val extra = toppings - 4
    return listOfNotNull(
        TestLine("Base service", "$hoursText · setup, staff & local travel", null, base.setScale(2).toPlainString()),
        TestLine("Ice cream service", "$guests${if (guestCountIsMinimum) "+" else ""} guests", "$guests", "4.00"),
        TestLine("Horchata", "Premium soft serve", "$guests", "0.50").takeIf { horchata },
        TestLine("Waffle cones", null, "$guests", "0.75").takeIf { waffleCones },
        TestLine(
            "Extra toppings ($extra)",
            "4 toppings included; each extra is charged per guest",
            "${extra * guests}",
            "0.25",
        ).takeIf { extra > 0 },
    )
}

/** The bespoke service no catalog offers: churro catering for one flat negotiated price. */
val CHURROS = TestLine("Churro catering service", "Prepared on site", "1", "450.00")

/** A separate courtesy credit, a signed negative flat line. */
val COURTESY_DISCOUNT = TestLine("Courtesy discount", null, null, "-50.00")

/** A JSON array of priced lines. */
fun linesJson(lines: List<TestLine>): String = lines.joinToString(",", "[", "]") { it.json() }

/** A `RequestedServiceRequest` JSON object. */
fun requestedServiceJson(
    guests: Int = 75,
    guestCountIsMinimum: Boolean = false,
    minutes: Int? = 120,
    items: List<String> = listOf("Vanilla soft serve", "Horchata soft serve", "Waffle cones"),
    pricingReference: String? = "test-pricing@1",
): String =
    "{" +
        listOfNotNull(
            "\"guestCount\":$guests",
            "\"guestCountIsMinimum\":$guestCountIsMinimum",
            minutes?.let { "\"durationMinutes\":$it" },
            "\"items\":" + items.joinToString(",", "[", "]") { "{\"label\":${quote(it)}}" },
            pricingReference?.let { "\"pricingReference\":${quote(it)}" },
        ).joinToString(",") + "}"

/** The domain form of [requestedServiceJson]'s defaults. */
fun requestedService(
    guests: Int = 75,
    guestCountIsMinimum: Boolean = false,
    minutes: Int? = 120,
) = RequestedService(
    guests,
    guestCountIsMinimum,
    minutes,
    listOf("Vanilla soft serve", "Horchata soft serve", "Waffle cones").map { RequestedServiceItem(it, null, null) },
    "test-pricing@1",
)

/** A complete `POST /inquiries` body. */
fun inquiryBody(
    email: String = "jane-${UUID.randomUUID()}@example.com",
    lines: List<TestLine> = acceptanceLines(),
    requested: String = requestedServiceJson(),
    name: String = "Jane Doe",
    extra: String = "",
): String =
    """{"name":${quote(name)},"email":${quote(email)},"zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY",""" +
        """"message":"Ice cream for a birthday.","requestedService":$requested,"lines":${linesJson(lines)}$extra}"""

/**
 * Records an inquiry through the public API as the web server's SERVICE principal, with the
 * exact [lines] its pricing authority committed, and returns the inquiry id. Like every accepted
 * inquiry, it has its canonical initial Estimate.
 */
fun TestApplication.createInquiry(
    email: String = "jane-${UUID.randomUUID()}@example.com",
    lines: List<TestLine> = acceptanceLines(),
    guestCountIsMinimum: Boolean = false,
): String {
    val response =
        http(
            Request(Method.POST, "/inquiries")
                .withSubmissionKey()
                .asFionasWeb(this)
                .header("Content-Type", "application/json")
                .body(inquiryBody(email, lines, requestedServiceJson(guestCountIsMinimum = guestCountIsMinimum))),
        )
    check(response.status == Status.CREATED) { "Recording an inquiry failed: ${response.status} ${response.bodyString()}" }
    return checkNotNull(response.header("Location")).substringAfterLast('/')
}

/** The id of [inquiryId]'s canonical initial Estimate, which every accepted inquiry has exactly one of. */
fun TestApplication.initialEstimateOf(inquiryId: String): String =
    database
        .strings(
            "SELECT document_id FROM fionas.inquiry_financial_documents " +
                "WHERE inquiry_id = '$inquiryId' AND purpose = 'INITIAL_ESTIMATE'",
        ).single()

/** The ids of [documentId]'s lines at [version], in order. */
fun TestApplication.lineIds(
    documentId: String,
    version: Int,
): List<String> =
    database.strings(
        "SELECT line ->> 'id' FROM commerce.financial_document_snapshots, jsonb_array_elements(lines) WITH ORDINALITY AS l(line, n) " +
            "WHERE document_id = '$documentId' AND version = $version ORDER BY n",
    )

/** A staff line-proposal JSON array, from already-encoded proposed lines. */
fun proposalJson(vararg lines: String): String = lines.joinToString(",", "[", "]")

/** A domain proposal keeping no existing line: every line is new, keyed `line-0`, `line-1`, … */
fun newLinesProposal(lines: List<TestLine>) =
    LineProposal(lines.mapIndexed { index, line -> ProposedLine(ProposedLineIdentity.New(LineKey("line-$index")), line.priced()) })

/** The bootstrap administrator's user id: a real staff USER that may author lines and approve proposals. */
val TestApplication.adminId: UserId
    get() = checkNotNull(authorization.findUserByUsername("admin")).id

/** A staff proposal replacing every reviewed line with [lines], each new (`line-0`, `line-1`, …). */
fun replacementLines(lines: List<TestLine>): String =
    proposalJson(*lines.mapIndexed { index, line -> line.new("line-$index") }.toTypedArray())

/** A `POST /financial-documents/{id}/change-orders` body replacing every line with [lines]. */
fun changeOrderBody(
    expectedVersion: Int,
    lines: List<TestLine>,
): String = """{"expectedVersion":$expectedVersion,"lines":${replacementLines(lines)}}"""
