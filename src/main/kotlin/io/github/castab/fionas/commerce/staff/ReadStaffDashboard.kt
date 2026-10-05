package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalState
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Composes canonical operations and complete customer/event enrichment in one unlocked snapshot. */
class ReadStaffDashboard(
    private val transactor: Transactor,
    private val operational: ReadInquiryOperationalStates,
    private val inquiries: InquiryRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
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
            val records = inquiries.findByIds(transaction, ids)
            check(
                records.keys == ids &&
                    records.all { (id, record) ->
                        id == record.id
                    },
            ) { "Dashboard inquiry enrichment is incomplete or corrupt" }
            val customerIds = records.values.map { it.customerId }.toSet()
            val people = customers.findByIds(transaction, customerIds)
            check(
                people.keys == customerIds &&
                    people.all { (id, customer) ->
                        id == customer.id
                    },
            ) { "Dashboard customer enrichment is incomplete or corrupt" }
            val ordered =
                snapshot.states.sortedWith(
                    compareBy({ records.getValue(it.inquiryId).createdAt }, { it.inquiryId.value.toString() }),
                )

            fun queue(predicate: (InquiryOperationalState) -> Boolean) =
                StaffWorkQueue.Available(
                    ordered.filter(predicate).map { state ->
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
                            state.financial.reconciliation.balance,
                            inquiry.createdAt,
                            state.lifecycle.fulfillment
                                ?.served
                                ?.occurredAt,
                        )
                    },
                )
            StaffDashboard(
                asOf,
                snapshot.counts,
                StaffDashboardWorkQueue(queue { it.needsQuote }, queue { it.awaitingQuoteReply }, queue { it.needsClosing }),
            )
        }
}
