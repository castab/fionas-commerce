package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsViolation
import io.github.castab.commerce.offering.QuantityDimension
import java.time.Duration
import java.util.Currency

/**
 * The event facts Fiona's prices a selection with, and nothing else: who the customer is,
 * when and where the event is, and what it celebrates do not change the price.
 *
 * Values are not validated here: [FionasOfferingsEngine] rejects those Fiona's policy cannot
 * price, with a [FionasOfferingsViolation].
 *
 * @property guestCount The guests the estimate is priced for.
 * @property guestCountIsMinimum Whether [guestCount] is a lower bound ("100+ guests") rather
 *   than an expected attendance. It changes how the estimate reads ("from $X"), never the
 *   arithmetic, which always uses [guestCount].
 * @property duration The service duration.
 */
data class FionasOfferingsContext(
    val guestCount: Int,
    val guestCountIsMinimum: Boolean,
    val duration: Duration,
)

/** Why Fiona's pricing policy cannot price an otherwise structurally valid selection. Codes are stable. */
sealed interface FionasOfferingsViolation : OfferingsViolation {
    /** What is wrong, for people; may change. */
    val message: String

    data class InvalidGuestCount(
        val guestCount: Int,
    ) : FionasOfferingsViolation {
        override val code = "INVALID_GUEST_COUNT"
        override val message = "the guest count must be at least 1, got $guestCount"
    }

    data class UnsupportedDuration(
        val duration: Duration,
        val allowed: Set<Duration>,
    ) : FionasOfferingsViolation {
        override val code = "UNSUPPORTED_DURATION"
        override val message =
            "the service duration must be one of ${allowed.sorted().joinToString { "${it.toMinutes()}" }} minutes, " +
                "got ${duration.toMinutes()}"
    }

    data class UnsupportedCurrency(
        val offering: OfferingKey,
        val currency: Currency,
        val expected: Currency,
    ) : FionasOfferingsViolation {
        override val code = "UNSUPPORTED_CURRENCY"
        override val message =
            "offering ${offering.value} is priced in ${currency.currencyCode}; estimates are in ${expected.currencyCode}"
    }

    data class UnsupportedQuantityDimension(
        val offering: OfferingKey,
        val dimension: QuantityDimension,
    ) : FionasOfferingsViolation {
        override val code = "UNSUPPORTED_QUANTITY_DIMENSION"
        override val message = "offering ${offering.value} is priced per ${dimension.value}, which Fiona's cannot count"
    }

    data class IncompatibleDurationPrice(
        val offering: OfferingKey,
        val interval: Duration,
        val duration: Duration,
    ) : FionasOfferingsViolation {
        override val code = "INCOMPATIBLE_DURATION_PRICE"
        override val message =
            "offering ${offering.value} is priced per $interval, which does not divide a $duration service exactly"
    }
}
