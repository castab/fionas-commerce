package io.github.castab.fionas.commerce.inquiry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Stable v1 binary encoding of canonical application intent, independent of wire JSON.
 * Length-prefixed UTF-16 code units, presence bits, explicit list lengths and fixed-width integers avoid
 * ambiguous concatenation. Submitted list order determines financial line order and is retained.
 * Changing this encoding requires a deliberate compatibility decision for durable replay.
 */
internal fun CreateInquiry.Command.fingerprint(): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(1)
        output.text(name.value)
        output.text(email.value)
        output.writeBoolean(message != null)
        message?.let { output.text(it.value) }
        output.text(zipCode.value)
        output.text(eventDate.value.toString())
        output.text(eventType.name)
        output.writeBoolean(pricingInputs != null)
        pricingInputs?.let { inputs ->
            output.writeInt(inputs.catalogRevision.number)
            output.writeInt(inputs.context.guestCount)
            output.writeBoolean(inputs.context.guestCountIsMinimum)
            output.writeLong(inputs.context.duration.seconds)
            output.writeInt(inputs.context.duration.nano)
            output.writeInt(inputs.selections.categories.size)
            inputs.selections.categories.forEach { block ->
                output.text(block.category.value)
                output.writeInt(block.offerings.size)
                block.offerings.forEach { output.text(it.value) }
            }
        }
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
}

private fun DataOutputStream.text(value: String) {
    // Preserve exact canonical JVM text, including unpaired surrogates. UTF-8's default
    // replacement encoder could otherwise make distinct validated strings hash identically.
    writeInt(value.length)
    writeChars(value)
}
