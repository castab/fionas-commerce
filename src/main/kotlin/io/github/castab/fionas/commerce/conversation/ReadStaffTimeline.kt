package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.payment.RefundRecord
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.PaymentHistory
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.financial.DocumentSnapshot
import io.github.castab.fionas.commerce.financial.InquiryProposal
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.ReadStaffRequest
import io.github.castab.fionas.commerce.staff.StaffRequest
import java.time.Instant

sealed interface TimelineItem {
    val id: String
    val occurredAt: Instant

    data class Message(
        val message: ConversationMessage,
    ) : TimelineItem {
        override val id = "message:${message.id}"
        override val occurredAt = message.createdAt
    }

    data class Quote(
        val proposal: InquiryProposal,
        val snapshot: DocumentSnapshot,
        val deposit: DepositRequirementVersion,
    ) : TimelineItem {
        override val id = "proposal:${proposal.id.value}"
        override val occurredAt = proposal.issuedAt
    }

    data class Invoice(
        val snapshot: DocumentSnapshot,
    ) : TimelineItem {
        override val id = "invoice:${snapshot.document.id}:${snapshot.document.version.number}"
        override val occurredAt = snapshot.createdAt
    }

    /**
     * A receipt with its whole authoritative runtime history. [canonicalDocuments] are the canonical lineage's
     * immutable snapshots, giving each allocation its exact stage; allocations to other lineages carry none.
     */
    data class Payment(
        val history: PaymentHistory,
        val classification: PaymentClassification,
        val acceptedProposal: InquiryProposal?,
        val canonicalDocuments: Map<FinancialDocumentReference, FinancialDocument>,
    ) : TimelineItem {
        override val id = "payment:${history.payment.id}"
        override val occurredAt: Instant = history.payment.receivedAt
    }

    /** A refund of [payment], with the allocations it explicitly unwound from that payment's history. */
    data class Refund(
        val refund: RefundRecord,
        val payment: Payment,
    ) : TimelineItem {
        override val id = "refund:${refund.id}"
        override val occurredAt: Instant = refund.refundedAt
        val unwound get() = payment.history.refundAllocations.filter { it.refundReference == refund.id }
    }

    data class Milestone(
        override val id: String,
        override val occurredAt: Instant,
        val kind: MilestoneKind,
        val actor: PrincipalId,
        val reason: String? = null,
    ) : TimelineItem
}

/**
 * DEPOSIT is the receipt allocated to a published canonical Quote snapshot, which only deposit acceptance can do;
 * every other receipt in the canonical lineage's history is ADDITIONAL. Refunds never reclassify a receipt.
 */
enum class PaymentClassification { DEPOSIT, ADDITIONAL }

enum class MilestoneKind { COMMUNICATION_ACKNOWLEDGED, SERVED, CLOSED, DECLINED }

data class StaffTimeline(
    val request: StaffRequest,
    val conversation: ConversationRead,
    val items: List<TimelineItem>,
)

/** Database history is one coherent snapshot. The subsequent remote read is explicitly not cross-system atomic. */
class ReadStaffTimeline(
    private val transactor: Transactor,
    private val requests: ReadStaffRequest,
    private val ledger: FinancialLedger,
    private val establishment: ConversationEstablishment,
    private val reader: ConversationReader,
) {
    operator fun invoke(
        inquiry: InquiryId,
        canReadMessages: Boolean,
    ): StaffTimeline {
        val (request, items, established) =
            transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                val request = requests.read(transaction, inquiry)
                val versions = ledger.versionHistory(transaction, request.financial.latest.document.id)
                val deposits = ledger.depositRequirementHistory(transaction, request.financial.latest.document.id)
                val items =
                    buildList<TimelineItem> {
                        request.proposalHistory.forEach { proposal ->
                            val version = versions.single { it.document.reference == proposal.documentReference }
                            val deposit = deposits.single { it.requirement.revision == proposal.depositRequirementRevision }
                            add(TimelineItem.Quote(proposal, DocumentSnapshot(version, null), deposit))
                        }
                        versions
                            .filter { it.document is FinancialDocument.Invoice }
                            .drop(
                                1,
                            ).forEach { add(TimelineItem.Invoice(DocumentSnapshot(it, null))) }
                        val canonicalDocuments = versions.associate { it.document.reference to it.document }
                        request.payments.forEach { history ->
                            val accepted =
                                request.proposalHistory.singleOrNull { proposal ->
                                    history.allocations.any { it.financialDocumentReference == proposal.documentReference }
                                }
                            val payment =
                                TimelineItem.Payment(
                                    history,
                                    if (accepted == null) PaymentClassification.ADDITIONAL else PaymentClassification.DEPOSIT,
                                    accepted,
                                    canonicalDocuments,
                                )
                            add(payment)
                            history.refunds.forEach { add(TimelineItem.Refund(it, payment)) }
                        }
                        request.communicationHistory.filter { it.activity.kind == InquiryCommunicationKind.STAFF_ACKNOWLEDGED }.forEach {
                            val activity = it.activity
                            add(
                                TimelineItem.Milestone(
                                    "acknowledgement:${activity.id.value}",
                                    activity.occurredAt,
                                    MilestoneKind.COMMUNICATION_ACKNOWLEDGED,
                                    checkNotNull(activity.principalId),
                                ),
                            )
                        }
                        request.inquiry.lifecycle.fulfillment?.let { fulfillment ->
                            add(
                                TimelineItem.Milestone(
                                    "served:${inquiry.value}",
                                    fulfillment.served.occurredAt,
                                    MilestoneKind.SERVED,
                                    fulfillment.served.principalId,
                                ),
                            )
                            fulfillment.closed?.let {
                                add(
                                    TimelineItem.Milestone("closed:${inquiry.value}", it.occurredAt, MilestoneKind.CLOSED, it.principalId),
                                )
                            }
                        }
                        request.inquiry.lifecycle.decline?.let {
                            add(
                                TimelineItem.Milestone(
                                    "declined:${inquiry.value}",
                                    it.declinedAt,
                                    MilestoneKind.DECLINED,
                                    it.declinedBy,
                                    it.reason.value,
                                ),
                            )
                        }
                    }
                Triple(request, items, establishment.established(transaction, inquiry))
            }
        var conversation = if (canReadMessages) reader.read(inquiry) else ConversationRead(ConversationAvailability.FORBIDDEN)
        if (conversation.availability == ConversationAvailability.EMPTY &&
            established
        ) {
            conversation = ConversationRead(ConversationAvailability.MISSING)
        }
        val all =
            items +
                conversation.conversation
                    ?.messages
                    .orEmpty()
                    .map(TimelineItem::Message)
        return StaffTimeline(request, conversation, all.sortedWith(compareBy<TimelineItem> { it.occurredAt }.thenBy { it.id }))
    }
}
