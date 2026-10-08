@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationViolationResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.ComposedQuote
import io.github.castab.fionas.commerce.financial.ComposedQuoteLine
import io.github.castab.fionas.commerce.financial.InquiryServicePlan
import io.github.castab.fionas.commerce.financial.PreviewInquiryQuote
import io.github.castab.fionas.commerce.financial.QuoteAdjustment
import io.github.castab.fionas.commerce.financial.QuoteAdjustmentKey
import io.github.castab.fionas.commerce.financial.QuoteAdjustmentKind
import io.github.castab.fionas.commerce.financial.QuoteComposition
import io.github.castab.fionas.commerce.financial.QuoteCompositionViolations
import io.github.castab.fionas.commerce.financial.QuoteEditReason
import io.github.castab.fionas.commerce.financial.QuoteLineDescription
import io.github.castab.fionas.commerce.financial.QuoteLineOrigin
import io.github.castab.fionas.commerce.financial.QuoteLineOverride
import io.github.castab.fionas.commerce.financial.QuoteLineSubDescription
import io.github.castab.fionas.commerce.financial.QuoteOverrideTarget
import io.github.castab.fionas.commerce.financial.QuotePricing
import io.github.castab.fionas.commerce.financial.QuoteReviewStale
import io.github.castab.fionas.commerce.financial.ServicePlanCategory
import io.github.castab.fionas.commerce.financial.ServicePlanLineOrigin
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.BodyLens
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

/** Staff commercial intent for the initial Quote. Never lines, prices, totals or a document. */
@Serializable
data class QuoteCompositionRequest(
    @ApiProperty(
        description =
            "Where the Quote's baseline lines come from: KEEP_ESTIMATE (the reviewed Estimate's persisted lines, " +
                "no catalog pricing), REVISE_SERVICE_SELECTIONS (persisted lines with financially neutral changed " +
                "selections), or REPRICE_CONFIGURATION (a complete configuration priced from the current catalog).",
    )
    @Serializable(with = StrictQuotePricing::class)
    val pricing: QuotePricingRequest,
    @ApiProperty(
        description = "Negotiated final amounts of generated service lines, one per target. Absent means none.",
    )
    val overrides: List<QuoteOverrideRequest> = emptyList(),
    @ApiProperty(description = "Separate charges, discounts and credits, in line order after the service lines. Absent means none.")
    val adjustments: List<QuoteAdjustmentRequest> = emptyList(),
)

@Serializable
@JsonClassDiscriminator("mode")
sealed interface QuotePricingRequest {
    /** The reviewed Estimate's persisted lines and its effective configuration, unchanged. */
    @Serializable
    @SerialName("KEEP_ESTIMATE")
    @ApiClosedObject
    class KeepEstimate : QuotePricingRequest {
        override fun equals(other: Any?) = other is KeepEstimate

        override fun hashCode() = KeepEstimate::class.hashCode()
    }

    /** The persisted lines with changed selections the current catalog prices identically; guests and duration unchanged. */
    @Serializable
    @SerialName("REVISE_SERVICE_SELECTIONS")
    @ApiClosedObject
    data class ReviseServiceSelections(
        @ApiProperty(description = "The current catalog revision the selections were reviewed against; older conflicts.")
        val catalogRevision: Int,
        @ApiProperty(description = "The complete revised selections, one entry per catalog category, in presentation order.")
        val selections: List<PricingSelection>,
    ) : QuotePricingRequest

    /** A complete configuration priced from the current catalog; its lines replace the Estimate's when charges differ. */
    @Serializable
    @SerialName("REPRICE_CONFIGURATION")
    @ApiClosedObject
    data class RepriceConfiguration(
        @ApiProperty(description = "The current catalog revision to price from; older conflicts with CATALOG_REVISION_STALE.")
        val catalogRevision: Int,
        @ApiProperty(description = "The guests to price for. At least 1.")
        val guestCount: Int,
        @ApiProperty(description = "Whether `guestCount` is a lower bound. False when absent.")
        val guestCountIsMinimum: Boolean = false,
        @ApiProperty(description = "The service duration in minutes: one of 90, 120, 150, or 180.")
        val durationMinutes: Int,
        @ApiProperty(description = "The complete chosen offerings, one entry per catalog category, in presentation order.")
        val selections: List<PricingSelection>,
    ) : QuotePricingRequest
}

/** A negotiated final line amount; never a delta. */
@Serializable
data class QuoteOverrideRequest(
    @ApiProperty(
        description =
            "EXISTING_LINE names a reviewed Estimate line by its persisted id (KEEP_ESTIMATE, REVISE_SERVICE_SELECTIONS); " +
                "the other targets name a charge a REPRICE_CONFIGURATION generates.",
    )
    @Serializable(with = StrictOverrideTarget::class)
    val target: QuoteOverrideTargetRequest,
    @ApiProperty(
        description =
            "The line's final total, an exact nonnegative decimal with at most the currency's minor-unit digits. It becomes a flat " +
                "line; zero waives the charge. Negative corrections are separate DISCOUNT or CREDIT adjustments.",
    )
    val finalAmount: String,
    @ApiProperty(description = "ISO 4217 currency; must be the document's.")
    val currency: String,
    @ApiProperty(description = "Why the price was negotiated.", minLength = 1, maxLength = QuoteEditReason.MAX_LENGTH)
    val reason: String,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface QuoteOverrideTargetRequest {
    @Serializable
    @SerialName("EXISTING_LINE")
    @ApiClosedObject
    data class ExistingLine(
        @ApiProperty(description = "A line id of the reviewed Estimate snapshot.", format = "uuid")
        val lineItemId: String,
    ) : QuoteOverrideTargetRequest

    @Serializable
    @SerialName("BASE_SERVICE")
    @ApiClosedObject
    class BaseService : QuoteOverrideTargetRequest {
        override fun equals(other: Any?) = other is BaseService

        override fun hashCode() = BaseService::class.hashCode()
    }

    @Serializable
    @SerialName("ICE_CREAM_SERVICE")
    @ApiClosedObject
    class IceCreamService : QuoteOverrideTargetRequest {
        override fun equals(other: Any?) = other is IceCreamService

        override fun hashCode() = IceCreamService::class.hashCode()
    }

    @Serializable
    @SerialName("EXTRA_TOPPINGS")
    @ApiClosedObject
    class ExtraToppings : QuoteOverrideTargetRequest {
        override fun equals(other: Any?) = other is ExtraToppings

        override fun hashCode() = ExtraToppings::class.hashCode()
    }

    @Serializable
    @SerialName("SELECTED_OFFERING")
    @ApiClosedObject
    data class SelectedOffering(
        @ApiProperty(description = "The category key of the priced selection.") val category: String,
        @ApiProperty(description = "The offering key of the priced selection.") val offering: String,
    ) : QuoteOverrideTargetRequest
}

@Serializable
enum class QuoteAdjustmentKindDto { CHARGE, DISCOUNT, CREDIT }

/** One separate staff-entered line; the server assigns its id and its sign. */
@Serializable
data class QuoteAdjustmentRequest(
    @ApiProperty(
        description = "Request-local identity, unique within the request, echoed by the preview. Not persisted.",
        pattern = "^[A-Za-z0-9_-]{1,64}$",
    )
    val clientKey: String,
    @ApiProperty(description = "CHARGE adds a positive line; DISCOUNT and CREDIT add negative lines.")
    val kind: QuoteAdjustmentKindDto,
    @ApiProperty(description = "The line's description.", minLength = 1, maxLength = QuoteLineDescription.MAX_LENGTH)
    val description: String,
    @ApiProperty(description = "Optional secondary text.", maxLength = QuoteLineSubDescription.MAX_LENGTH)
    val subDescription: String? = null,
    @ApiProperty(
        description = "The positive magnitude, an exact decimal with at most the currency's minor-unit digits; `kind` decides the sign.",
    )
    val amount: String,
    @ApiProperty(description = "ISO 4217 currency; must be the document's.")
    val currency: String,
    @ApiProperty(description = "Why the line is added.", minLength = 1, maxLength = QuoteEditReason.MAX_LENGTH)
    val reason: String,
)

@Serializable
data class PreviewInquiryQuoteRequest(
    @ApiProperty(description = "Exact reviewed current Estimate version; a stale version conflicts.")
    val expectedDocumentVersion: Int,
    val composition: QuoteCompositionRequest,
    @ApiProperty(description = "The deposit terms to resolve against the composed Quote, exactly as issuance would approve them.")
    @Serializable(with = StrictDepositTerms::class)
    val terms: DepositTermsRequest,
)

/** The approved catalog offerings, with the current catalog's names staff reviewed; never historical customer labels. */
@Serializable
data class ServiceConfigurationResponse(
    val guestCount: Int,
    val guestCountIsMinimum: Boolean,
    val durationMinutes: Int,
    @ApiProperty(
        description =
            "Every selected offering, priced or not, in presentation order, with the display names and descriptions of " +
                "`catalogRevision` as staff reviewed them at that revision.",
    )
    val selections: List<ServicePlanCategoryResponse>,
)

@Serializable
data class ServicePlanCategoryResponse(
    val category: String,
    val displayName: String,
    val offerings: List<ServicePlanOfferingResponse>,
)

@Serializable
data class ServicePlanOfferingResponse(
    val offering: String,
    val displayName: String,
    val description: String? = null,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ChargeSourceResponse {
    @Serializable
    @SerialName("BASE_SERVICE")
    class BaseService : ChargeSourceResponse {
        override fun equals(other: Any?) = other is BaseService

        override fun hashCode() = BaseService::class.hashCode()
    }

    @Serializable
    @SerialName("ICE_CREAM_SERVICE")
    class IceCreamService : ChargeSourceResponse {
        override fun equals(other: Any?) = other is IceCreamService

        override fun hashCode() = IceCreamService::class.hashCode()
    }

    @Serializable
    @SerialName("EXTRA_TOPPINGS")
    class ExtraToppings : ChargeSourceResponse {
        override fun equals(other: Any?) = other is ExtraToppings

        override fun hashCode() = ExtraToppings::class.hashCode()
    }

    @Serializable
    @SerialName("SELECTED_OFFERING")
    data class SelectedOffering(
        val category: String,
        val offering: String,
    ) : ChargeSourceResponse
}

/** Why one line exists: carried from the reviewed Estimate, generated by repricing, or entered by staff. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface QuoteLineOriginResponse {
    @Serializable
    @SerialName("ESTIMATE_LINE")
    class EstimateLine : QuoteLineOriginResponse {
        override fun equals(other: Any?) = other is EstimateLine

        override fun hashCode() = EstimateLine::class.hashCode()
    }

    @Serializable
    @SerialName("GENERATED")
    data class Generated(
        val source: ChargeSourceResponse,
    ) : QuoteLineOriginResponse

    @Serializable
    @SerialName("ADJUSTMENT")
    data class Adjustment(
        val kind: QuoteAdjustmentKindDto,
        val reason: String,
        @ApiProperty(description = "The request's clientKey; previews only, never persisted.")
        val clientKey: String? = null,
    ) : QuoteLineOriginResponse
}

/** What an overridden line charged before its negotiated final amount. */
@Serializable
data class QuoteLineOverrideResponse(
    val reason: String,
    @ApiProperty(description = "The original line's quantity; absent for a flat line.")
    val originalQuantity: String? = null,
    val originalUnitPrice: String,
    val originalTotal: String,
)

@Serializable
data class QuotePreviewLineResponse(
    @ApiProperty(
        description =
            "The persisted id this line keeps from the reviewed Estimate; absent for a new line, whose id is assigned at issuance.",
        format = "uuid",
    )
    val lineItemId: String? = null,
    val origin: QuoteLineOriginResponse,
    val description: String,
    val subDescription: String? = null,
    @ApiProperty(description = "Exact decimal quantity; absent for a flat line, including every overridden line.")
    val quantity: String? = null,
    val unitPrice: String,
    val subtotal: String,
    val taxAmount: String,
    val total: String,
    val currency: String,
    @ApiProperty(description = "Present when the line carries a negotiated final amount.")
    val override: QuoteLineOverrideResponse? = null,
)

@Serializable
data class QuoteDepositPreviewResponse(
    val terms: DepositTermsRequest,
    @ApiProperty(description = "The amount the terms resolve to against this exact Quote, with commerce's shared rules (HALF_UP).")
    val requiredAmount: DepositMoneyResponse,
)

@Serializable
data class InquiryQuotePreviewResponse(
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val documentId: String,
    @ApiProperty(description = "The Estimate version the composition was evaluated from.") val reviewedDocumentVersion: Int,
    @ApiProperty(description = "That Estimate's persisted total, for comparison; never repriced.") val estimateTotal: String,
    @ApiProperty(description = "KEEP_ESTIMATE, REVISE_SERVICE_SELECTIONS or REPRICE_CONFIGURATION.") val pricingBasis: String,
    @ApiProperty(description = "The current catalog revision whose names the service shows and, when repricing, whose prices apply.")
    val catalogRevision: Int,
    @ApiProperty(
        description =
            "Whether issuance appends an intermediate Estimate before the Quote: only when the proposed lines charge " +
                "differently from the reviewed Estimate, line by line.",
    )
    val financialChange: Boolean,
    @ApiProperty(description = "The version the issued Quote will have.") val quoteVersion: Int,
    val service: ServiceConfigurationResponse,
    @ApiProperty(description = "The proposed Quote's lines, in order.") val lines: List<QuotePreviewLineResponse>,
    val subtotal: String,
    val taxAmount: String,
    @ApiProperty(description = "The proposed Quote total, derived by commerce-domain from the lines.") val total: String,
    val currency: String,
    val deposit: QuoteDepositPreviewResponse,
    @ApiProperty(
        description =
            "Identity of exactly this reviewed result. Issuance with the same composition and terms must present it; " +
                "any authoritative difference answers 409 QUOTE_REVIEW_STALE.",
        pattern = "^[0-9a-f]{64}$",
    )
    val reviewToken: String,
)

/** Why one line of the approved Quote exists. Amounts stay on the ledger line with the same id. */
@Serializable
data class ServicePlanLineResponse(
    @ApiProperty(format = "uuid") val lineItemId: String,
    val origin: QuoteLineOriginResponse,
    @ApiProperty(
        description =
            "Present for a negotiated final amount. For an ESTIMATE_LINE, the original is the same line id in the " +
                "reviewed Estimate version.",
    )
    val overrideReason: String? = null,
)

/** The immutable approved service plan of one exact Quote snapshot; it holds no money. */
@Serializable
data class ServicePlanResponse(
    @ApiProperty(format = "uuid") val documentId: String,
    @ApiProperty(description = "The exact Quote version the plan approves.") val documentVersion: Int,
    @ApiProperty(description = "The Estimate version it was composed from.") val reviewedDocumentVersion: Int,
    val pricingBasis: String,
    val catalogRevision: Int,
    @ApiProperty(format = "date-time") val approvedAt: String,
    val principalKind: String,
    @ApiProperty(format = "uuid") val principalId: String,
    val service: ServiceConfigurationResponse,
    @ApiProperty(description = "One entry per line of the approved Quote, in its order; join on lineItemId for amounts.")
    val lines: List<ServicePlanLineResponse>,
)

/** Strict union shapes: a variant accepts exactly its own fields, so no contradictory intent parses. */
internal open class StrictUnion<T : Any>(
    private val delegate: KSerializer<T>,
    private val discriminator: String,
    private val shapes: Map<String, Pair<Set<String>, Set<String>>>,
) : KSerializer<T> {
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: throw SerializationException("A union requires JSON")
        val value = input.decodeJsonElement().jsonObject
        val tag = (value[discriminator] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val (required, optional) = shapes[tag] ?: throw SerializationException("Unknown $discriminator")
        val fields = value.keys - discriminator
        if (!fields.containsAll(required) || !(required + optional).containsAll(fields)) {
            throw SerializationException("The object must match its $discriminator")
        }
        return input.json.decodeFromJsonElement(delegate, value)
    }

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = delegate.serialize(encoder, value)
}

internal object StrictQuotePricing : StrictUnion<QuotePricingRequest>(
    QuotePricingRequest.serializer(),
    "mode",
    mapOf(
        "KEEP_ESTIMATE" to (emptySet<String>() to emptySet()),
        "REVISE_SERVICE_SELECTIONS" to (setOf("catalogRevision", "selections") to emptySet()),
        "REPRICE_CONFIGURATION" to
            (setOf("catalogRevision", "guestCount", "durationMinutes", "selections") to setOf("guestCountIsMinimum")),
    ),
)

internal object StrictOverrideTarget : StrictUnion<QuoteOverrideTargetRequest>(
    QuoteOverrideTargetRequest.serializer(),
    "type",
    mapOf(
        "EXISTING_LINE" to (setOf("lineItemId") to emptySet()),
        "BASE_SERVICE" to (emptySet<String>() to emptySet()),
        "ICE_CREAM_SERVICE" to (emptySet<String>() to emptySet()),
        "EXTRA_TOPPINGS" to (emptySet<String>() to emptySet()),
        "SELECTED_OFFERING" to (setOf("category", "offering") to emptySet()),
    ),
)

private val QUOTE_DECIMAL = Regex("""-?\d+(\.\d+)?""")

private fun quoteMoney(
    amount: String,
    currency: String,
): Money {
    require(QUOTE_DECIMAL.matches(amount)) { "Amounts are exact decimal strings, for example 165.00" }
    val code =
        try {
            Currency.getInstance(currency)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Currency must be an ISO 4217 code", e)
        }
    return Money(BigDecimal(amount), code)
}

/** An unreadable line id is malformed [body] input, before domain validation. */
private fun overrideLineId(
    value: String,
    body: BodyLens<*>,
): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(body.metas.single().copy(name = "lineItemId")), cause = e)
    }

/** Translates the transport intent into application values; call inside [validating], after reading any line ids. */
internal fun QuoteCompositionRequest.domain(body: BodyLens<*>): QuoteComposition {
    val lineIds =
        overrides.map {
            (it.target as? QuoteOverrideTargetRequest.ExistingLine)?.lineItemId?.let { id ->
                overrideLineId(id, body)
            }
        }
    return validating {
        QuoteComposition(
            pricing =
                when (val mode = pricing) {
                    is QuotePricingRequest.KeepEstimate -> QuotePricing.KeepEstimate
                    is QuotePricingRequest.ReviseServiceSelections ->
                        QuotePricing.ReviseServiceSelections(
                            OfferingsRevision.of(mode.catalogRevision),
                            OfferingSelections(
                                mode.selections.map {
                                    OfferingCategorySelection(
                                        OfferingCategoryKey(it.category),
                                        it.offerings.map(::OfferingKey),
                                    )
                                },
                            ),
                        )
                    is QuotePricingRequest.RepriceConfiguration ->
                        QuotePricing.RepriceConfiguration(
                            pricingInputs(
                                mode.catalogRevision,
                                mode.guestCount,
                                mode.guestCountIsMinimum,
                                mode.durationMinutes,
                                mode.selections.map { it.category to it.offerings },
                            ),
                        )
                },
            overrides =
                overrides.zip(lineIds) { override, lineId ->
                    QuoteLineOverride(
                        when (val target = override.target) {
                            is QuoteOverrideTargetRequest.ExistingLine -> QuoteOverrideTarget.ExistingLine(lineId!!)
                            is QuoteOverrideTargetRequest.BaseService -> QuoteOverrideTarget.GeneratedCharge(FionasChargeSource.BaseService)
                            is QuoteOverrideTargetRequest.IceCreamService ->
                                QuoteOverrideTarget.GeneratedCharge(FionasChargeSource.IceCreamService)
                            is QuoteOverrideTargetRequest.ExtraToppings ->
                                QuoteOverrideTarget.GeneratedCharge(FionasChargeSource.ExtraToppings)
                            is QuoteOverrideTargetRequest.SelectedOffering ->
                                QuoteOverrideTarget.GeneratedCharge(
                                    FionasChargeSource.SelectedOffering(OfferingCategoryKey(target.category), OfferingKey(target.offering)),
                                )
                        },
                        quoteMoney(override.finalAmount, override.currency),
                        QuoteEditReason.of(override.reason),
                    )
                },
            adjustments =
                adjustments.map { adjustment ->
                    QuoteAdjustment(
                        QuoteAdjustmentKey(adjustment.clientKey),
                        QuoteAdjustmentKind.valueOf(adjustment.kind.name),
                        QuoteLineDescription.of(adjustment.description),
                        QuoteLineSubDescription.ofOptional(adjustment.subDescription),
                        quoteMoney(adjustment.amount, adjustment.currency),
                        QuoteEditReason.of(adjustment.reason),
                    )
                },
        )
    }
}

private fun FionasOfferingsContext.toResponse(selections: List<ServicePlanCategory>) =
    ServiceConfigurationResponse(
        guestCount,
        guestCountIsMinimum,
        Math.toIntExact(duration.toMinutes()).also { check(Duration.ofMinutes(it.toLong()) == duration) },
        selections.map { category ->
            ServicePlanCategoryResponse(
                category.category.value,
                category.displayName,
                category.offerings.map { ServicePlanOfferingResponse(it.offering.value, it.displayName, it.description) },
            )
        },
    )

private fun FionasChargeSource.toResponse(): ChargeSourceResponse =
    when (this) {
        FionasChargeSource.BaseService -> ChargeSourceResponse.BaseService()
        FionasChargeSource.IceCreamService -> ChargeSourceResponse.IceCreamService()
        FionasChargeSource.ExtraToppings -> ChargeSourceResponse.ExtraToppings()
        is FionasChargeSource.SelectedOffering -> ChargeSourceResponse.SelectedOffering(category.value, offering.value)
    }

private fun QuoteAdjustmentKind.toResponse() = QuoteAdjustmentKindDto.valueOf(name)

private fun LineItem.quantityText() = quantity?.stripTrailingZeros()?.toPlainString()

private fun ComposedQuoteLine.toResponse() =
    QuotePreviewLineResponse(
        lineItemId = line.id.toString().takeIf { persisted },
        origin =
            when (val origin = origin) {
                QuoteLineOrigin.EstimateLine -> QuoteLineOriginResponse.EstimateLine()
                is QuoteLineOrigin.Generated -> QuoteLineOriginResponse.Generated(origin.source.toResponse())
                is QuoteLineOrigin.Adjustment ->
                    QuoteLineOriginResponse.Adjustment(origin.kind.toResponse(), origin.reason.value, origin.key.value)
            },
        description = line.description,
        subDescription = line.subDescription,
        quantity = line.quantityText(),
        unitPrice = line.price.decimal(),
        subtotal = line.subtotal.decimal(),
        taxAmount = line.taxAmount.decimal(),
        total = line.total.decimal(),
        currency = line.currency.currencyCode,
        override =
            override?.let {
                QuoteLineOverrideResponse(
                    it.reason.value,
                    it.original.quantityText(),
                    it.original.price.decimal(),
                    it.original.total.decimal(),
                )
            },
    )

internal fun ComposedQuote.toResponse() =
    InquiryQuotePreviewResponse(
        inquiryId = inquiryId.value.toString(),
        documentId = reviewed.id.toString(),
        reviewedDocumentVersion = reviewed.version.number,
        estimateTotal = reviewed.total.decimal(),
        pricingBasis = basis.name,
        catalogRevision = catalogRevision.number,
        financialChange = financialChange,
        quoteVersion = quote.version.number,
        service = configuration.context.toResponse(selections),
        lines = lines.map { it.toResponse() },
        subtotal = quote.subtotal.decimal(),
        taxAmount = quote.taxAmount.decimal(),
        total = quote.total.decimal(),
        currency = quote.currency.currencyCode,
        deposit =
            QuoteDepositPreviewResponse(
                terms.toResponse(),
                DepositMoneyResponse(requiredDeposit.amount.toPlainString(), requiredDeposit.currency.currencyCode),
            ),
        reviewToken = reviewToken.value,
    )

internal fun InquiryServicePlan.toResponse() =
    ServicePlanResponse(
        documentId = quote.id.toString(),
        documentVersion = quote.version.number,
        reviewedDocumentVersion = reviewedEstimate.version.number,
        pricingBasis = basis.name,
        catalogRevision = catalogRevision.number,
        approvedAt = approvedAt.toString(),
        principalKind =
            when (principalId) {
                is UserId -> "USER"
                is ServiceId -> "SERVICE"
            },
        principalId =
            when (val actor = principalId) {
                is UserId -> actor.value.toString()
                is ServiceId -> actor.value.toString()
            },
        service = context.toResponse(selections),
        lines =
            lines.map { line ->
                ServicePlanLineResponse(
                    line.lineItemId.toString(),
                    when (val origin = line.origin) {
                        ServicePlanLineOrigin.EstimateLine -> QuoteLineOriginResponse.EstimateLine()
                        is ServicePlanLineOrigin.Generated -> QuoteLineOriginResponse.Generated(origin.source.toResponse())
                        is ServicePlanLineOrigin.Adjustment ->
                            QuoteLineOriginResponse.Adjustment(
                                origin.kind.toResponse(),
                                origin.reason.value,
                            )
                    },
                    line.overrideReason?.value,
                )
            },
    )

internal const val QUOTE_REVIEW_STALE = "QUOTE_REVIEW_STALE"

private val reviewConflictBody = jsonBody(ErrorResponse.serializer())

/** Only a changed reviewed result has this local code; every other conflict keeps runtime handling. */
internal val quoteReviewStaleResponses =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: CommerceFailure.Conflict) {
                if (failure.cause !is QuoteReviewStale) throw failure
                Response(Status.CONFLICT)
                    .with(reviewConflictBody of ErrorResponse(QUOTE_REVIEW_STALE, failure.message!!))
                    .header("Cache-Control", "no-store")
            }
        }
    }

/** The composition rejection codes, documented on the composing routes. */
internal val compositionViolationExamples =
    listOf(
        QuoteCompositionViolations.OVERRIDE_TARGET_NOT_FOUND,
        QuoteCompositionViolations.QUOTE_TOTAL_NOT_POSITIVE,
    ).map(::ValidationViolationResponse)

internal const val COMPOSITION_REJECTED =
    "invalid values, ineligible selections (stable offering codes such as `OFFERING_UNAVAILABLE`), deposit terms the Quote " +
        "cannot satisfy, or a composition rejection with stable `violations` codes: `OVERRIDE_TARGET_NOT_FOUND`, " +
        "`OVERRIDE_TARGET_NOT_ALLOWED`, `DUPLICATE_OVERRIDE_TARGET`, `OVERRIDE_UNCHANGED`, `DUPLICATE_ADJUSTMENT_KEY`, " +
        "`CURRENCY_MISMATCH`, `SERVICE_SELECTIONS_UNCHANGED`, `SERVICE_SELECTIONS_CHANGE_PRICING`, `NEGATIVE_DOCUMENT_TOTAL`, " +
        "`QUOTE_TOTAL_NOT_POSITIVE`."

private val previewBody = jsonBody(PreviewInquiryQuoteRequest.serializer())
private val previewResponseBody = jsonBody(InquiryQuotePreviewResponse.serializer())

internal val exampleComposition =
    QuoteCompositionRequest(
        QuotePricingRequest.KeepEstimate(),
        listOf(
            QuoteOverrideRequest(
                QuoteOverrideTargetRequest.ExistingLine("1c1d9f7f-a05b-4d2f-9f6a-3a2b4c5d6e72"),
                "280.00",
                "USD",
                "Negotiated package rate",
            ),
        ),
        listOf(
            QuoteAdjustmentRequest(
                "travel-1",
                QuoteAdjustmentKindDto.CHARGE,
                "Additional travel fee",
                null,
                "25.00",
                "USD",
                "Outside service area",
            ),
            QuoteAdjustmentRequest(
                "courtesy-1",
                QuoteAdjustmentKindDto.DISCOUNT,
                "Courtesy discount",
                null,
                "20.00",
                "USD",
                "Accommodation",
            ),
        ),
    )

private val exampleService =
    ServiceConfigurationResponse(
        75,
        false,
        120,
        listOf(
            ServicePlanCategoryResponse(
                "soft-serve-flavor",
                "Soft Serve",
                listOf(
                    ServicePlanOfferingResponse("vanilla", "Vanilla"),
                    ServicePlanOfferingResponse("horchata", "Horchata", "Premium soft serve"),
                ),
            ),
            ServicePlanCategoryResponse("cone-option", "Cones", listOf(ServicePlanOfferingResponse("waffle-cone", "Waffle cones"))),
        ),
    )

private val examplePreview: InquiryQuotePreviewResponse =
    run {
        val estimate = exampleEstimate.lines

        fun kept(
            line: FinancialDocumentLine,
            override: QuoteLineOverrideResponse? = null,
            total: String = line.total,
        ) = QuotePreviewLineResponse(
            line.id,
            QuoteLineOriginResponse.EstimateLine(),
            line.description,
            line.subDescription.takeIf { override == null || line.quantity == null },
            line.quantity.takeIf { override == null },
            if (override == null) line.unitPrice else total,
            total,
            "0.00",
            total,
            "USD",
            override,
        )

        fun adjustment(
            key: String,
            kind: QuoteAdjustmentKindDto,
            description: String,
            amount: String,
            reason: String,
        ) = QuotePreviewLineResponse(
            null,
            QuoteLineOriginResponse.Adjustment(kind, reason, key),
            description,
            null,
            null,
            amount,
            amount,
            "0.00",
            amount,
            "USD",
        )
        InquiryQuotePreviewResponse(
            inquiryId = exampleInquiry.id,
            documentId = exampleInquiry.lifecycle.documentId,
            reviewedDocumentVersion = 1,
            estimateTotal = "681.25",
            pricingBasis = "KEEP_ESTIMATE",
            catalogRevision = 20,
            financialChange = true,
            quoteVersion = 3,
            service = exampleService,
            lines =
                listOf(
                    kept(estimate[0]),
                    kept(estimate[1], QuoteLineOverrideResponse("Negotiated package rate", "75", "4.00", "300.00"), "280.00"),
                ) + estimate.drop(2).map { kept(it) } +
                    listOf(
                        adjustment("travel-1", QuoteAdjustmentKindDto.CHARGE, "Additional travel fee", "25.00", "Outside service area"),
                        adjustment("courtesy-1", QuoteAdjustmentKindDto.DISCOUNT, "Courtesy discount", "-20.00", "Accommodation"),
                    ),
            subtotal = "666.25",
            taxAmount = "0.00",
            total = "666.25",
            currency = "USD",
            deposit = QuoteDepositPreviewResponse(DepositTermsRequest.Fixed("150.00", "USD"), DepositMoneyResponse("150.00", "USD")),
            reviewToken = "9a3f6c1e0b7d4a2f8e5c3b1a0d9f8e7c6b5a4d3c2b1a0f9e8d7c6b5a4f3e2d1c",
        )
    }

internal val exampleServicePlan =
    ServicePlanResponse(
        documentId = exampleInquiry.lifecycle.documentId,
        documentVersion = 3,
        reviewedDocumentVersion = 1,
        pricingBasis = "KEEP_ESTIMATE",
        catalogRevision = 20,
        approvedAt = "2026-10-05T17:00:00Z",
        principalKind = "USER",
        principalId = "580a28a1-7417-480a-9089-8f5f3c25c1cd",
        service = exampleService,
        lines =
            exampleEstimate.lines.mapIndexed { index, line ->
                ServicePlanLineResponse(line.id, QuoteLineOriginResponse.EstimateLine(), "Negotiated package rate".takeIf { index == 1 })
            } +
                listOf(
                    ServicePlanLineResponse(
                        "6a0d3e1c-2b4f-4c8e-9d7a-1f2e3d4c5b6a",
                        QuoteLineOriginResponse.Adjustment(QuoteAdjustmentKindDto.CHARGE, "Outside service area"),
                    ),
                    ServicePlanLineResponse(
                        "7b1e4f2d-3c5a-4d9f-8e6b-2a3f4e5d6c7b",
                        QuoteLineOriginResponse.Adjustment(QuoteAdjustmentKindDto.DISCOUNT, "Accommodation"),
                    ),
                ),
    )

/**
 * `POST /staff/requests/{inquiryId}/quote-preview`: the write-free preview of an initial Quote
 * composition. Same permissions as issuance, which publishes exactly this result.
 */
internal fun previewInquiryQuoteRoute(
    preview: (PreviewInquiryQuote.Command) -> ComposedQuote,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "quote-preview" meta {
        operationId = "previewInquiryQuote"
        summary = "Preview a composed initial Quote"
        description =
            "A query that writes nothing, even on failure. Evaluates staff intent against the canonical Estimate at exactly " +
            "`expectedDocumentVersion` (REQUESTED, no proposal) in one unlocked REPEATABLE_READ snapshot: KEEP_ESTIMATE " +
            "keeps the persisted lines and never reprices; REVISE_SERVICE_SELECTIONS keeps them for selections the current " +
            "catalog prices identically; REPRICE_CONFIGURATION prices a complete configuration from the current catalog. " +
            "Overrides set a generated line's final flat amount; adjustments add signed CHARGE/DISCOUNT/CREDIT lines. " +
            "Returns the proposed lines with provenance, the domain-derived totals, the deposit resolved against that exact " +
            "Quote, the service with the current catalog's reviewed names, and a reviewToken. Issue the result with " +
            "`issueInquiryProposal` and the same composition, terms and reviewToken. Requires BOTH " +
            "`commerce.financial-document.create` and `commerce.deposit-requirement.manage`, like issuance. Cache-Control: no-store."
        tags += Tag("Staff proposals", "Atomic publication of an exact canonical Quote and approved deposit pair.")
        receiving(previewBody to PreviewInquiryQuoteRequest(1, exampleComposition, DepositTermsRequest.Fixed("150.00", "USD")))
        returning(Status.OK, previewResponseBody to examplePreview)
        principalAuthentication()
        returningError(
            ErrorCategory.FORBIDDEN,
            "Requires BOTH commerce.financial-document.create and commerce.deposit-requirement.manage. " +
                "Unsafe cookie requests require a trusted Origin.",
            UNTRUSTED_ORIGIN,
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "Unreadable inquiry or line UUID, or request shape.", "Malformed request")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry/canonical lineage, catalog or catalog revision does not exist.", "Not found")
        catalogRevisionConflict(
            " `conflict`: expectedDocumentVersion is no longer the latest Estimate version; reload and review. " +
                "`illegal_transition`: the canonical lineage is no longer an unissued Estimate.",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            COMPOSITION_REJECTED,
            "The quote composition is invalid",
            compositionViolationExamples,
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "Corrupt canonical state or an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String ->
        access
            .requirePermission(CommercePermissions.FinancialDocumentCreate)
            .then(access.requirePermission(CommercePermissions.DepositRequirementManage))
            .then(catalogRevisionStaleResponses)
            .then { request: Request ->
                val inquiry = inquiryId(id)
                val body = previewBody(request)
                val composition = body.composition.domain(previewBody)
                val command =
                    validating {
                        PreviewInquiryQuote.Command(inquiry, Version.of(body.expectedDocumentVersion), composition, body.terms.domain())
                    }
                Response(Status.OK).header("Cache-Control", "no-store").with(previewResponseBody of preview(command).toResponse())
            }
    }
