package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationAttention
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryListPosition
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalState
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import java.time.Instant
import java.util.Locale

/** Fiona navigation categories deliberately overlap; financial truth remains in the operational projection. */
enum class StaffRequestFilter {
    ALL,
    NEW,
    QUOTED,
    BOOKED,
    NEEDS_CLOSING,
    CLOSED,
    DECLINED,
    ;

    fun includes(state: InquiryOperationalState): Boolean =
        when (this) {
            ALL -> true
            NEW -> state.needsQuote
            QUOTED -> state.awaitingQuoteReply
            BOOKED -> state.booked
            NEEDS_CLOSING -> state.needsClosing
            CLOSED -> state.lifecycle.stage == InquiryStage.CLOSED
            DECLINED -> state.lifecycle.stage == InquiryStage.DECLINED
        }
}

/** Normalized literal search. No regex, SQL pattern, or locale-dependent case conversion. */
data class StaffRequestSearch(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim().lowercase(Locale.ROOT)) { "Search must be normalized" }
        require(
            value.length <= MAX_LENGTH &&
                value.codePoints().noneMatch {
                    Character.isISOControl(it) || it in 0xD800..0xDFFF
                },
        ) { "Invalid search text" }
    }

    companion object {
        const val MAX_LENGTH = 200

        fun of(raw: String?): StaffRequestSearch? {
            require(raw == null || raw.length <= MAX_LENGTH) { "Search must be at most $MAX_LENGTH characters" }
            return raw
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
                ?.let(::StaffRequestSearch)
        }
    }
}

data class StaffRequestSummary(
    val inquiry: Inquiry,
    val customer: Customer,
    val operational: InquiryOperationalState,
    val totalQualifier: DashboardTotalQualifier,
    val awaitingReplySince: Instant?,
)

data class StaffRequestPage(
    val items: List<StaffRequestSummary>,
    val next: InquiryListPosition?,
    val totalCount: Int,
)

/** Complete set-based enrichment and classification, before pagination, in one unlocked snapshot. */
class ListStaffRequests(
    private val transactor: Transactor,
    private val operational: ReadInquiryOperationalStates,
    private val inquiries: InquiryRepository,
    private val customers: CustomerRepository,
    private val communications: InquiryCommunicationRepository,
) {
    data class Command(
        val filter: StaffRequestFilter = StaffRequestFilter.ALL,
        val search: StaffRequestSearch? = null,
        val after: InquiryListPosition? = null,
        val limit: Int = DEFAULT_LIMIT,
    ) {
        init {
            require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        }
    }

    operator fun invoke(command: Command): StaffRequestPage =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val snapshot =
                try {
                    operational(transaction)
                } catch (failure: CommerceFailure.NotFound) {
                    throw IllegalStateException("Canonical request financial data is missing", failure)
                }
            val ids = snapshot.states.map { it.inquiryId }.toSet()
            val requested = inquiries.findRequestedByIds(transaction, ids)
            check(requested.keys == ids && requested.all { (id, value) -> id == value.inquiry.id }) {
                "Request inquiry enrichment is incomplete or corrupt"
            }
            val customerIds = requested.values.map { it.inquiry.customerId }.toSet()
            val people = customers.findByIds(transaction, customerIds)
            check(people.keys == customerIds && people.all { (id, value) -> id == value.id }) {
                "Request customer enrichment is incomplete or corrupt"
            }
            val attention = communications.attentionFor(transaction, ids)
            val matches =
                snapshot.states
                    .map { state ->
                        val record = requested.getValue(state.inquiryId)
                        StaffRequestSummary(
                            record.inquiry,
                            people.getValue(record.inquiry.customerId),
                            state,
                            if (state.financial.latestVersion.document is FinancialDocument.Estimate &&
                                record.requestedService.guestCountIsMinimum
                            ) {
                                DashboardTotalQualifier.FROM
                            } else {
                                DashboardTotalQualifier.EXACT
                            },
                            DashboardAttentionPolicy()
                                .needsReply(
                                    state,
                                    attention[state.inquiryId] ?: InquiryCommunicationAttention(null, null),
                                )?.attentionSince,
                        )
                    }.filter { item ->
                        command.filter.includes(item.operational) &&
                            (
                                command.search?.let { search ->
                                    item.customer.name.value
                                        .lowercase(Locale.ROOT)
                                        .contains(search.value) ||
                                        item.customer.email.value
                                            .lowercase(Locale.ROOT)
                                            .contains(search.value) ||
                                        item.inquiry.id.value
                                            .toString() == search.value
                                } ?: true
                            )
                    }.sortedWith(
                        compareByDescending<StaffRequestSummary> { it.inquiry.createdAt }.thenByDescending {
                            it.inquiry.id.value
                                .toString()
                        },
                    )
            val remaining =
                matches.filter { item ->
                    command.after?.let { after ->
                        item.inquiry.createdAt < after.createdAt ||
                            (
                                item.inquiry.createdAt == after.createdAt &&
                                    item.inquiry.id.value
                                        .toString() < after.id.value.toString()
                            )
                    } ?: true
                }
            val page = remaining.take(command.limit)
            StaffRequestPage(
                page,
                page.lastOrNull()?.takeIf { remaining.size > command.limit }?.let {
                    InquiryListPosition(
                        it.inquiry.createdAt,
                        it.inquiry.id,
                    )
                },
                matches.size,
            )
        }

    companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}
