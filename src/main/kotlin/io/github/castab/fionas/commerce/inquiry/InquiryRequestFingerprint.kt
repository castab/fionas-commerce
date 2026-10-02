package io.github.castab.fionas.commerce.inquiry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Stable v1 binary encoding of canonical application intent, independent of wire JSON.
 * Length-prefixed UTF-16 code units, the optional message's presence bit, explicit list lengths and fixed-width integers avoid
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
        // Fixed v1 marker, formerly a presence bit when pricing was optional. Every command is now
        // priced; keeping the byte keeps already-committed v1 fingerprints replayable unchanged.
        output.writeBoolean(true)
        output.writeInt(pricingInputs.catalogRevision.number)
        output.writeInt(pricingInputs.context.guestCount)
        output.writeBoolean(pricingInputs.context.guestCountIsMinimum)
        output.writeLong(pricingInputs.context.duration.seconds)
        output.writeInt(pricingInputs.context.duration.nano)
        output.writeInt(pricingInputs.selections.categories.size)
        pricingInputs.selections.categories.forEach { block ->
            output.text(block.category.value)
            output.writeInt(block.offerings.size)
            block.offerings.forEach { output.text(it.value) }
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
