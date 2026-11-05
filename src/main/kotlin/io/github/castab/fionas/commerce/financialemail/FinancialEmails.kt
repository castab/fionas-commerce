package io.github.castab.fionas.commerce.financialemail

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.ConversationEstablishment
import io.github.castab.fionas.commerce.conversation.ConversationExpectation
import io.github.castab.fionas.commerce.conversation.ConversationSender
import io.github.castab.fionas.commerce.conversation.ProviderSendOutcome
import io.github.castab.fionas.commerce.conversation.ProviderSendRequest
import io.github.castab.fionas.commerce.conversation.TopicLookup
import io.github.castab.fionas.commerce.conversation.conversationSendRequest
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.payments.CustomerPayments
import io.github.castab.fionas.commerce.payments.JdbiPaymentAccessRepository
import io.github.castab.fionas.commerce.payments.PaymentAccessAction
import io.github.castab.fionas.commerce.staff.ReadStaffRequest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Manual application communication. Network I/O is outside the short transactions authorizing and recording it. */
class FinancialEmails(
    private val transactor: Transactor,
    private val requests: ReadStaffRequest,
    private val associations: InquiryFinancialDocumentRepository,
    private val ledger: FinancialLedger,
    private val communications: InquiryCommunicationRepository,
    private val establishment: ConversationEstablishment,
    private val users: (UserId) -> User?,
    private val sender: ConversationSender?,
    private val payments: CustomerPayments?,
    private val clock: Clock,
    private val records: JdbiFinancialEmailRepository = JdbiFinancialEmailRepository(),
    private val access: JdbiPaymentAccessRepository = JdbiPaymentAccessRepository(),
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun preview(
        intent: FinancialEmailIntent,
        actor: UserId,
    ): FinancialEmailPreview {
        val signature = signature(actor)
        val preview = transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { compose(it, intent, signature) }
        val provider = provider()
        val lookup = lookup(provider, intent.inquiryId)
        val established = transactor.inTransaction { establishment.established(it, intent.inquiryId) }
        if (lookup == TopicLookup.Missing && established) emailRefused("conversation_missing", "The established conversation is missing")
        validateRecipient(provider, preview, lookup)
        val subject = (lookup as? TopicLookup.Found)?.subject ?: financialEmailSubject(intent.kind)
        return FinancialEmailPreview(
            intent,
            preview.request,
            preview.summary,
            RenderedFinancialEmail(subject, preview.email.html, preview.email.text),
            preview.reviewToken,
        )
    }

    fun send(command: SendFinancialEmail): FinancialEmailResult {
        val existing = transactor.inTransaction { records.find(it, command.intent.inquiryId, command.key) }
        if (existing != null) return resume(existing, command)
        return try {
            sendNew(command)
        } catch (refused: FinancialEmailRefused) {
            // A duplicate may commit while this caller reviews state or performs the provider lookup.
            // Prefer its immutable command over an apparent stale-preview/conversation refusal.
            val concurrent = transactor.inTransaction { records.find(it, command.intent.inquiryId, command.key) }
            if (concurrent != null) resume(concurrent, command) else throw refused
        }
    }

    private fun sendNew(command: SendFinancialEmail): FinancialEmailResult {
        val provider = provider()
        // Runtime directory convenience reads own a transaction; resolve the profile before entering ours.
        val senderSignature = signature(command.sentBy)
        val before =
            transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                val preview = compose(transaction, command.intent, senderSignature)
                requireReviewed(preview, command.reviewToken)
                Triple(
                    preview,
                    establishment.established(
                        transaction,
                        command.intent.inquiryId,
                    ),
                    records
                        .history(transaction, command.intent.inquiryId)
                        .map {
                            it.id
                        }.toSet(),
                )
            }
        val lookup = lookup(provider, command.intent.inquiryId)
        validateRecipient(provider, before.first, lookup)
        when (lookup) {
            TopicLookup.Missing -> {
                if (before.second) emailRefused("conversation_missing", "The established conversation is missing")
                if (command.expectation != ConversationExpectation.Empty) changed()
            }
            is TopicLookup.Found ->
                if ((command.expectation as? ConversationExpectation.Latest)?.messageId != lookup.latestMessageId ||
                    command.expectation == ConversationExpectation.Empty
                ) {
                    changed()
                }
            else -> error("Lookup was not resolved")
        }
        val claimed =
            transactor.inTransaction { transaction ->
                lock(transaction, command.intent.inquiryId)
                records.find(transaction, command.intent.inquiryId, command.key)?.let { return@inTransaction it to null }
                val history = records.history(transaction, command.intent.inquiryId)
                if (history.any { it.outcome == FinancialEmailOutcome.UNKNOWN }) {
                    emailRefused(
                        "financial_email_in_progress",
                        "Reconcile the earlier financial email with its original command before sending another",
                    )
                }
                if (history.map { it.id }.toSet() != before.third) changed()
                val currentSignature = senderSignature
                val preview = compose(transaction, command.intent, currentSignature)
                requireReviewed(preview, command.reviewToken)
                validateRecipient(provider, preview, lookup)
                val capability = access.forInquiry(transaction, command.intent.inquiryId)
                if (capability?.revoked ==
                    true
                ) {
                    emailRefused("payment_access_revoked", "Reactivate payment access deliberately before reviewing this email")
                }
                // This existing authorized capability is the sole issuer. An active link is returned unchanged.
                val url =
                    checkNotNull(
                        checkNotNull(
                            payments,
                        ).manageAccess(transaction, command.intent.inquiryId, PaymentAccessAction.ISSUE, command.sentBy).url,
                    )
                val afterIssue = compose(transaction, command.intent, currentSignature)
                val recordId = FinancialEmailId(newId())
                val conversation = (lookup as? TopicLookup.Found)?.conversationId
                val request = providerRequest(provider, command, afterIssue, currentSignature, conversation, url, recordId)
                val record =
                    FinancialEmailRecord(
                        recordId,
                        command.intent.inquiryId,
                        command.key,
                        command.fingerprint(),
                        command.sentBy,
                        command.intent.kind,
                        command.intent.documentId,
                        command.intent.expectedDocumentVersion.number,
                        command.intent.expectedBusinessRevision,
                        command.intent.proposalId,
                        afterIssue.reviewToken,
                        request.digest,
                        currentSignature,
                        conversation == null,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                        // Financial emails do not acknowledge customer correspondence. They clear through zero only.
                        0L,
                        conversation,
                    )
                records.insert(transaction, record)
                record to request
            }
        return if (claimed.second ==
            null
        ) {
            resume(claimed.first, command)
        } else {
            dispatch(provider, claimed.first, checkNotNull(claimed.second), false)
        }
    }

    private fun resume(
        record: FinancialEmailRecord,
        command: SendFinancialEmail,
    ): FinancialEmailResult {
        if (record.fingerprint !=
            command.fingerprint()
        ) {
            emailRefused("IDEMPOTENCY_KEY_REUSED", "This key already represents a different financial email")
        }
        when (record.outcome) {
            FinancialEmailOutcome.ACCEPTED -> return result(record, true)
            FinancialEmailOutcome.REJECTED -> rejected()
            FinancialEmailOutcome.UNKNOWN -> Unit
        }
        val provider = provider()
        val prepared =
            try {
                transactor.inTransaction { transaction ->
                    lock(transaction, record.inquiryId)
                    val current = checkNotNull(records.find(transaction, record.inquiryId, record.key))
                    when (current.outcome) {
                        FinancialEmailOutcome.ACCEPTED, FinancialEmailOutcome.REJECTED -> return@inTransaction current to null
                        FinancialEmailOutcome.UNKNOWN -> Unit
                    }
                    val preview = compose(transaction, command.intent, record.signature)
                    if (!provider.configuration.permits(preview.request.inquiry.customer.email)) recoveryRequired()
                    if (preview.reviewToken != record.recoveryDigest) recoveryRequired()
                    val capability = access.forInquiry(transaction, record.inquiryId) ?: recoveryRequired()
                    if (capability.revoked) recoveryRequired()
                    val url =
                        checkNotNull(
                            checkNotNull(
                                payments,
                            ).manageAccess(transaction, record.inquiryId, PaymentAccessAction.ISSUE, record.sentBy).url,
                        )
                    val exact =
                        providerRequest(
                            provider,
                            command,
                            preview,
                            record.signature,
                            if (record.opening) null else record.conversationId,
                            url,
                            record.id,
                        )
                    if (exact.digest != record.providerRequest) recoveryRequired()
                    current to exact
                }
            } catch (failure: FinancialEmailRefused) {
                if (failure.code == "financial_email_reconciliation_required") throw failure
                recoveryRequired()
            }
        // Original conversation expectation is fingerprinted, never rechecked for recovery.
        val request = prepared.second
        if (request == null) {
            if (prepared.first.outcome == FinancialEmailOutcome.REJECTED) rejected()
            return result(prepared.first, true)
        }
        return dispatch(provider, prepared.first, request, true)
    }

    private fun dispatch(
        provider: ConversationSender,
        record: FinancialEmailRecord,
        request: ProviderSendRequest,
        replayed: Boolean,
    ): FinancialEmailResult {
        val attempt =
            transactor.inTransaction { transaction ->
                communications.lock(transaction, record.inquiryId)
                val current = checkNotNull(records.find(transaction, record.inquiryId, record.key))
                current to if (current.outcome == FinancialEmailOutcome.UNKNOWN) records.beginDispatch(transaction, record.id) else 0
            }
        when (attempt.first.outcome) {
            FinancialEmailOutcome.ACCEPTED -> return result(attempt.first, true)
            FinancialEmailOutcome.REJECTED -> rejected()
            FinancialEmailOutcome.UNKNOWN -> Unit
        }
        return when (val sent = provider.send(request)) {
            is ProviderSendOutcome.Accepted -> {
                transactor.inTransaction { transaction ->
                    communications.lock(transaction, record.inquiryId)
                    val current = checkNotNull(records.find(transaction, record.inquiryId, record.key))
                    if (current.outcome == FinancialEmailOutcome.UNKNOWN) {
                        val activity =
                            InquiryCommunication(
                                InquiryCommunicationId(newId()),
                                record.inquiryId,
                                InquiryCommunicationKind.STAFF_EMAIL_SENT,
                                clock.instant().truncatedTo(ChronoUnit.MICROS),
                                record.sentBy,
                                record.observedThrough,
                            )
                        communications.append(transaction, activity)
                        records.accept(transaction, record, sent.conversationId, sent.messageId, sent.state, activity.id)
                    } else {
                        check(current.outcome == FinancialEmailOutcome.ACCEPTED && current.messageId == sent.messageId) {
                            "Financial email result disagrees with accepted command"
                        }
                    }
                }
                FinancialEmailResult(record.id, FinancialEmailOutcome.ACCEPTED, replayed, sent.conversationId, sent.messageId, sent.state)
            }
            ProviderSendOutcome.Rejected -> {
                val resolved =
                    transactor.inTransaction { transaction ->
                        communications.lock(transaction, record.inquiryId)
                        val current = checkNotNull(records.find(transaction, record.inquiryId, record.key))
                        if (current.outcome == FinancialEmailOutcome.ACCEPTED) return@inTransaction current
                        // Refusing a later request cannot refute acceptance of an earlier or competing dispatch.
                        if (current.dispatchCount > 1) return@inTransaction current
                        records.reject(transaction, record.id)
                        null
                    }
                if (resolved != null) result(resolved, true) else rejected()
            }
            is ProviderSendOutcome.Indeterminate -> {
                val current =
                    transactor.inTransaction { transaction ->
                        communications.lock(transaction, record.inquiryId)
                        val current = checkNotNull(records.find(transaction, record.inquiryId, record.key))
                        if (current.outcome == FinancialEmailOutcome.ACCEPTED) return@inTransaction current
                        check(current.outcome == FinancialEmailOutcome.UNKNOWN) { "Indeterminate send disagrees with its recorded outcome" }
                        if (current.messageId == null) {
                            records.observeIndeterminate(transaction, record.id, sent.conversationId, sent.messageId)
                        } else {
                            check(current.conversationId == sent.conversationId && current.messageId == sent.messageId) {
                                "Indeterminate provider identity changed under the same command"
                            }
                        }
                        checkNotNull(records.find(transaction, record.inquiryId, record.key))
                    }
                result(current, replayed)
            }
            ProviderSendOutcome.Unknown -> result(attempt.first, replayed)
        }
    }

    private fun compose(
        transaction: Transaction,
        intent: FinancialEmailIntent,
        signature: String,
    ): FinancialEmailPreview {
        val request = requests.read(transaction, intent.inquiryId)
        val document = request.financial.latest.document
        if (document.id != intent.documentId || document.version != intent.expectedDocumentVersion) stale()
        when (intent.kind) {
            FinancialEmailKind.QUOTE -> {
                if (request.inquiry.lifecycle.stage != InquiryStage.QUOTED || document !is FinancialDocument.Quote) ineligible()
                val proposal = checkNotNull(request.proposal)
                val deposit =
                    request.deposit.depositRequirement?.requirement as? DepositRequirement.Active
                        ?: emailRefused(
                            "financial_email_deposit_required",
                            "A payment-oriented Quote email requires a current positive approved deposit",
                        )
                if (proposal.id != intent.proposalId ||
                    proposal.quoteRevision != intent.expectedBusinessRevision ||
                    deposit.revision.number != intent.expectedDepositRequirementRevision
                ) {
                    stale()
                }
            }
            FinancialEmailKind.INVOICE -> {
                if (request.inquiry.lifecycle.stage != InquiryStage.SERVED || document !is FinancialDocument.Invoice) ineligible()
                if (request.currentInvoiceRevision != intent.expectedBusinessRevision) stale()
            }
        }
        val summary =
            (
                payments ?: emailRefused(
                    "stripe_disabled",
                    "Customer payments are not configured",
                )
            ).collectionSummary(transaction, request)
        if (!summary.canPay) emailRefused("financial_email_ineligible", "Collection is unavailable: ${summary.unavailableReason}")
        val capability = access.forInquiry(transaction, intent.inquiryId)
        if (capability?.revoked ==
            true
        ) {
            emailRefused("payment_access_revoked", "Reactivate payment access deliberately before reviewing this email")
        }
        val email = renderFinancialEmail(intent.kind, request, summary, intent.message, signature, PREVIEW_PAYMENT_URL)
        val connection = provider().configuration
        val facts =
            request.payments
                .flatMap { history ->
                    listOf(history.payment.id) + history.allocations.map { it.id } + history.refunds.map { it.id } +
                        history.refundAllocations.map { it.id }
                }.map(UUID::toString)
        val token =
            financialEmailDigest(
                listOf(
                    "fionas.financial-email.review.v1",
                    intent.inquiryId.value.toString(),
                    intent.kind.name,
                    document.id.toString(),
                    document.version.number.toString(),
                    intent.expectedBusinessRevision.toString(),
                    request.proposal
                        ?.id
                        ?.value
                        ?.toString(),
                    intent.expectedDepositRequirementRevision?.toString(),
                    request.invoiceAmendmentHistory
                        .lastOrNull()
                        ?.id
                        ?.toString(),
                    request.effectiveServicePlan?.revision?.toString(),
                    request.inquiry.customer.id.value
                        .toString(),
                    request.inquiry.customer.email.value,
                    capability?.publicId?.toString(),
                    capability?.tokenHash,
                    connection.base.toASCIIString(),
                    financialEmailDigest(listOf(connection.credential)),
                    connection.fromAddress,
                    connection.fromName,
                    connection.replyToAddress,
                    email.html,
                    email.text,
                ) + facts,
            )
        return FinancialEmailPreview(intent, request, summary, email, token)
    }

    private fun providerRequest(
        provider: ConversationSender,
        command: SendFinancialEmail,
        preview: FinancialEmailPreview,
        signature: String,
        conversation: UUID?,
        url: String,
        id: FinancialEmailId,
    ): ProviderSendRequest {
        val email = renderFinancialEmail(command.intent.kind, preview.request, preview.summary, command.intent.message, signature, url)
        val customer = preview.request.inquiry.customer
        return conversationSendRequest(
            provider.configuration,
            "fionas-commerce:financial-email:${id.value}:v1",
            command.intent.inquiryId,
            conversation,
            customer.email,
            customer.name.value.filterNot { it.isISOControl() },
            email.subject,
            email.text,
            email.html,
        )
    }

    private fun provider(): ConversationSender {
        val provider = sender ?: emailRefused("email_sending_disabled", "Email sending is not enabled")
        if (!provider.configuration.financialLinksUntracked) {
            emailRefused(
                "financial_email_tracking_not_verified",
                "Financial email requires verified disabled link tracking",
            )
        }
        return provider
    }

    private fun lookup(
        provider: ConversationSender,
        inquiry: InquiryId,
    ): TopicLookup =
        when (val lookup = provider.lookup(inquiry)) {
            TopicLookup.Unavailable, TopicLookup.Forbidden ->
                emailRefused(
                    "email_provider_unavailable",
                    "The email provider could not be reached",
                )
            else -> lookup
        }

    private fun validateRecipient(
        provider: ConversationSender,
        preview: FinancialEmailPreview,
        lookup: TopicLookup,
    ) {
        val recipient = preview.request.inquiry.customer.email
        if (!provider.configuration.permits(
                recipient,
            )
        ) {
            emailRefused("email_recipient_not_allowed", "This customer's address is not on the outgoing email allow-list")
        }
        if (lookup is TopicLookup.Found && lookup.participantAddress?.lowercase() != recipient.value) {
            emailRefused("financial_email_recipient_mismatch", "The provider conversation participant does not match the inquiry customer")
        }
    }

    private fun signature(id: UserId) = checkNotNull(users(id)) { "Authenticated staff user is missing" }.displayName.trim()

    private fun lock(
        transaction: Transaction,
        inquiry: InquiryId,
    ) {
        val document = associations.initialEstimateOf(transaction, inquiry) ?: emailRefused("not_found", "Inquiry was not found")
        check(associations.lockInquiryOf(transaction, document) == inquiry)
        ledger
            .paymentHistoriesForLineage(
                transaction,
                document,
            ).map { it.payment.id }
            .sorted()
            .forEach { ledger.reconcilePayment(transaction, it) }
    }

    private fun result(
        record: FinancialEmailRecord,
        replayed: Boolean,
    ) = FinancialEmailResult(record.id, record.outcome, replayed, record.conversationId, record.messageId, record.sendState)

    private fun requireReviewed(
        preview: FinancialEmailPreview,
        token: String,
    ) {
        if (preview.reviewToken != token) stale()
    }

    private fun stale(): Nothing = emailRefused("financial_email_review_stale", "Financial email changed since review; preview it again")

    private fun changed(): Nothing = emailRefused("conversation_changed", "The conversation has changed; review it before sending")

    private fun ineligible(): Nothing =
        emailRefused("financial_email_ineligible", "Quote emails require QUOTED; Invoice emails require SERVED with a collectible balance")

    private fun recoveryRequired(): Nothing =
        emailRefused(
            "financial_email_reconciliation_required",
            "The original provider request cannot be reconstructed; reconcile explicitly without sending a replacement",
        )

    private fun rejected(): Nothing = emailRefused("email_send_rejected", "The email provider did not accept this financial email")
}
