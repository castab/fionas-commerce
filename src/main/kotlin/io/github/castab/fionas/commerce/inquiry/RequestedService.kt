package io.github.castab.fionas.commerce.inquiry

import io.github.castab.fionas.commerce.StrictBooleanSerializer
import io.github.castab.fionas.commerce.StrictIntSerializer
import io.github.castab.fionas.commerce.StrictStringSerializer
import io.github.castab.fionas.commerce.financial.requireCanonicalText
import io.github.castab.fionas.commerce.persistedJson
import kotlinx.serialization.Serializable

/**
 * What the customer asked Fiona to serve, as the public pricing authority (the web server)
 * recorded it with the inquiry: descriptive historical request facts for staff, never inputs to
 * price, authorize or validate the committed financial lines. Fiona checks no catalog, minimum or
 * maximum selection, or availability here; a customer may ask for something no catalog offers.
 *
 * @property guestCount The guests the event is for.
 * @property guestCountIsMinimum Whether [guestCount] is a lower bound ("100+ guests"); estimates
 *   then read "from" a total.
 * @property durationMinutes The requested service duration, when one was requested.
 * @property items What the customer chose or described, in presentation order.
 * @property pricingReference Documentary provenance of the price, for example the web server's
 *   pricing policy version. Never validated or interpreted by Fiona.
 */
data class RequestedService(
    val guestCount: Int,
    val guestCountIsMinimum: Boolean,
    val durationMinutes: Int?,
    val items: List<RequestedServiceItem>,
    val pricingReference: String?,
) {
    init {
        require(guestCount in 1..MAX_GUESTS) { "The guest count is between 1 and $MAX_GUESTS" }
        durationMinutes?.let { require(it in 1..MAX_DURATION_MINUTES) { "The duration is between 1 and $MAX_DURATION_MINUTES minutes" } }
        require(items.size <= MAX_ITEMS) { "A requested service lists at most $MAX_ITEMS items" }
        pricingReference?.let { requireCanonicalText(it, PRICING_REFERENCE_MAX_LENGTH, "A pricing reference") }
    }

    companion object {
        const val MAX_GUESTS = 100_000
        const val MAX_DURATION_MINUTES = 1440
        const val MAX_ITEMS = 100
        const val PRICING_REFERENCE_MAX_LENGTH = 200
    }
}

/**
 * One thing the customer requested, as people read it ([label]), optionally with the web
 * catalog's grouping ([group]) and stable [key]. The key identifies nothing in Fiona.
 */
data class RequestedServiceItem(
    val label: String,
    val group: String?,
    val key: String?,
) {
    init {
        requireCanonicalText(label, LABEL_MAX_LENGTH, "A requested item label")
        group?.let { requireCanonicalText(it, GROUP_MAX_LENGTH, "A requested item group") }
        key?.let { requireCanonicalText(it, KEY_MAX_LENGTH, "A requested item key") }
    }

    companion object {
        const val LABEL_MAX_LENGTH = 200
        const val GROUP_MAX_LENGTH = 120
        const val KEY_MAX_LENGTH = 120

        /** An item from submitted text: everything is trimmed, and a blank group or key is none. */
        fun of(
            label: String,
            group: String?,
            key: String?,
        ) = RequestedServiceItem(label.trim(), group.trimmedOrNull(), key.trimmedOrNull())
    }
}

internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/*
 * Fiona's persisted representation of a [RequestedService], the `requested_service` jsonb of
 * `fionas.inquiries`:
 *
 * ```
 * {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120,
 *  "items": [{"label": "Horchata soft serve", "group": "Soft serve", "key": "horchata"}],
 *  "pricingReference": "fionas-web-pricing@2026-10-01"}
 * ```
 *
 * Every property is required; `durationMinutes`, `pricingReference`, `group` and `key` may be
 * `null`. Decoding is strict and re-checks the domain invariants. Only `JdbiInquiryRepository`
 * encodes and restores it.
 */

@Serializable
private class RequestedServiceJson(
    @Serializable(with = StrictIntSerializer::class) val guestCount: Int,
    @Serializable(with = StrictBooleanSerializer::class) val guestCountIsMinimum: Boolean,
    @Serializable(with = StrictIntSerializer::class) val durationMinutes: Int?,
    val items: List<RequestedItemJson>,
    @Serializable(with = StrictStringSerializer::class) val pricingReference: String?,
)

@Serializable
private class RequestedItemJson(
    @Serializable(with = StrictStringSerializer::class) val label: String,
    @Serializable(with = StrictStringSerializer::class) val group: String?,
    @Serializable(with = StrictStringSerializer::class) val key: String?,
)

/** This request in Fiona's persisted JSON. */
internal fun RequestedService.toPersistedJson(): String {
    val persisted =
        RequestedServiceJson(
            guestCount,
            guestCountIsMinimum,
            durationMinutes,
            items.map { RequestedItemJson(it.label, it.group, it.key) },
            pricingReference,
        )
    check(persisted.restore() == this) { "A requested service must survive its persisted representation" }
    return persistedJson.encodeToString(RequestedServiceJson.serializer(), persisted)
}

/**
 * Restores the [RequestedService] stored as [json], or fails with an [IllegalStateException]
 * naming [owner] when it is malformed, has an unsupported shape, or breaks a domain invariant.
 */
internal fun restoreRequestedService(
    owner: String,
    json: String,
): RequestedService =
    try {
        persistedJson.decodeFromString(RequestedServiceJson.serializer(), json).restore()
    } catch (e: Exception) {
        throw IllegalStateException("Malformed persisted requested service of $owner: ${e.message}", e)
    }

private fun RequestedServiceJson.restore() =
    RequestedService(
        guestCount,
        guestCountIsMinimum,
        durationMinutes,
        items.map { RequestedServiceItem(it.label, it.group, it.key) },
        pricingReference,
    )
