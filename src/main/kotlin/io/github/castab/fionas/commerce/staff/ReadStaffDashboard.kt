package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationAttention
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalState
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import java.time.Clock
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Composes canonical operations and complete customer/event enrichment in one unlocked snapshot. */
class ReadStaffDashboard(
    private val transactor: Transactor,
    private val operational: ReadInquiryOperationalStates,
    private val inquiries: InquiryRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
    private val communications: InquiryCommunicationRepository,
    private val eventCalendarZone: ZoneId,
    private val policy: DashboardAttentionPolicy = DashboardAttentionPolicy(),
) {
    operator fun invoke(): StaffDashboard =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val snapshot =
                try {
                    operational(transaction)
                } catch (failure: CommerceFailure.NotFound) {
                    // A missing canonical financial lineage is internal corruption, not a caller's missing resource.
                    throw IllegalStateException("Canonical dashboard financial data is missing", failure)
                }
            val asOf = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val ids = snapshot.states.map { it.inquiryId }.toSet()
            val requested = inquiries.findRequestedByIds(transaction, ids)
            check(
                requested.keys == ids &&
                    requested.all { (id, record) ->
                        id == record.inquiry.id
                    },
            ) { "Dashboard inquiry enrichment is incomplete or corrupt" }
            val records = requested.mapValues { it.value.inquiry }
            val customerIds = records.values.map { it.customerId }.toSet()
            val people = customers.findByIds(transaction, customerIds)
            check(
                people.keys == customerIds &&
                    people.all { (id, customer) ->
                        id == customer.id
                    },
            ) { "Dashboard customer enrichment is incomplete or corrupt" }
            val attention = communications.attentionFor(transaction, ids)
            check(ids.containsAll(attention.keys)) {
                "Dashboard communication activity is outside the operational population or corrupt"
            }

            fun communication(state: InquiryOperationalState) = attention[state.inquiryId] ?: InquiryCommunicationAttention(null, null)

            fun queue(project: (InquiryOperationalState) -> StaffAttention?) =
                StaffWorkQueue(
                    snapshot.states
                        .mapNotNull { state ->
                            val reason = project(state) ?: return@mapNotNull null
                            val inquiry = records.getValue(state.inquiryId)
                            val customer = people.getValue(inquiry.customerId)
                            StaffDashboardItem(
                                inquiry.id,
                                customer.id,
                                customer.name,
                                inquiry.eventDate,
                                inquiry.eventType,
                                state.lifecycle.stage,
                                state.financial.latestVersion,
                                if (state.financial.latestVersion.document is FinancialDocument.Estimate &&
                                    requested
                                        .getValue(inquiry.id)
                                        .requestedService.guestCountIsMinimum
                                ) {
                                    DashboardTotalQualifier.FROM
                                } else {
                                    DashboardTotalQualifier.EXACT
                                },
                                state.financial.reconciliation.balance,
                                inquiry.createdAt,
                                state.lifecycle.fulfillment
                                    ?.served
                                    ?.occurredAt,
                                reason.attentionSince,
                                reason.reasons,
                            )
                        }.sortedWith(compareBy({ it.attentionSince }, { it.inquiryId.value.toString() })),
                )
            StaffDashboard(
                asOf,
                snapshot.counts,
                StaffDashboardWorkQueue(
                    queue { policy.needsReply(communication(it)) },
                    queue { policy.needsQuote(it, records.getValue(it.inquiryId).createdAt) },
                    queue {
                        policy.needsResolution(
                            it,
                            records.getValue(it.inquiryId).eventDate,
                            communication(it),
                            asOf,
                            eventCalendarZone,
                        )
                    },
                ),
            )
        }
}
