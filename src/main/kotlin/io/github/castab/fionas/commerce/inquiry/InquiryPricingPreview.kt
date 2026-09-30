package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.fionas.commerce.offering.FionasPricingPolicy
import io.github.castab.fionas.commerce.offering.exactMultipleOf
import java.time.Duration

/**
 * Resolves Fiona's arithmetic facts from the same snapshot as the public questions.
 * A configuration mistake fails the entire read, never silently removes an active option.
 * Detailed diagnostics are logged by the runtime; customers receive its generic internal_failure.
 */
internal fun inquiryPricingPreview(
    snapshot: OfferingsSnapshot,
    publicCategories: List<OfferingCategory>,
    policy: FionasPricingPolicy,
): InquiryPricingPreview {
    val publicKeys = publicCategories.map { it.key }.toSet()
    snapshot.categories.filterNot { it.key in publicKeys }.forEach { category ->
        check(category.minimumSelections == 0) {
            "Inquiry form at ${snapshot.reference}: hidden category ${category.key.value} requires selections"
        }
    }
    val offerings =
        publicCategories.flatMap { category ->
            val options = snapshot.offeringsIn(category.key)
            check(options.size >= category.minimumSelections) {
                "Inquiry form at ${snapshot.reference}: category ${category.key.value} has fewer options than its minimum selections"
            }
            options
        }
    val durations =
        policy.allowedDurations.sorted().map { duration ->
            val minutes = Math.toIntExact(duration.toMinutes())
            check(Duration.ofMinutes(minutes.toLong()) == duration) { "Inquiry service durations must be representable as integer minutes" }
            offerings.forEach { offering ->
                val violation = policy.offeringPriceViolation(offering, duration)
                check(violation == null) {
                    "Inquiry form at ${snapshot.reference}: ${violation?.code} (${violation?.message})"
                }
            }
            InquiryDurationPricing(
                minutes,
                policy.baseServiceAmount(duration),
                offerings.mapNotNull { offering ->
                    (offering.price as? OfferingPrice.PerDuration)?.let { price ->
                        val multiplier = checkNotNull(duration.exactMultipleOf(price.interval))
                        InquiryDurationOfferingContribution(offering.key, price.amount * multiplier)
                    }
                },
            )
        }
    return InquiryPricingPreview(
        durations,
        policy.perGuestRate,
        policy.guestDimension,
        InquiryToppingAdjustment(policy.toppingCategory, policy.includedToppingCount, policy.extraToppingPerGuestRate),
    )
}
