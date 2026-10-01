package io.github.castab.fionas.commerce.inquiry

/** Opaque command identity, not a credential. Its exact spelling matters; no trimming. */
@JvmInline
value class InquirySubmissionKey(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH && FORMAT.matches(value)) { "Invalid inquiry submission key" }
    }

    companion object {
        const val MAX_LENGTH = 128
        const val PATTERN = "^[A-Za-z0-9_-]{1,128}$"
        private val FORMAT = Regex(PATTERN)
    }
}

/** Typed context carried by a runtime Conflict; never includes the previous request or fingerprint. */
class IdempotencyKeyReused : RuntimeException()
