package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.InquiryServicePlan
import io.github.castab.fionas.commerce.financial.LineAuthorship
import io.github.castab.fionas.commerce.financial.LineKey
import io.github.castab.fionas.commerce.financial.LineProposal
import io.github.castab.fionas.commerce.financial.PricedLine
import io.github.castab.fionas.commerce.financial.ProposedLine
import io.github.castab.fionas.commerce.financial.ProposedLineIdentity
import io.github.castab.fionas.commerce.financial.ProposedLineNote
import io.github.castab.fionas.commerce.financial.ProposedServicePlan
import io.github.castab.fionas.commerce.financial.ServiceCommitment
import io.github.castab.fionas.commerce.financial.ServicePlanLineNote
import kotlinx.serialization.Serializable
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.lens.BodyLens
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * One already-priced financial line, exactly as the authorized commercial actor committed it.
 * Amounts and quantities are exact decimal strings, never JSON numbers; Fiona never rounds,
 * reprices or checks them against a catalog. Document totals are always derived from the lines.
 */
@Serializable
data class PricedLineRequest(
    @ApiProperty(
        description = "What the line charges for, for example `Churro catering service`. Trimmed; nonblank; at most 200 characters.",
        maxLength = PricedLine.DESCRIPTION_MAX_LENGTH,
    )
    val description: String,
    @ApiProperty(
        description = "Optional secondary text. Trimmed; blank means none; at most 500 characters.",
        maxLength = PricedLine.SUB_DESCRIPTION_MAX_LENGTH,
    )
    val subDescription: String? = null,
    @ApiProperty(
        description =
            "How many units `unitPrice` is charged for, a nonzero exact decimal string (at most 9 integer and 6 fraction " +
                "digits); absent or null for a flat charge.",
        pattern = SIGNED_DECIMAL,
    )
    val quantity: String? = null,
    @ApiProperty(
        description =
            "The price of one unit, or of the flat charge, an exact decimal string; negative for a discount or credit. " +
                "With a quantity it is a unit rate of at most ${PricedLine.MAX_UNIT_PRICE_FRACTION_DIGITS} decimal places " +
                "(USD `0.125`), kept exactly, and the extended subtotal `unitPrice × quantity` must be exact in the " +
                "currency's minor units (`0.125 × 8 = 1.00`; `0.125 × 3` is rejected). Without a quantity it is the " +
                "subtotal itself, with at most the currency's minor-unit digits. Nothing is rounded.",
        pattern = SIGNED_DECIMAL,
    )
    val unitPrice: String,
    @ApiProperty(
        description =
            "The final tax of the whole line as an amount (never a rate), an exact decimal string with at most the " +
                "currency's minor-unit digits; `0.00` when untaxed.",
        pattern = SIGNED_DECIMAL,
    )
    val taxAmount: String,
    @ApiProperty(description = "The ISO 4217 code of `unitPrice` and `taxAmount`; every line of a document uses one currency.")
    val currency: String,
)

/**
 * One line of a staff-authored final line set. Exactly one of `lineItemId` (an existing line of
 * the reviewed version: carried when its values are unchanged, or replaced in place under the
 * same id, a direct override) and `key` (a new line) is present. Reviewed lines left out are
 * removed. Every line states its complete values.
 */
@Serializable
data class ProposedLineRequest(
    @ApiProperty(description = "An existing line of the reviewed version, kept under its id. Exclusive with `key`.", format = "uuid")
    val lineItemId: String? = null,
    @ApiProperty(
        description =
            "A request-local key for a new line, 1–64 ASCII letters, digits, underscores or hyphens. Fiona derives the new " +
                "line's id from the document, the reviewed version and this key, so a preview and its approval agree. " +
                "Exclusive with `lineItemId`.",
        pattern = "^[A-Za-z0-9_-]{1,64}$",
    )
    val key: String? = null,
    @ApiProperty(
        description = "What the line charges for. Trimmed; nonblank; at most 200 characters.",
        maxLength = PricedLine.DESCRIPTION_MAX_LENGTH,
    )
    val description: String,
    @ApiProperty(description = "Optional secondary text; at most 500 characters.", maxLength = PricedLine.SUB_DESCRIPTION_MAX_LENGTH)
    val subDescription: String? = null,
    @ApiProperty(description = "A nonzero exact decimal quantity; absent or null for a flat charge.", pattern = SIGNED_DECIMAL)
    val quantity: String? = null,
    @ApiProperty(
        description =
            "The exact decimal unit rate (with a quantity: at most ${PricedLine.MAX_UNIT_PRICE_FRACTION_DIGITS} decimal " +
                "places, its extended subtotal exact in minor units) or flat price (minor-unit digits only); negative " +
                "for a discount or credit.",
        pattern = SIGNED_DECIMAL,
    )
    val unitPrice: String,
    @ApiProperty(
        description = "The line's final tax amount, an exact decimal with at most the currency's minor-unit digits; `0.00` when untaxed.",
        pattern = SIGNED_DECIMAL,
    )
    val taxAmount: String,
    @ApiProperty(description = "The ISO 4217 currency of the line; it must be the document's.")
    val currency: String,
)

/** A staff note on one final line, named like the line itself: exactly one of `lineItemId` and `key`. */
@Serializable
data class LineNoteRequest(
    @ApiProperty(description = "An existing line kept in the proposal. Exclusive with `key`.", format = "uuid")
    val lineItemId: String? = null,
    @ApiProperty(description = "The key of a new line in the proposal. Exclusive with `lineItemId`.", pattern = "^[A-Za-z0-9_-]{1,64}$")
    val key: String? = null,
    @ApiProperty(
        description = "Why the line exists or was overridden. Trimmed; nonblank; at most 500 characters.",
        maxLength = ServicePlanLineNote.NOTE_MAX_LENGTH,
    )
    val note: String,
)

/** What staff approve Fiona to serve with the Quote: never a price, product reference or catalog identity. */
@Serializable
data class ServicePlanRequest(
    @ApiProperty(
        description = "The promised service, for people, for example `Churro catering for an evening reception`. At most 2000 characters.",
        maxLength = ServiceCommitment.DESCRIPTION_MAX_LENGTH,
    )
    val description: String,
    @ApiProperty(description = "The guests the commitment is for, when stated: 1 to 100000.")
    val guestCount: Int? = null,
    @ApiProperty(description = "The service duration in minutes, when stated: 1 to 1440.")
    val durationMinutes: Int? = null,
    @ApiProperty(description = "Human-facing service items in presentation order; at most 50, each at most 200 characters.")
    val items: List<String> = emptyList(),
    @ApiProperty(description = "Optional staff notes on final Quote lines, for example the reason for an override; one per line.")
    val lineNotes: List<LineNoteRequest> = emptyList(),
)

/** A staff note on one line of the approved Quote. */
@Serializable
data class ServicePlanLineNoteResponse(
    @ApiProperty(format = "uuid") val lineItemId: String,
    val note: String,
)

/** The immutable approved service plan of one exact canonical Quote snapshot; it holds no money. */
@Serializable
data class ServicePlanResponse(
    @ApiProperty(format = "uuid") val documentId: String,
    @ApiProperty(description = "The exact Quote version the plan approves.") val documentVersion: Int,
    @ApiProperty(description = "The version staff reviewed when approving it (the Estimate, or the Quote revised).")
    val reviewedDocumentVersion: Int,
    val description: String,
    val guestCount: Int? = null,
    val durationMinutes: Int? = null,
    val items: List<String>,
    @ApiProperty(description = "Notes on lines of the approved Quote; join on lineItemId for amounts.")
    val lineNotes: List<ServicePlanLineNoteResponse>,
    @ApiProperty(format = "date-time") val approvedAt: String,
    @ApiProperty(description = "The verified staff user who approved the plan.", format = "uuid") val approvedBy: String,
)

/** Who committed a snapshot's lines: the authenticated principal whose authority the amounts rest on. */
@Serializable
data class LineAuthorshipResponse(
    @ApiProperty(description = "`SERVICE` for the public pricing authority (an inquiry's Estimate v1), `USER` for a staff user.")
    val principalKind: String,
    @ApiProperty(format = "uuid") val principalId: String,
    @ApiProperty(description = "When Fiona recorded the lines.", format = "date-time") val recordedAt: String,
)

/** The precision rules a line can break, for the documented `422` causes of every line-taking route. */
internal val LINE_PRECISION_REJECTED =
    "a flat price or tax amount with more decimal places than the currency's minor units, a unit rate with more than " +
        "${PricedLine.MAX_UNIT_PRICE_FRACTION_DIGITS} decimal places, an extended subtotal (unit rate × quantity) that is " +
        "not exact in minor units"

/** An exact decimal string as Fiona reads one: optional sign, digits, optional fraction; never an exponent. */
internal const val SIGNED_DECIMAL = "^-?[0-9]+(\\.[0-9]+)?$"

private val SIGNED_DECIMAL_TEXT = Regex(SIGNED_DECIMAL)

/** At most this many characters of decimal text are read; longer text is rejected before parsing. */
private const val MAX_DECIMAL_TEXT = 40

/** The exact decimal in [text], or a validation failure naming [what]. Call inside `validating`. */
internal fun exactDecimal(
    text: String,
    what: String,
): BigDecimal {
    require(text.length <= MAX_DECIMAL_TEXT && SIGNED_DECIMAL_TEXT.matches(text)) {
        "$what must be an exact decimal string, for example 450.00"
    }
    return BigDecimal(text)
}

internal fun currencyOf(text: String): Currency =
    try {
        Currency.getInstance(text)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Currency must be an ISO 4217 code", e)
    }

private fun line(
    description: String,
    subDescription: String?,
    quantity: String?,
    unitPrice: String,
    taxAmount: String,
    currency: String,
): PricedLine {
    val code = currencyOf(currency)
    return PricedLine.of(
        description,
        subDescription,
        quantity?.let { exactDecimal(it, "A line quantity") },
        Money(exactDecimal(unitPrice, "A line unit price"), code),
        Money(exactDecimal(taxAmount, "A line tax amount"), code),
    )
}

/** The committed lines; call inside `validating`. */
internal fun List<PricedLineRequest>.domain(): List<PricedLine> =
    map { line(it.description, it.subDescription, it.quantity, it.unitPrice, it.taxAmount, it.currency) }

/**
 * The proposal's line identities, read before domain validation: an unreadable line id is
 * malformed [body] input (`400`), and naming both or neither of `lineItemId` and `key` is too.
 */
internal fun List<ProposedLineRequest>.identities(body: BodyLens<*>): List<Pair<UUID?, String?>> =
    map { identityOf(it.lineItemId, it.key, body) }

@JvmName("noteIdentities")
internal fun List<LineNoteRequest>.identities(body: BodyLens<*>): List<Pair<UUID?, String?>> =
    map { identityOf(it.lineItemId, it.key, body) }

private fun identityOf(
    lineItemId: String?,
    key: String?,
    body: BodyLens<*>,
): Pair<UUID?, String?> {
    if ((lineItemId == null) == (key == null)) throw LensFailure(Invalid(body.metas.single().copy(name = "lineItemId")))
    val id =
        lineItemId?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw LensFailure(Invalid(body.metas.single().copy(name = "lineItemId")), cause = e)
            }
        }
    return id to key
}

private fun Pair<UUID?, String?>.identity(): ProposedLineIdentity =
    first?.let { ProposedLineIdentity.Existing(it) } ?: ProposedLineIdentity.New(LineKey(second!!))

/** The staff-authored final lines, from [identities] already read; call inside `validating`. */
internal fun List<ProposedLineRequest>.domain(identities: List<Pair<UUID?, String?>>): LineProposal =
    LineProposal(
        zip(identities) { request, identity ->
            ProposedLine(
                identity.identity(),
                line(request.description, request.subDescription, request.quantity, request.unitPrice, request.taxAmount, request.currency),
            )
        },
    )

/** The proposed service plan, from its notes' identities already read; call inside `validating`. */
internal fun ServicePlanRequest.domain(noteIdentities: List<Pair<UUID?, String?>>): ProposedServicePlan =
    ProposedServicePlan(
        ServiceCommitment(
            description.trim(),
            guestCount,
            durationMinutes,
            items.map(String::trim),
        ),
        lineNotes.zip(noteIdentities) { note, identity -> ProposedLineNote(identity.identity(), note.note.trim()) },
    )

internal fun InquiryServicePlan.toResponse() =
    ServicePlanResponse(
        documentId = quote.id.toString(),
        documentVersion = quote.version.number,
        reviewedDocumentVersion = reviewedVersion.number,
        description = service.description,
        guestCount = service.guestCount,
        durationMinutes = service.durationMinutes,
        items = service.items,
        lineNotes = lineNotes.map { it.toResponse() },
        approvedAt = approvedAt.toString(),
        approvedBy = approvedBy.value.toString(),
    )

internal fun ServicePlanLineNote.toResponse() = ServicePlanLineNoteResponse(lineItemId.toString(), note)

internal fun LineAuthorship.toResponse() =
    when (val principal = author) {
        is UserId -> LineAuthorshipResponse("USER", principal.value.toString(), recordedAt.toString())
        is ServiceId -> LineAuthorshipResponse("SERVICE", principal.value.toString(), recordedAt.toString())
    }

/**
 * Allows only a request whose authenticated principal is a staff USER; a SERVICE principal is
 * `403` whatever it holds. Placed after `AccessControl.requirePermission`, so the principal is
 * already established. Routes that commit staff-negotiated financial values use it, so a
 * service credential can never stand in for, or be recorded as, a staff approver.
 */
internal val requireStaffUser: Filter =
    Filter { next ->
        { request: Request ->
            if (authenticatedPrincipal(request) is UserId) {
                next(request)
            } else {
                errorResponse(ErrorCategory.FORBIDDEN, STAFF_USER_REQUIRED)
            }
        }
    }

/**
 * Allows only a request whose authenticated principal is a SERVICE: the public pricing authority.
 * A staff USER holding the same permission is `403`, so a browser session can never submit
 * arbitrary amounts as if it were the trusted server.
 */
internal val requireService: Filter =
    Filter { next ->
        { request: Request ->
            if (authenticatedPrincipal(request) is ServiceId) {
                next(request)
            } else {
                errorResponse(ErrorCategory.FORBIDDEN, SERVICE_REQUIRED)
            }
        }
    }

internal const val STAFF_USER_REQUIRED = "Committing negotiated financial terms requires a verified staff user"
internal const val SERVICE_REQUIRED = "Priced inquiry submission requires the public pricing authority's service principal"

/** The authenticated staff user; [requireStaffUser] has already admitted the request. */
internal fun staffUser(request: Request): UserId = authenticatedPrincipal(request) as UserId

/** The authenticated service; [requireService] has already admitted the request. */
internal fun servicePrincipal(request: Request): ServiceId = authenticatedPrincipal(request) as ServiceId

/**
 * The exact amount as a decimal string, with at least the currency's minor digits
 * (`250.00`, `0.00`) and more only when the amount has them (`9.375`). Never rounded.
 */
internal fun Money.decimal(): String {
    val exact = amount.stripTrailingZeros()
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    return (if (exact.scale() < digits) exact.setScale(digits) else exact).toPlainString()
}
