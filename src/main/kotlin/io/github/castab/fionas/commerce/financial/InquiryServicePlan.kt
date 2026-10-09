package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant
import java.util.UUID

/**
 * What staff approved Fiona to serve, in their own words: a service commitment that may differ
 * from the customer's original request and from the financial line descriptions (soft serve
 * replaced by churros, for example). It names no product, catalog, or price: a business may
 * serve something no catalog ever offered.
 *
 * @property description The promised service, for people. Required.
 * @property guestCount The guests the commitment is for, when staff stated it.
 * @property durationMinutes The service duration, when staff stated it.
 * @property items Human-facing service items in presentation order, for example the flavors.
 */
data class ServiceCommitment(
    val description: String,
    val guestCount: Int?,
    val durationMinutes: Int?,
    val items: List<String>,
) {
    init {
        requireCanonicalText(description, DESCRIPTION_MAX_LENGTH, "A service description")
        guestCount?.let { require(it in 1..MAX_GUESTS) { "A committed guest count is between 1 and $MAX_GUESTS" } }
        durationMinutes?.let {
            require(
                it in 1..MAX_DURATION_MINUTES,
            ) { "A committed duration is between 1 and $MAX_DURATION_MINUTES minutes" }
        }
        require(items.size <= MAX_ITEMS) { "A service commitment lists at most $MAX_ITEMS items" }
        items.forEach { requireCanonicalText(it, ITEM_MAX_LENGTH, "A service item") }
    }

    companion object {
        const val DESCRIPTION_MAX_LENGTH = 2000
        const val ITEM_MAX_LENGTH = 200
        const val MAX_ITEMS = 50
        const val MAX_GUESTS = 100_000
        const val MAX_DURATION_MINUTES = 1440
    }
}

/** A staff reason attached to one final Quote line, for example why its amount was overridden. */
data class ServicePlanLineNote(
    val lineItemId: UUID,
    val note: String,
) {
    init {
        requireCanonicalText(note, NOTE_MAX_LENGTH, "A line note")
    }

    companion object {
        const val NOTE_MAX_LENGTH = 500
    }
}

/** A staff reason for a proposed line, named by its proposal identity before it has a ledger id. */
data class ProposedLineNote(
    val line: ProposedLineIdentity,
    val note: String,
) {
    init {
        requireCanonicalText(note, ServicePlanLineNote.NOTE_MAX_LENGTH, "A line note")
    }
}

/** The service commitment and line notes staff propose with a Quote; resolved against the final lines on approval. */
data class ProposedServicePlan(
    val service: ServiceCommitment,
    val lineNotes: List<ProposedLineNote> = emptyList(),
) {
    init {
        require(lineNotes.map { it.line }.toSet().size == lineNotes.size) { "A service plan notes each line at most once" }
    }
}

/**
 * The immutable approved service plan of one exact canonical Quote snapshot: Fiona business
 * facts the ledger cannot reproduce. It holds no money and copies no payment state; amounts stay
 * on the commerce-runtime snapshot, and each [lineNotes] entry names its ledger line by id.
 *
 * [reviewedVersion] is the snapshot staff reviewed (the Estimate, or the Quote being revised);
 * [approvedBy] is the verified staff user who approved it at [approvedAt].
 */
data class InquiryServicePlan(
    val inquiryId: InquiryId,
    val quote: FinancialDocumentReference,
    val reviewedVersion: Version,
    val service: ServiceCommitment,
    val lineNotes: List<ServicePlanLineNote>,
    val approvedAt: Instant,
    val approvedBy: UserId,
) {
    init {
        require(reviewedVersion < quote.version) { "A service plan's Quote must follow the version staff reviewed" }
        require(quote.version.number >= 2) { "A service plan belongs to an issued Quote" }
        require(lineNotes.map { it.lineItemId }.toSet().size == lineNotes.size) { "A service plan notes each line at most once" }
    }

    /** Whether this plan belongs to exactly [quote] and notes only its lines. */
    fun describes(quote: FinancialDocument): Boolean =
        quote is FinancialDocument.Quote &&
            quote.reference == this.quote &&
            quote.lineItems.map { it.id }.containsAll(lineNotes.map { it.lineItemId })
}

/** [plan]'s notes resolved to the final ledger ids of [lines]; an unknown proposed line rejects with a stable code. */
internal fun ProposedServicePlan.resolveNotes(lines: ResolvedLineProposal): List<ServicePlanLineNote> {
    val resolved = lineNotes.map { note -> lines.idOf(note.line)?.let { ServicePlanLineNote(it, note.note) } }
    if (resolved.any { it == null }) {
        throw lineProposalFailure(listOf(SERVICE_PLAN_LINE_NOT_FOUND), "A service plan note names a line that is not in the proposal")
    }
    return resolved.filterNotNull()
}

/** A service plan note names a line the final proposal does not contain. */
const val SERVICE_PLAN_LINE_NOT_FOUND = "SERVICE_PLAN_LINE_NOT_FOUND"

/**
 * Immutable approved service plans in the caller's transaction. Never begins, commits or rolls
 * back. A plan is written once, with the Quote it describes, and never changed.
 */
interface InquiryServicePlanRepository {
    /** Records [plan]; its Quote must exist and belong to the inquiry's canonical lineage. */
    fun insert(
        transaction: Transaction,
        plan: InquiryServicePlan,
    )

    /** The plan of exactly [quote], or `null` when that snapshot has none. */
    fun find(
        transaction: Transaction,
        quote: FinancialDocumentReference,
    ): InquiryServicePlan?
}
