package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.ValidationViolation
import io.github.castab.commerce.runtime.operation.validating
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * A request-local identity for a new proposed line, so that a preview, its approval and staff
 * notes can name the line before it has a ledger id. Fiona derives the line's ledger id from
 * it ([proposedLineId]), so the same proposal always produces the same ids.
 */
@JvmInline
value class LineKey(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "A line key is 1 to $MAX_LENGTH ASCII letters, digits, underscores or hyphens" }
    }

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")
    }
}

/** Which line a proposed line is: an existing line of the reviewed snapshot, kept under its id, or a new one. */
sealed interface ProposedLineIdentity {
    /** The reviewed snapshot's line [lineItemId]: carried unchanged, or replaced in place under the same id. */
    data class Existing(
        val lineItemId: UUID,
    ) : ProposedLineIdentity

    /** A new line, named by its request-local [key]. */
    data class New(
        val key: LineKey,
    ) : ProposedLineIdentity
}

/** One line of a staff-authored final line set: its identity and its complete committed values. */
data class ProposedLine(
    val identity: ProposedLineIdentity,
    val line: PricedLine,
)

/**
 * The complete, ordered final lines a verified staff member commits for one financial
 * snapshot: never a delta or a pricing instruction. Every line states its full values. An
 * existing line may be carried unchanged or edited under its id (a direct override), a line of
 * the reviewed snapshot left out is removed, and a new line (a bespoke service, a separate
 * charge, discount or credit) is added. Fiona never checks the amounts against any catalog or
 * policy: a verified staff user is the pricing authority for negotiated terms.
 */
data class LineProposal(
    val lines: List<ProposedLine>,
) {
    init {
        requireDocumentLines(lines.map { it.line })
        val identities = lines.map { it.identity }
        require(identities.toSet().size == identities.size) { "Each proposed line names a distinct existing line or key" }
    }
}

/** Stable codes of line-proposal rejections. Clients match codes, never messages. */
object LineProposalViolations {
    /** An existing line id that the reviewed snapshot does not have. */
    const val LINE_NOT_IN_REVIEWED_DOCUMENT = "LINE_NOT_IN_REVIEWED_DOCUMENT"

    /** The proposed lines use another currency than the document. */
    const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"

    /** The proposed lines are exactly the reviewed snapshot's: no change order exists. */
    const val NO_FINANCIAL_CHANGE = "NO_FINANCIAL_CHANGE"

    /** The resulting document total would be negative. */
    const val NEGATIVE_DOCUMENT_TOTAL = "NEGATIVE_DOCUMENT_TOTAL"
}

/**
 * The ledger id of the new line [key] proposed against [documentId] at [reviewed]: derived, never
 * random, so a preview and its approval agree, and a key reused against a later version names
 * another line.
 */
internal fun proposedLineId(
    documentId: UUID,
    reviewed: Version,
    key: LineKey,
): UUID = UUID.nameUUIDFromBytes("fionas.proposed-line.v1|$documentId|${reviewed.number}|${key.value}".toByteArray())

/** Where one final line came from, relative to the reviewed snapshot. */
enum class ResolvedLineOrigin {
    /** The reviewed line, unchanged under its id. */
    CARRIED,

    /** The reviewed line's id with new committed values: a direct override or edit. */
    REPLACED,

    /** A new line, with a derived id. */
    NEW,
}

/** One final line, its origin, and the key it was proposed under when new. */
data class ResolvedLine(
    val line: LineItem,
    val origin: ResolvedLineOrigin,
    val key: LineKey?,
)

/**
 * A [LineProposal] resolved against the exact snapshot it was reviewed from: the final ordered
 * lines with their ledger ids, and the domain [ChangeOrder] that turns the reviewed lines into
 * them (`null` when they are exactly the reviewed lines). Applying [changes] to the reviewed
 * snapshot yields precisely [lines], in order.
 */
data class ResolvedLineProposal(
    val reviewed: FinancialDocument,
    val lines: List<ResolvedLine>,
    val changes: ChangeOrder?,
) {
    /** The id of the line proposed as [identity]. */
    fun idOf(identity: ProposedLineIdentity): UUID? =
        when (identity) {
            is ProposedLineIdentity.Existing -> identity.lineItemId.takeIf { id -> lines.any { it.line.id == id } }
            is ProposedLineIdentity.New -> lines.firstOrNull { it.key == identity.key }?.line?.id
        }
}

/**
 * Resolves this proposal against [reviewed], the snapshot staff reviewed: a deterministic,
 * small diff, never a generic patch language.
 *
 * Existing lines keep their ids (numerically equal values keep the stored line exactly); new
 * lines get [proposedLineId]s. The result is no change (`changes == null`) only when the final
 * lines are the reviewed snapshot itself: the same ordered ids with equal values
 * ([sameSnapshot]). Financially identical lines in another order, or a removed line re-added
 * under a new key, are changes. The change order respects commerce-domain's semantics: a
 * replacement keeps its line's position and an addition appends. The longest leading run of
 * proposed existing lines already in reviewed order stays in place (replaced when edited);
 * every other reviewed line is removed, and the remaining proposed lines, including existing
 * ones that move, are appended in order, so a moved line keeps its id.
 *
 * Fails with [CommerceFailure.ValidationFailed] carrying stable [LineProposalViolations] codes.
 */
internal fun LineProposal.resolveAgainst(reviewed: FinancialDocument): ResolvedLineProposal {
    val current = reviewed.lineItems.associateBy { it.id }
    val unknown = lines.mapNotNull { (it.identity as? ProposedLineIdentity.Existing)?.lineItemId?.takeIf { id -> id !in current } }
    val violations = mutableListOf<String>()
    if (unknown.isNotEmpty()) violations += LineProposalViolations.LINE_NOT_IN_REVIEWED_DOCUMENT
    if (lines.first().line.currency != reviewed.currency) violations += LineProposalViolations.CURRENCY_MISMATCH
    if (violations.isNotEmpty()) throw lineProposalFailure(violations)

    val resolved =
        lines.map { proposed ->
            when (val identity = proposed.identity) {
                is ProposedLineIdentity.Existing -> {
                    val stored = current.getValue(identity.lineItemId)
                    if (proposed.line.charges(stored)) {
                        ResolvedLine(stored, ResolvedLineOrigin.CARRIED, null)
                    } else {
                        ResolvedLine(proposed.line.withId(stored.id), ResolvedLineOrigin.REPLACED, null)
                    }
                }
                is ProposedLineIdentity.New ->
                    ResolvedLine(
                        proposed.line.withId(proposedLineId(reviewed.id, reviewed.version, identity.key)),
                        ResolvedLineOrigin.NEW,
                        identity.key,
                    )
            }
        }
    val finalLines = resolved.map { it.line }
    if (sameSnapshot(finalLines, reviewed.lineItems)) {
        // The same ordered ids with numerically equal values: every line is the stored line itself
        // (carried lines keep the stored object), so there is nothing to change.
        return ResolvedLineProposal(reviewed, resolved, null)
    }

    // The longest leading run of existing lines whose reviewed positions increase stays in place.
    val positions = reviewed.lineItems.withIndex().associate { (index, line) -> line.id to index }
    var kept = 0
    var lastPosition = -1
    for (line in resolved) {
        if (line.origin == ResolvedLineOrigin.NEW) break
        val position = positions.getValue(line.line.id)
        if (position <= lastPosition) break
        lastPosition = position
        kept++
    }
    val inPlace = resolved.take(kept)
    val inPlaceIds = inPlace.map { it.line.id }.toSet()
    val changes =
        ChangeOrder(
            reviewed.lineItems.filter { it.id !in inPlaceIds }.map { ChangeOrder.Change.RemoveLineItem(it.id) } +
                inPlace.filter { it.origin == ResolvedLineOrigin.REPLACED }.map {
                    ChangeOrder.Change.ReplaceLineItem(
                        it.line.id,
                        it.line,
                    )
                } +
                resolved.drop(kept).map { ChangeOrder.Change.AddLineItem(it.line) },
        )
    val successor = validating { reviewed.changeOrder(changes) }
    check(successor.lineItems == finalLines) { "The derived change order must produce exactly the proposed lines" }
    return ResolvedLineProposal(reviewed, resolved, changes)
}

/**
 * Fiona policy for a change order, evaluated under the association lock before any successor or
 * related write: the resulting document total must not be negative (zero is allowed; negative
 * lines are allowed). Returns the domain successor.
 */
internal fun validateChangeOrder(
    current: FinancialDocument,
    changes: ChangeOrder,
): FinancialDocument {
    val successor = validating { current.changeOrder(changes) }
    if (successor.total.amount.signum() < 0) {
        throw lineProposalFailure(
            listOf(LineProposalViolations.NEGATIVE_DOCUMENT_TOTAL),
            "A change order must not produce a negative financial-document total",
        )
    }
    return successor
}

internal fun lineProposalFailure(
    codes: List<String>,
    message: String = "The proposed lines are invalid: " + codes.joinToString(),
) = CommerceFailure.ValidationFailed(message, codes.distinct().map(::ValidationViolation))

/**
 * Whether [first] and [second] are the same snapshot lines: the same ordered line ids, each
 * charging exactly the same. This, never a charge-only comparison, decides that a proposal
 * changes nothing: a reordered, removed-and-re-added or otherwise re-identified line is a change
 * even when every amount is equal.
 */
internal fun sameSnapshot(
    first: List<LineItem>,
    second: List<LineItem>,
): Boolean = first.map { it.id } == second.map { it.id } && sameChargesIgnoringIds(first, second)

/**
 * Whether [first] and [second] charge exactly the same, line by line in order: descriptions,
 * quantities, prices, tax and currency, deliberately **ignoring line ids**. Amounts compare
 * numerically, whatever their scale. An equal total alone is not equal charges. Use it only to
 * compare charges (for example one proposed line with the stored line of the same id); whether a
 * snapshot changed is [sameSnapshot]'s question.
 */
internal fun sameChargesIgnoringIds(
    first: List<LineItem>,
    second: List<LineItem>,
): Boolean = first.map(::chargeOf) == second.map(::chargeOf)

/** What a line charges, without its id; amounts compare numerically, whatever their scale. */
private data class Charge(
    val description: String,
    val subDescription: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal,
    val tax: BigDecimal,
    val currency: Currency,
)

private fun chargeOf(line: LineItem) =
    Charge(
        line.description,
        line.subDescription,
        line.quantity?.stripTrailingZeros(),
        line.price.amount.stripTrailingZeros(),
        line.taxAmount.amount.stripTrailingZeros(),
        line.currency,
    )
