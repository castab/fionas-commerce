package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.QuantityDimension
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency

/**
 * How Fiona's prices an event, beyond the prices its catalog attaches to single offerings:
 * Fiona's pricing policy gathered into one immutable value, never a pricing framework.
 *
 * The catalog decides what can be selected and how many selections are legal; this policy
 * decides what selections and the event cost. [includedToppingCount] is how many topping
 * selections the per-guest price already covers, not how many are allowed: the catalog's
 * [toppingCategory] limits those.
 *
 * @property currency The one currency Fiona's evaluates in; every amount here is in it.
 * @property baseEventFee Charged once per event, with the service duration.
 * @property hourlyRate Charged per hour of service, pro rata.
 * @property perGuestRate The ice cream service, per guest.
 * @property includedToppingCount Topping selections the ice cream service includes.
 * @property extraToppingPerGuestRate Per guest, for each topping selection beyond those included.
 * @property allowedDurations The service durations Fiona's offers.
 * @property toppingCategory The catalog category whose selections are toppings.
 * @property guestDimension The catalog's quantity dimension for per-guest offering prices.
 */
data class FionasPricingPolicy(
    val currency: Currency,
    val baseEventFee: Money,
    val hourlyRate: Money,
    val perGuestRate: Money,
    val includedToppingCount: Int,
    val extraToppingPerGuestRate: Money,
    val allowedDurations: Set<Duration>,
    val toppingCategory: OfferingCategoryKey,
    val guestDimension: QuantityDimension,
) {
    init {
        listOf(baseEventFee, hourlyRate, perGuestRate, extraToppingPerGuestRate).forEach {
            require(it.currency == currency) { "Pricing policy amount $it is not in ${currency.currencyCode}" }
        }
        require(includedToppingCount >= 0) { "Included topping count must not be negative" }
        require(allowedDurations.isNotEmpty()) { "A pricing policy offers at least one service duration" }
        allowedDurations.forEach {
            require(!it.isNegative && !it.isZero) { "Service duration $it must be positive" }
            // Every offered duration must be charged exactly: hourly pricing never rounds.
            requireNotNull(it.exactMultipleOf(ONE_HOUR)) { "Service duration $it is not an exact number of hours" }
        }
    }

    /** One resolved base-service contribution, shared by authoritative pricing and the form preview. */
    internal fun baseServiceAmount(duration: Duration): Money {
        val hours = checkNotNull(duration.exactMultipleOf(ONE_HOUR)) { "Allowed durations are whole-minute hour fractions" }
        return baseEventFee + hourlyRate * hours
    }

    /** The same compatibility checks for a selected offering and an advertised public offering. */
    internal fun offeringPriceViolation(
        offering: Offering,
        duration: Duration,
    ): FionasOfferingsViolation? {
        val price = offering.price ?: return null
        val amount =
            when (price) {
                is OfferingPrice.Fixed -> price.amount
                is OfferingPrice.PerQuantity -> price.amount
                is OfferingPrice.PerDuration -> price.amount
            }
        if (amount.currency != currency) {
            return FionasOfferingsViolation.UnsupportedCurrency(offering.key, amount.currency, currency)
        }
        return when (price) {
            is OfferingPrice.Fixed -> null
            is OfferingPrice.PerQuantity ->
                if (price.dimension != guestDimension) {
                    FionasOfferingsViolation.UnsupportedQuantityDimension(offering.key, price.dimension)
                } else {
                    null
                }
            is OfferingPrice.PerDuration ->
                if (duration.exactMultipleOf(price.interval) == null) {
                    FionasOfferingsViolation.IncompatibleDurationPrice(offering.key, price.interval, duration)
                } else {
                    null
                }
        }
    }
}

// Declared before the policy below, whose initialization uses it.
internal val ONE_HOUR: Duration = Duration.ofHours(1)

private val USD: Currency = Currency.getInstance("USD")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

/** Fiona's current pricing, as its booking page quotes it. */
val FIONAS_PRICING_POLICY =
    FionasPricingPolicy(
        currency = USD,
        baseEventFee = usd("150.00"),
        hourlyRate = usd("50.00"),
        perGuestRate = usd("4.00"),
        includedToppingCount = 4,
        extraToppingPerGuestRate = usd("0.25"),
        allowedDurations = listOf(90L, 120L, 150L, 180L).map(Duration::ofMinutes).toSet(),
        toppingCategory = OfferingCategoryKey("topping"),
        guestDimension = QuantityDimension("guest"),
    )

/** This duration as an exact decimal multiple of [unit], or `null` when no finite decimal is exact. */
internal fun Duration.exactMultipleOf(unit: Duration): BigDecimal? =
    try {
        BigDecimal(toNanos()).divide(BigDecimal(unit.toNanos()))
    } catch (_: ArithmeticException) {
        null
    }
