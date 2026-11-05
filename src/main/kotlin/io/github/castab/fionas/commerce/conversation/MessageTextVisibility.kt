package io.github.castab.fionas.commerce.conversation

/** Provider content availability, independent of financial-email commands and payment-link presence. */
enum class MessageTextVisibility {
    READABLE,
    PROVIDER_CONTENT_UNAVAILABLE,
}
