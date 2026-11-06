package io.github.castab.fionas.commerce.financialemail

import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.payments.CustomerPaymentSummary
import io.github.castab.fionas.commerce.staff.StaffRequest
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PREVIEW_PAYMENT_URL = "https://example.invalid/payment-preview"
private const val FONT = "Poppins,'Segoe UI',Arial,sans-serif"
private const val LOGO = "https://www.fionasicecream.com/images/fionas_underline_cream_347x184.png"
private const val INSTAGRAM = "https://www.instagram.com/fionasicecream/"
private const val CONTACT = "contact@fionasicecream.com"

// Design colors. Dividers are pre-blended hex because Outlook ignores rgba borders.
private const val OLIVE = "#5f5b40"
private const val HEADING = "#3d3a2b"
private const val INK = "#2b2921"
private const val MUTED = "#55523f"
private const val CREAM = "#f6f1e4"
private const val PANEL = "#ede5d3"
private const val FOOTER_TEXT = "#e2d8c3"
private const val LEADER = "#aaa68e"
private const val PANEL_DIVIDER = "#d1cab8"
private const val CARD_DIVIDER = "#d8d4c6"

// Inline styles shared across sections; email clients ignore most stylesheet rules.
private const val CAPS = "font-family:$FONT;font-size:11px;line-height:1.4;font-weight:600;letter-spacing:.14em;text-transform:uppercase"
private const val SECTION = "margin:0;$CAPS;color:$HEADING"
private const val LABEL = "$CAPS;font-size:10px;color:$MUTED"
private const val BODY = "font-family:$FONT;font-size:14px;line-height:1.65;color:$INK"
private const val SMALL = "font-family:$FONT;font-size:13.5px;line-height:1.6;color:$INK"
private const val CAPTION = "font-family:$FONT;font-size:11.5px;line-height:1.55;color:$MUTED"
private const val TABLE = "role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
private const val PILL =
    "display:inline-block;white-space:nowrap;padding:4px 10px;border-radius:999px;background:$OLIVE;color:$CREAM;font-family:$FONT;" +
        "font-size:9.5px;line-height:1.4;font-weight:600;letter-spacing:.14em;text-transform:uppercase"
private const val SPACER = "<div style=\"height:24px;line-height:24px;font-size:1px\">&nbsp;</div>"
private const val AMOUNT = "font-family:$FONT;font-size:20px;line-height:1.3;font-weight:700;color:$HEADING"
private const val LINE_CELL = "padding:8px 0;border-bottom:2px dotted $LEADER"
private val PARAGRAPH_BREAK = Regex("\\r?\\n[ \\t]*\\r?\\n")

internal fun escapeEmailHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/** Exact amount; a typographic minus marks credits. Never rounded. */
private fun usd(value: BigDecimal): String =
    (if (value.signum() < 0) "−$" else "$") + value.abs().setScale(maxOf(2, value.scale())).toPlainString()

private fun multiline(value: String) = escapeEmailHtml(value).replace("\r\n", "\n").replace("\n", "<br>")

private fun paragraphs(
    value: String,
    style: String,
) = value.split(PARAGRAPH_BREAK).joinToString("") { "<p style=\"$style\">${multiline(it)}</p>" }

private fun sectionHeading(text: String) = "<h2 style=\"$SECTION\">${escapeEmailHtml(text)}</h2>"

private fun factRows(facts: List<Pair<String, String>>) =
    facts.chunked(2).joinToString("") { pair ->
        val filler = if (pair.size == 1) "<td class=\"stack\" width=\"50%\"></td>" else ""
        "<tr>" +
            pair.joinToString("") { (name, value) ->
                "<td class=\"stack\" width=\"50%\" valign=\"top\" style=\"padding:8px 10px\">" +
                    "<div style=\"$LABEL\">${escapeEmailHtml(name)}</div>" +
                    "<div style=\"padding-top:2px;$SMALL;line-height:1.5;font-weight:600;overflow-wrap:anywhere\">" +
                    "${escapeEmailHtml(value)}</div></td>"
            } + filler + "</tr>"
    }

private fun menuRows(menu: List<Pair<String, String>>) =
    menu.joinToString("") { (group, labels) ->
        "<tr><td class=\"stack menu-label\" width=\"120\" valign=\"top\" " +
            "style=\"padding:4px 16px 4px 0;$LABEL;line-height:2.1\">${escapeEmailHtml(group)}</td>" +
            "<td class=\"stack\" valign=\"top\" style=\"padding:4px 0;$SMALL;overflow-wrap:anywhere\">${escapeEmailHtml(labels)}</td></tr>"
    }

private fun lineRow(line: EmailLine): String {
    val detail =
        line.detail
            ?.let { "<div style=\"padding-top:2px;$CAPTION;font-size:11px;line-height:1.4\">${multiline(it)}</div>" }
            .orEmpty()
    return "<tr><td valign=\"top\" style=\"width:100%;$LINE_CELL;overflow-wrap:anywhere\">" +
        "<div style=\"$SMALL;font-size:13px;line-height:1.4;font-weight:600\">${escapeEmailHtml(line.description)}</div>" +
        detail +
        "</td><td align=\"right\" valign=\"top\" style=\"$LINE_CELL;padding-left:16px;white-space:nowrap;" +
        "$SMALL;font-size:13px;line-height:1.4;font-weight:600;color:$HEADING\">${line.amount}</td></tr>"
}

private fun moneyCell(
    cell: MoneyCell,
    first: Boolean,
): String {
    // On phones the cells stack, and the first cell's divider moves from its right to its bottom.
    val cellClass = if (first) "stack money-first" else "stack"
    val divider = if (first) "border-right:1px solid $PANEL_DIVIDER;" else ""
    val caption = cell.caption?.let { "<div style=\"padding-top:2px;$CAPTION\">${escapeEmailHtml(it)}</div>" }.orEmpty()
    return "<td class=\"$cellClass\" width=\"50%\" valign=\"top\" style=\"${divider}padding:18px 20px\">" +
        "<div style=\"$LABEL\">${escapeEmailHtml(cell.label)}</div>" +
        "<div style=\"padding-top:4px;$AMOUNT\">${cell.amount}</div>" +
        caption + "</td>"
}

/** Greeting only: keep the authoritative full name in the event facts and customer record. */
internal fun customerGreetingName(name: String): String =
    name
        .trim { it.isWhitespace() || Character.isSpaceChar(it) }
        .takeWhile { !it.isWhitespace() && !Character.isSpaceChar(it) }
        .ifEmpty { "there" }

private fun customerEventType(type: EventType): String =
    when (type) {
        EventType.BIRTHDAY -> "Birthday party"
        EventType.WEDDING -> "Wedding"
        EventType.CORPORATE -> "Corporate event"
        EventType.SCHOOL_EVENT -> "School event"
        EventType.NEIGHBORHOOD_EVENT -> "Neighborhood event"
        EventType.OTHER -> "Other"
    }

private data class EmailLine(
    val description: String,
    val detail: String?,
    val amount: String,
)

private data class MoneyCell(
    val label: String,
    val amount: String,
    val caption: String?,
)

/** Exact ledger amounts, including precise unit rates. No rounding, catalog lookup or inferred payment terms. */
internal fun renderFinancialEmail(
    kind: FinancialEmailKind,
    request: StaffRequest,
    summary: CustomerPaymentSummary,
    message: FinancialEmailMessage?,
    signature: String,
    paymentUrl: String,
    subject: String = financialEmailSubject(kind),
): RenderedFinancialEmail {
    val inquiry = request.inquiry.inquiry
    val customer = request.inquiry.customer
    val document = request.financial.latest.document
    require(document.currency.currencyCode == "USD") { "Financial emails support USD only" }
    val quote = kind == FinancialEmailKind.QUOTE
    require(!quote || summary.required.signum() > 0) { "A payment-oriented Quote email requires a positive deposit" }
    val greetingName = customerGreetingName(customer.name.value)
    val revised = quote && request.proposalHistory.size > 1
    val documentTitle = if (quote) "Your quote" else "Your invoice"
    val title =
        if (quote) {
            "Hi $greetingName, here's your ${if (revised) "updated " else ""}quote"
        } else {
            "Thank you, $greetingName — here's your invoice"
        }
    val introduction =
        message?.value ?: if (quote) {
            "Thanks for thinking of us for your event! Here's everything we'd bring and what it comes to."
        } else {
            "Thank you for having Fiona's Ice Cream at your event. Here is your invoice and the remaining balance."
        }
    val service = request.effectiveServicePlan?.service
    val requested = request.inquiry.requestedService
    val guests =
        service?.guestCount?.let { "$it guests" }
            ?: if (requested.guestCountIsMinimum) "At least ${requested.guestCount} guests" else "${requested.guestCount} guests"
    val facts =
        listOf(
            "Date" to inquiry.eventDate.value.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US)),
            "Event type" to customerEventType(inquiry.eventType),
            "Guests" to guests,
            "Event ZIP code" to inquiry.zipCode.value,
        )
    // Collapse contiguous selections of one category; staff order is kept even when a category recurs later.
    val menu =
        buildList<Pair<String, MutableList<String>>> {
            service?.items?.forEach { item ->
                val group = item.group ?: "Other selections"
                if (lastOrNull()?.first == group) last().second.add(item.label) else add(group to mutableListOf(item.label))
            }
        }.map { (group, labels) -> group to labels.joinToString(" · ") }
    val lines =
        document.lineItems.map { line ->
            val detail =
                listOfNotNull(
                    line.subDescription,
                    line.quantity?.let { "${it.stripTrailingZeros().toPlainString()} × ${usd(line.price.amount)}" },
                ).joinToString(" · ").ifEmpty { null }
            EmailLine(line.description, detail, usd(line.total.amount))
        }
    val totalLabel = if (quote) "Quote total" else "Invoice total"
    val total = usd(document.total.amount)
    val money =
        if (quote) {
            listOf(
                MoneyCell("Deposit to hold your date", usd(summary.required), "Holds your date on our calendar"),
                MoneyCell("Balance after deposit", usd(document.total.amount - summary.required), null),
            )
        } else {
            listOf(
                MoneyCell("Paid so far", usd(summary.paid), null),
                MoneyCell("Remaining balance", usd(summary.balance), null),
            )
        }
    val eventDay = inquiry.eventDate.value.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
    val preheader =
        if (quote) {
            "${if (revised) "Your updated quote" else "Your quote"} for $eventDay — $total total, ${usd(summary.required)} deposit. " +
                "Accept online to hold your date."
        } else {
            "Your invoice for $eventDay — ${usd(summary.balance)} remaining balance."
        }
    val action = if (quote) "Accept this quote" else "Pay remaining balance"
    val actionNote =
        if (quote) {
            "Accept online and pay your deposit — your date is held the moment it lands."
        } else {
            "Your payment page always shows the current balance and payment status."
        }
    val invitation =
        if (quote) {
            "Want to change something — fewer guests, a different flavor, another topping? Just reply to this email and we'll " +
                "rework the numbers and send a new quote. And if the timing or the price doesn't work out, reply and tell us; " +
                "no hard feelings at all."
        } else {
            "Questions about your invoice or payment? Just reply to this email and we'll help."
        }
    val signOff = if (quote) "Hope we get to scoop for you. 🍦" else "Thanks again for having us scoop at your event. 🍦"
    val signed = "Sent from $signature @ Fiona's Ice Cream"
    val footer = "@fionasicecream · Fresno & Madera Ranchos · $CONTACT"
    val menuText = service?.let { plan -> listOf("Your menu", plan.description) + menu.map { "${it.first}: ${it.second}" } }
    val linesText =
        listOf(if (quote) "$documentTitle (final price)" else documentTitle) +
            lines.map { line -> line.description + line.detail?.let { " ($it)" }.orEmpty() + ": " + line.amount } +
            "$totalLabel: $total"
    val text =
        buildList {
            add("$title.")
            add(introduction)
            add("Your event\n" + facts.joinToString("\n") { "${it.first}: ${it.second}" })
            menuText?.let { add(it.joinToString("\n")) }
            add(linesText.joinToString("\n"))
            add(money.joinToString("\n") { "${it.label}: ${it.amount}" })
            add("$actionNote\n$paymentUrl")
            add(invitation)
            add(signOff)
            add(signed)
            add("Fiona's Ice Cream\n$footer")
        }.joinToString("\n\n")

    val menuHtml =
        service
            ?.let { plan ->
                val rows = if (menu.isEmpty()) "" else "<table $TABLE style=\"border-collapse:collapse\">${menuRows(menu)}</table>"
                sectionHeading("Your menu") + paragraphs(plan.description, "margin:10px 0 12px;$SMALL") + rows + SPACER
            }.orEmpty()
    val quoteChip = if (quote) "<td align=\"right\" valign=\"middle\"><span style=\"$PILL\">Final price</span></td>" else ""
    val preheaderHtml = escapeEmailHtml(preheader)
    val titleHtml = escapeEmailHtml(title)
    val totalLabelHtml = escapeEmailHtml(totalLabel)
    val documentHeading = sectionHeading(documentTitle)
    val factsHtml = factRows(facts)
    val linesHtml = lines.joinToString("") { lineRow(it) }
    val moneyHtml = money.mapIndexed { index, cell -> moneyCell(cell, index == 0) }.joinToString("")
    val href = escapeEmailHtml(paymentUrl)
    val html =
        """
        <!doctype html>
        <html lang="en" xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="x-apple-disable-message-reformatting">
        <meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">
        <title>${escapeEmailHtml(subject)}</title>
        <style>
        @font-face { font-family:Poppins; font-style:normal; font-weight:400; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiEyp8kv8JHgFVrJJfecg.woff2) format('woff2'); }
        @font-face { font-family:Poppins; font-style:normal; font-weight:600; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiByp8kv8JHgFVrLEj6Z1xlFQ.woff2) format('woff2'); }
        @font-face { font-family:Poppins; font-style:normal; font-weight:700; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiByp8kv8JHgFVrLCz7Z1xlFQ.woff2) format('woff2'); }
        @media only screen and (max-width:520px) {
          .outer { padding:20px 12px 40px !important; }
          .inner { padding:24px 20px 28px !important; }
          .stack { display:block !important; width:100% !important; box-sizing:border-box; }
          .menu-label { padding:8px 0 0 !important; }
          .money-first { border-right:0 !important; border-bottom:1px solid $PANEL_DIVIDER !important; }
          .title { font-size:22px !important; }
        }
        </style></head>
        <body style="margin:0;padding:0;background:$PANEL;color:$INK;font-family:$FONT;font-size:14px">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:$PANEL">$preheaderHtml</div>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="$PANEL" style="background:$PANEL"><tr><td class="outer" align="center" style="padding:24px 24px 72px">
        <!--[if mso]><table role="presentation" width="640" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="$CREAM" style="max-width:640px;background:$CREAM;border-collapse:separate;border-radius:16px;overflow:hidden;box-shadow:0 2px 6px rgba(61,58,43,.08),0 12px 28px rgba(61,58,43,.1)">
        <tr><td align="center" bgcolor="$OLIVE" style="background:$OLIVE;padding:28px 32px;border-radius:16px 16px 0 0"><img src="$LOGO" width="150" height="80" alt="Fiona's Ice Cream" style="display:block;border:0;max-width:100%;height:auto;color:$PANEL;font-family:$FONT;font-size:14px"></td></tr>
        <tr><td class="inner" style="padding:36px 40px 40px;overflow-wrap:anywhere">
        <h1 class="title" style="margin:0 0 12px;font-family:$FONT;font-size:30px;line-height:1.2;font-weight:700;letter-spacing:-0.015em;text-transform:lowercase;color:$HEADING">$titleHtml</h1>
        ${paragraphs(introduction, "margin:0 0 24px;$BODY")}
        ${sectionHeading("Your event")}
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="$PANEL" style="margin:12px 0 24px;background:$PANEL;border-collapse:separate;border-radius:10px"><tr><td style="padding:8px 10px">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse">$factsHtml</table>
        </td></tr></table>
        $menuHtml
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;margin-bottom:8px"><tr><td valign="middle">$documentHeading</td>$quoteChip</tr></table>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse">$linesHtml</table>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;margin-top:10px"><tr>
        <td style="padding-top:10px;border-top:2px solid $OLIVE;$CAPS;color:$HEADING">$totalLabelHtml</td>
        <td align="right" style="padding-top:10px;border-top:2px solid $OLIVE;white-space:nowrap;font-family:$FONT;font-size:18px;line-height:1.3;font-weight:700;color:$HEADING">$total</td>
        </tr></table>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="$PANEL" style="margin:20px 0 24px;background:$PANEL;border-collapse:separate;border-radius:10px"><tr>$moneyHtml</tr></table>
        <table role="presentation" align="center" cellpadding="0" cellspacing="0" border="0" style="margin:0 auto"><tr><td align="center" bgcolor="$OLIVE" style="background:$OLIVE;border-radius:999px;mso-padding-alt:15px 32px">
        <a href="$href" style="display:inline-block;padding:15px 32px;border-radius:999px;color:$CREAM;font-family:$FONT;font-size:12px;line-height:16px;font-weight:600;letter-spacing:.18em;text-transform:uppercase;text-decoration:none">$action</a>
        </td></tr></table>
        <p style="margin:14px 0 0;text-align:center;font-family:$FONT;font-size:12.5px;line-height:1.6;color:$MUTED">$actionNote</p>
        ${paragraphs(invitation, "margin:24px 0 0;padding-top:20px;border-top:1px solid $CARD_DIVIDER;$BODY")}
        ${paragraphs(signOff, "margin:20px 0 0;$BODY")}
        ${paragraphs(signed, "margin:12px 0 0;font-family:$FONT;font-size:12.5px;line-height:1.6;color:$MUTED")}
        </td></tr>
        <tr><td align="center" bgcolor="$OLIVE" style="background:$OLIVE;padding:20px 32px;border-radius:0 0 16px 16px;font-family:$FONT;font-size:12px;line-height:1.6;color:$FOOTER_TEXT">
        <a href="$INSTAGRAM" style="font-family:$FONT;font-size:10.5px;line-height:1.6;font-weight:600;letter-spacing:.14em;text-transform:uppercase;color:$PANEL;text-decoration:none">@fionasicecream</a>
        <span>&nbsp;·&nbsp;Fresno &amp; Madera Ranchos&nbsp;·&nbsp;</span><a href="mailto:$CONTACT" style="color:$FOOTER_TEXT;text-decoration:none">$CONTACT</a>
        </td></tr>
        </table>
        <!--[if mso]></td></tr></table><![endif]-->
        </td></tr></table></body></html>
        """.trimIndent()
    return RenderedFinancialEmail(subject, html, text)
}

internal fun financialEmailSubject(kind: FinancialEmailKind): String =
    when (kind) {
        FinancialEmailKind.QUOTE -> "Your quote from Fiona's Ice Cream"
        FinancialEmailKind.INVOICE -> "Your invoice from Fiona's Ice Cream"
    }
