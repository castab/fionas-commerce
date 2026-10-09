package io.github.castab.fionas.commerce.inquiry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Stable v2 binary encoding of a public inquiry command's canonical intent, independent of wire
 * JSON (property order and whitespace never matter). Length-prefixed UTF-16 code units, presence
 * bits for optional values, explicit list lengths and fixed-width integers avoid ambiguous
 * concatenation.
 *
 * It binds everything meaningful: the customer and event values, the requested service, and
 * every committed line's description, sub-description, quantity, unit price, tax and currency,
 * in submitted order (which is the Estimate's line order), so a reused key can never swap
 * commercial terms. Amounts and quantities are compared numerically (`450.00` equals `450`).
 * Generated identities (line and document ids), clocks, the submitting principal and the key
 * itself are excluded. Changing this encoding requires a deliberate durable-replay decision.
 */
internal fun CreateInquiry.Command.fingerprint(): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(2)
        output.text(name.value)
        output.text(email.value)
        output.optional(message?.value)
        output.text(zipCode.value)
        output.text(eventDate.value.toString())
        output.text(eventType.name)
        output.writeInt(requestedService.guestCount)
        output.writeBoolean(requestedService.guestCountIsMinimum)
        output.optional(requestedService.durationMinutes?.toString())
        output.writeInt(requestedService.items.size)
        requestedService.items.forEach { item ->
            output.text(item.label)
            output.optional(item.group)
            output.optional(item.key)
        }
        output.optional(requestedService.pricingReference)
        output.writeInt(lines.size)
        lines.forEach { line ->
            output.text(line.description)
            output.optional(line.subDescription)
            output.optional(line.quantity?.decimalText())
            output.text(line.unitPrice.amount.decimalText())
            output.text(line.taxAmount.amount.decimalText())
            output.text(line.currency.currencyCode)
        }
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
}

private fun BigDecimal.decimalText(): String = stripTrailingZeros().toPlainString()

private fun DataOutputStream.optional(value: String?) {
    writeBoolean(value != null)
    value?.let { text(it) }
}

private fun DataOutputStream.text(value: String) {
    // Preserve exact canonical JVM text, including unpaired surrogates. UTF-8's default
    // replacement encoder could otherwise make distinct validated strings hash identically.
    writeInt(value.length)
    writeChars(value)
}
