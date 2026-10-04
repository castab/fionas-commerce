package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Explicit service and exact-zero closeout, serialized with canonical financial mutations. */
class ManageInquiryFulfillment(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val associations: InquiryFinancialDocumentRepository,
    private val ledger: FinancialLedger,
    private val fulfillment: InquiryFulfillmentRepository,
    private val clock: Clock,
) {
    data class Command(
        val inquiryId: InquiryId,
        val principalId: PrincipalId,
    )

    fun markServed(command: Command): InquiryLifecycle = transition(command, close = false)

    fun close(command: Command): InquiryLifecycle = transition(command, close = true)

    private fun transition(
        command: Command,
        close: Boolean,
    ): InquiryLifecycle =
        transactor.inTransaction { transaction ->
            val id = command.inquiryId
            inquiries.findById(transaction, id) ?: throw CommerceFailure.NotFound("Inquiry ${id.value} was not found")
            val documentId =
                checkNotNull(associations.initialEstimateOf(transaction, id)) { "Inquiry ${id.value} has no canonical lineage" }
            check(associations.lockInquiryOf(transaction, documentId) == id) { "Canonical inquiry ownership changed" }
            val document = ledger.latest(transaction, documentId)
            val facts = fulfillment.find(transaction, id)
            val lifecycle = InquiryLifecycle.project(document, facts)
            if (document !is FinancialDocument.Invoice) {
                throw CommerceFailure.IllegalTransition("Inquiry must be booked before service or closeout")
            }
            val milestone = InquiryMilestone(clock.instant().truncatedTo(ChronoUnit.MICROS), command.principalId)
            if (close) {
                if (lifecycle.stage != InquiryStage.SERVED) throw CommerceFailure.IllegalTransition("Only a served inquiry can close")
                val balance = ledger.reconcile(transaction, document.reference).balance.amount
                if (balance.signum() != 0) {
                    throw CommerceFailure.IllegalTransition("Inquiry can close only with an exactly zero Invoice balance")
                }
                fulfillment.close(transaction, id, milestone)
            } else {
                if (lifecycle.stage != InquiryStage.BOOKED) {
                    throw CommerceFailure.IllegalTransition("Only a booked inquiry can be marked served")
                }
                fulfillment.serve(transaction, id, milestone)
            }
            InquiryLifecycle.project(document, fulfillment.find(transaction, id))
        }
}
