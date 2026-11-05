package io.github.castab.fionas.commerce.financialemail

import io.github.castab.fionas.commerce.payments.CustomerPaymentSummary
import io.github.castab.fionas.commerce.staff.StaffRequest
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PREVIEW_PAYMENT_URL = "https://example.invalid/payment-preview"
private const val FONT = "Poppins,'Segoe UI',Arial,sans-serif"
private const val LOGO = "https://www.fionasicecream.com/images/fionas_underline_cream_347x184.png"

internal fun escapeEmailHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun usd(value: BigDecimal): String =
    (if (value.signum() < 0) "-$" else "$") + value.abs().setScale(maxOf(2, value.scale())).toPlainString()

private fun multiline(value: String) = escapeEmailHtml(value).replace("\r\n", "\n").replace("\n", "<br>")

/** Greeting only: keep the authoritative full name in the event facts and customer record. */
internal fun customerGreetingName(name: String): String =
    name
        .trim { it.isWhitespace() || Character.isSpaceChar(it) }
        .takeWhile { !it.isWhitespace() && !Character.isSpaceChar(it) }
        .ifEmpty { "there" }

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
    val name = customer.name.value
    val greetingName = customerGreetingName(name)
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
            "We'd love to scoop for your event. Please review your quote and the approved serving plan below."
        } else {
            "Thank you for having Fiona's Ice Cream at your event. Here is your invoice and the remaining balance."
        }
    val service = request.effectiveServicePlan?.service
    val facts =
        buildList {
            add("Customer" to name)
            add("Event date" to inquiry.eventDate.value.format(DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)))
            add(
                "Event type" to
                    inquiry.eventType.name
                        .lowercase(Locale.US)
                        .replace('_', ' '),
            )
            add(
                "Guests requested" to
                    (
                        request.inquiry.requestedService.guestCount
                            .toString() +
                            if (request.inquiry.requestedService.guestCountIsMinimum) " minimum" else ""
                    ),
            )
            add("Event ZIP code" to inquiry.zipCode.value)
            service?.guestCount?.let { add("Approved guests" to it.toString()) }
            service?.durationMinutes?.let { add("Approved duration" to "$it minutes") }
        }
    // Group contiguous selections: preserve the staff-approved order, even when a category occurs again later.
    val selections =
        buildList {
            service?.let {
                add("Approved serving plan" to it.description)
                it.items.forEach { item -> add((item.group ?: "Selections") to item.label) }
            }
        }
    val lines =
        document.lineItems.map { line ->
            val detail =
                buildList {
                    line.subDescription?.let(::add)
                    line.quantity?.let { add("${it.stripTrailingZeros().toPlainString()} × ${usd(line.price.amount)}") }
                    add("Subtotal ${usd(line.subtotal.amount)}; tax ${usd(line.taxAmount.amount)}")
                }.joinToString(" · ")
            Triple(line.description, detail, usd(line.total.amount))
        }
    val totals =
        buildList {
            add((if (quote) "Quote total" else "Invoice total") to usd(document.total.amount))
            if (quote) {
                add("Required deposit" to usd(summary.required))
                add("Balance after deposit" to usd(document.total.amount - summary.required))
            } else {
                add("Recorded payments (net applied)" to usd(summary.paid))
                add("Remaining balance" to usd(summary.balance))
            }
        }
    val action = if (quote) "Pay required deposit" else "Pay remaining balance"
    val invitation =
        if (quote) {
            "Want to request a change? Reply to this email and we'll be happy to help."
        } else {
            "Please reply to this email with any payment questions. Thank you again!"
        }
    val paymentNote = "Your payment page always shows the current terms and payment status."
    val signed = "Sent from $signature @ Fiona's Ice Cream"
    val text =
        buildList {
            add(title)
            add(introduction)
            add("Your event\n" + facts.joinToString("\n") { "${it.first}: ${it.second}" })
            if (selections.isNotEmpty()) add(selections.joinToString("\n") { "${it.first}: ${it.second}" })
            add("$documentTitle\n" + lines.joinToString("\n") { "${it.first}\n${it.second}\n${it.third}" })
            add(totals.joinToString("\n") { "${it.first}: ${it.second}" })
            add("$action\n$paymentUrl\n$paymentNote")
            add(invitation)
            add(signed)
            add("Fiona's Ice Cream\n@fionasicecream · Fresno & Madera Ranchos")
        }.joinToString("\n\n")

    fun paragraphs(value: String) =
        value.split(Regex("\\r?\\n[ \\t]*\\r?\\n")).joinToString("") {
            "<p style=\"margin:0 0 18px;line-height:1.65\">${escapeEmailHtml(it).replace("\r\n", "\n").replace("\n", "<br>")}</p>"
        }

    fun rows(values: List<Pair<String, String>>) =
        values.joinToString("") { (label, value) ->
            "<tr><td style=\"padding:8px 12px;vertical-align:top;color:#55523f\">${escapeEmailHtml(label)}</td>" +
                "<td style=\"padding:8px 12px;overflow-wrap:anywhere\">${multiline(value)}</td></tr>"
        }
    val financialRows =
        lines.joinToString("") { (description, detail, amount) ->
            "<tr><td style=\"padding:12px 0;border-bottom:1px solid #d9d6c6;overflow-wrap:anywhere\"><strong>${escapeEmailHtml(
                description,
            )}</strong>" +
                "<br><span style=\"font-size:12px;color:#55523f\">${multiline(detail)}</span></td>" +
                "<td align=\"right\" style=\"padding:12px 0 12px 16px;vertical-align:top;white-space:nowrap;border-bottom:1px solid #d9d6c6\">$amount</td></tr>"
        }
    val html =
        """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        @font-face { font-family:Poppins; font-style:normal; font-weight:400; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiEyp8kv8JHgFVrJJfecg.woff2) format('woff2'); }
        @font-face { font-family:Poppins; font-style:normal; font-weight:600; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiByp8kv8JHgFVrLEj6Z1xlFQ.woff2) format('woff2'); }
        @font-face { font-family:Poppins; font-style:normal; font-weight:700; src:url(https://fonts.gstatic.com/s/poppins/v24/pxiByp8kv8JHgFVrLCz7Z1xlFQ.woff2) format('woff2'); }
        </style></head>
        <body style="margin:0;background:#ede5d3;color:#2b2921;font-family:$FONT;font-size:14px">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:24px 12px">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:640px;background:#f6f1e4;border-collapse:collapse">
        <tr><td align="center" bgcolor="#5f5b40" style="padding:24px"><img src="$LOGO" width="150" height="80" alt="Fiona's Ice Cream" style="border:0;max-width:100%;height:auto"></td></tr>
        <tr><td style="padding:28px 20px;overflow-wrap:anywhere">
        <h1 style="font-size:24px;line-height:1.3;color:#3d3a2b;margin:0 0 20px">${escapeEmailHtml(title)}</h1>
        ${paragraphs(introduction)}<h2 style="font-size:16px">Your event</h2>
        <table role="presentation" width="100%" style="background:#ede5d3;border-collapse:collapse">${rows(facts)}</table>
        ${if (selections.isEmpty()) {
            ""
        } else {
            "<h2 style=\"font-size:16px\">Approved menu and serving plan</h2><table role=\"presentation\" width=\"100%\">${rows(
                selections,
            )}</table>"
        }}
        <h2 style="font-size:18px">$documentTitle</h2>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse">$financialRows</table>
        <table role="presentation" width="100%" style="margin:20px 0;background:#ede5d3;font-weight:600">${rows(totals)}</table>
        <table role="presentation" align="center"><tr><td bgcolor="#5f5b40" style="border-radius:24px;text-align:center">
        <a href="${escapeEmailHtml(
            paymentUrl,
        )}" style="display:inline-block;padding:16px 24px;color:#f6f1e4;text-decoration:none;font-weight:600">$action</a>
        </td></tr></table><p style="font-size:12px;color:#55523f;text-align:center">$paymentNote</p>
        ${paragraphs(invitation)}${paragraphs(signed)}</td></tr>
        <tr><td align="center" bgcolor="#5f5b40" style="padding:24px;color:#f6f1e4">Fiona's Ice Cream<br><span style="font-size:12px">@fionasicecream · Fresno &amp; Madera Ranchos</span></td></tr>
        </table></td></tr></table></body></html>
        """.trimIndent()
    return RenderedFinancialEmail(subject, html, text)
}

internal fun financialEmailSubject(kind: FinancialEmailKind): String =
    when (kind) {
        FinancialEmailKind.QUOTE -> "Your quote from Fiona's Ice Cream"
        FinancialEmailKind.INVOICE -> "Your invoice from Fiona's Ice Cream"
    }
