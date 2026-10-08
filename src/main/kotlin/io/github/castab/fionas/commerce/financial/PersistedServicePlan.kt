package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.StrictBooleanSerializer
import io.github.castab.fionas.commerce.offering.StrictIntSerializer
import io.github.castab.fionas.commerce.offering.StrictStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.UUID

/*
 * Fiona's persisted representation of an approved service plan's content, the `plan` jsonb of
 * `fionas.inquiry_service_plans`. The row's own columns hold its identities and provenance:
 *
 * ```
 * {"context": {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120},
 *  "selections": [{"categoryKey": "cone-option", "displayName": "Cones",
 *                  "offerings": [{"offeringKey": "waffle-cone", "displayName": "Waffle cones", "description": null}]}],
 *  "lines": [{"lineItemId": "…", "origin": "ESTIMATE_LINE", "source": null, "adjustment": null, "overrideReason": "Negotiated"},
 *            {"lineItemId": "…", "origin": "GENERATED", "source": {"kind": "SELECTED_OFFERING",
 *             "categoryKey": "cone-option", "offeringKey": "waffle-cone"}, "adjustment": null, "overrideReason": null},
 *            {"lineItemId": "…", "origin": "ADJUSTMENT", "source": null,
 *             "adjustment": {"kind": "DISCOUNT", "reason": "Courtesy"}, "overrideReason": null}]}
 * ```
 *
 * It holds no money: each line names its commerce-runtime ledger line by id. It is durable
 * historical state, separate from the HTTP DTOs, and decoded strictly: every property is
 * required, `null` only where shown, unknown properties and wrong JSON types fail, and the
 * domain types' invariants are checked again. Nothing is defaulted, coerced or repaired.
 * Only [JdbiInquiryServicePlanRepository] encodes and restores it.
 */

@Serializable
private class ServicePlanJson(
    val context: PlanContextJson,
    val selections: List<PlanCategoryJson>,
    val lines: List<PlanLineJson>,
)

@Serializable
private class PlanContextJson(
    @Serializable(with = StrictIntSerializer::class) val guestCount: Int,
    @Serializable(with = StrictBooleanSerializer::class) val guestCountIsMinimum: Boolean,
    @Serializable(with = StrictIntSerializer::class) val durationMinutes: Int,
)

@Serializable
private class PlanCategoryJson(
    @Serializable(with = StrictStringSerializer::class) val categoryKey: String,
    @Serializable(with = StrictStringSerializer::class) val displayName: String,
    val offerings: List<PlanOfferingJson>,
)

@Serializable
private class PlanOfferingJson(
    @Serializable(with = StrictStringSerializer::class) val offeringKey: String,
    @Serializable(with = StrictStringSerializer::class) val displayName: String,
    @Serializable(with = StrictStringSerializer::class) val description: String?,
)

@Serializable
private class PlanLineJson(
    @Serializable(with = StrictStringSerializer::class) val lineItemId: String,
    @Serializable(with = StrictStringSerializer::class) val origin: String,
    val source: PlanSourceJson?,
    val adjustment: PlanAdjustmentJson?,
    @Serializable(with = StrictStringSerializer::class) val overrideReason: String?,
)

@Serializable
private class PlanSourceJson(
    @Serializable(with = StrictStringSerializer::class) val kind: String,
    @Serializable(with = StrictStringSerializer::class) val categoryKey: String?,
    @Serializable(with = StrictStringSerializer::class) val offeringKey: String?,
)

@Serializable
private class PlanAdjustmentJson(
    @Serializable(with = StrictStringSerializer::class) val kind: String,
    @Serializable(with = StrictStringSerializer::class) val reason: String,
)

private val planJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

/** The content of a plan, restored from its `plan` column. */
internal class PersistedServicePlanContent(
    val context: FionasOfferingsContext,
    val selections: List<ServicePlanCategory>,
    val lines: List<ServicePlanLine>,
)

/** [plan]'s content in Fiona's persisted JSON, after checking that reading it back yields the same plan content. */
internal fun encodeServicePlanContent(plan: InquiryServicePlan): String {
    val minutes = plan.context.duration.toMinutes()
    check(Duration.ofMinutes(minutes) == plan.context.duration) { "A service duration is a whole number of minutes" }
    val persisted =
        ServicePlanJson(
            PlanContextJson(plan.context.guestCount, plan.context.guestCountIsMinimum, Math.toIntExact(minutes)),
            plan.selections.map { category ->
                PlanCategoryJson(
                    category.category.value,
                    category.displayName,
                    category.offerings.map { PlanOfferingJson(it.offering.value, it.displayName, it.description) },
                )
            },
            plan.lines.map { line ->
                when (val origin = line.origin) {
                    ServicePlanLineOrigin.EstimateLine ->
                        PlanLineJson(line.lineItemId.toString(), ESTIMATE_LINE, null, null, line.overrideReason?.value)
                    is ServicePlanLineOrigin.Generated ->
                        PlanLineJson(line.lineItemId.toString(), GENERATED, origin.source.json(), null, line.overrideReason?.value)
                    is ServicePlanLineOrigin.Adjustment ->
                        PlanLineJson(
                            line.lineItemId.toString(),
                            ADJUSTMENT,
                            null,
                            PlanAdjustmentJson(origin.kind.name, origin.reason.value),
                            null,
                        )
                }
            },
        )
    val restored = persisted.restore()
    check(restored.context == plan.context && restored.selections == plan.selections && restored.lines == plan.lines) {
        "A service plan must survive its persisted representation unchanged"
    }
    return planJson.encodeToString(ServicePlanJson.serializer(), persisted)
}

/**
 * Restores the plan content stored as [json], or fails with an [IllegalStateException] naming
 * [owner] when it is malformed, has an unsupported shape, or breaks a domain invariant.
 */
internal fun restoreServicePlanContent(
    owner: String,
    json: String,
): PersistedServicePlanContent =
    try {
        planJson.decodeFromString(ServicePlanJson.serializer(), json).restore()
    } catch (e: Exception) {
        throw IllegalStateException("Malformed persisted service plan of $owner: ${e.message}", e)
    }

private fun ServicePlanJson.restore(): PersistedServicePlanContent {
    require(context.guestCount >= 1) { "guestCount must be at least 1, got ${context.guestCount}" }
    require(context.durationMinutes >= 1) { "durationMinutes must be positive, got ${context.durationMinutes}" }
    val categories = selections.map { it.categoryKey }
    require(categories.toSet().size == categories.size) { "a category is listed more than once" }
    val lineIds = lines.map { it.lineItemId }
    require(lineIds.toSet().size == lineIds.size) { "a line is described more than once" }
    return PersistedServicePlanContent(
        FionasOfferingsContext(context.guestCount, context.guestCountIsMinimum, Duration.ofMinutes(context.durationMinutes.toLong())),
        selections.map { category ->
            ServicePlanCategory(
                OfferingCategoryKey(category.categoryKey),
                category.displayName,
                category.offerings.map { ServicePlanOffering(OfferingKey(it.offeringKey), it.displayName, it.description) },
            )
        },
        lines.map { it.restore() },
    )
}

private fun PlanLineJson.restore(): ServicePlanLine {
    val origin =
        when (origin) {
            ESTIMATE_LINE -> {
                require(source == null && adjustment == null) { "An Estimate line has neither a source nor an adjustment" }
                ServicePlanLineOrigin.EstimateLine
            }
            GENERATED -> {
                require(adjustment == null) { "A generated line has no adjustment" }
                ServicePlanLineOrigin.Generated(requireNotNull(source) { "A generated line names its source" }.restore())
            }
            ADJUSTMENT -> {
                require(source == null) { "An adjustment line has no generated source" }
                val adjustment = requireNotNull(adjustment) { "An adjustment line names its adjustment" }
                ServicePlanLineOrigin.Adjustment(QuoteAdjustmentKind.valueOf(adjustment.kind), QuoteEditReason(adjustment.reason))
            }
            else -> throw IllegalArgumentException("Unknown line origin $origin")
        }
    return ServicePlanLine(
        UUID.fromString(lineItemId).also {
            require(it.toString() == lineItemId)
        },
        origin,
        overrideReason?.let(::QuoteEditReason),
    )
}

private fun PlanSourceJson.restore(): FionasChargeSource {
    val offering = kind == SELECTED_OFFERING
    require(offering == (categoryKey != null) && offering == (offeringKey != null)) { "Only a selected offering source names its keys" }
    return when (kind) {
        BASE_SERVICE -> FionasChargeSource.BaseService
        ICE_CREAM_SERVICE -> FionasChargeSource.IceCreamService
        EXTRA_TOPPINGS -> FionasChargeSource.ExtraToppings
        SELECTED_OFFERING -> FionasChargeSource.SelectedOffering(OfferingCategoryKey(categoryKey!!), OfferingKey(offeringKey!!))
        else -> throw IllegalArgumentException("Unknown charge source $kind")
    }
}

private fun FionasChargeSource.json(): PlanSourceJson =
    when (this) {
        FionasChargeSource.BaseService -> PlanSourceJson(BASE_SERVICE, null, null)
        FionasChargeSource.IceCreamService -> PlanSourceJson(ICE_CREAM_SERVICE, null, null)
        FionasChargeSource.ExtraToppings -> PlanSourceJson(EXTRA_TOPPINGS, null, null)
        is FionasChargeSource.SelectedOffering -> PlanSourceJson(SELECTED_OFFERING, category.value, offering.value)
    }

private const val ESTIMATE_LINE = "ESTIMATE_LINE"
private const val GENERATED = "GENERATED"
private const val ADJUSTMENT = "ADJUSTMENT"
private const val BASE_SERVICE = "BASE_SERVICE"
private const val ICE_CREAM_SERVICE = "ICE_CREAM_SERVICE"
private const val EXTRA_TOPPINGS = "EXTRA_TOPPINGS"
private const val SELECTED_OFFERING = "SELECTED_OFFERING"
