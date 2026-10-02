package io.github.castab.fionas.commerce.inquiry

/** The event's five-digit US ZIP code, kept as text to preserve leading zeroes. Not an address verification. */
@JvmInline
value class ZipCode(
    val value: String,
) {
    init {
        require(Regex(PATTERN).matches(value)) { "ZIP code must contain exactly five digits" }
    }

    companion object {
        const val LENGTH = 5
        const val PATTERN = "^[0-9]{5}$"

        /** Required inquiry information: trim surrounding whitespace, then validate all five digits. */
        fun of(submitted: String): ZipCode = ZipCode(submitted.trim())
    }
}
