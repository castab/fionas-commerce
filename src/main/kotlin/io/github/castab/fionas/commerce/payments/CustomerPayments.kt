package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.InquiryTimelineRevisionRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.staff.ReadStaffRequest
import io.github.castab.fionas.commerce.staff.StaffRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

enum class PaymentAccessAction { ISSUE, ROTATE, REVOKE }

class CheckoutCommand(
    val publicId: UUID,
    val bearer: PaymentBearer,
    val key: String,
    val documentVersion: Int,
    val businessRevision: Int,
    val amountMinor: Long?,
) {
    override fun toString() = "CheckoutCommand([redacted])"
}

class CheckoutRedirect(
    val attemptId: UUID,
    val url: String?,
    val state: StripeAttemptState,
    val outcome: StripeReconciliation,
) {
    override fun toString() = "CheckoutRedirect([redacted])"
}

/** Fiona integration orchestration over one runtime pool, supported ledger operations and association locking. */
class CustomerPayments(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val requests: ReadStaffRequest,
    private val recordPayment: RecordDocumentPayment,
    private val gateway: StripeGateway,
    private val configuration: StripeConfiguration,
    private val clock: Clock,
    private val access: JdbiPaymentAccessRepository = JdbiPaymentAccessRepository(),
    private val attempts: JdbiStripeAttemptRepository = JdbiStripeAttemptRepository(),
) {
    private val secrets = PaymentAccessSecrets(configuration)
    private val logger = KotlinLogging.logger {}

    fun staffAccess(id: InquiryId): StaffPaymentAccess =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            requests.read(transaction, id)
            secrets.describe(access.forInquiry(transaction, id))
        }

    fun manageAccess(
        id: InquiryId,
        action: PaymentAccessAction,
        actor: UserId,
    ): StaffPaymentAccess =
        transactor.inTransaction { transaction ->
            lock(transaction, id)
            val request = requests.read(transaction, id)
            val existing = access.forInquiry(transaction, id)
            if (action == PaymentAccessAction.REVOKE) {
                existing?.let { access.save(transaction, it.copy(revoked = true)) }
                return@inTransaction secrets.describe(existing?.copy(revoked = true))
            }
            if (action == PaymentAccessAction.ISSUE &&
                existing != null &&
                !existing.revoked
            ) {
                return@inTransaction secrets.describe(existing)
            }
            if (request.inquiry.lifecycle.stage !in setOf(InquiryStage.QUOTED, InquiryStage.BOOKED, InquiryStage.SERVED)) {
                throw CommerceFailure.IllegalTransition("Payment access can be issued only for published, active inquiries")
            }
            val publicId = existing?.publicId ?: UUID.randomUUID()
            val generation = secrets.generation()
            val next = PaymentAccess(id, publicId, generation, secrets.hash(secrets.bearer(publicId, generation)), false, now(), actor)
            access.save(transaction, next)
            secrets.describe(next)
        }

    fun summary(
        id: UUID,
        bearer: PaymentBearer,
    ): CustomerPaymentSummary =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val capability = authorized(transaction, id, bearer)
            customerPaymentSummary(requests.read(transaction, capability.inquiryId), collectionAttempts(transaction, capability.inquiryId))
        }

    fun checkout(command: CheckoutCommand): CheckoutRedirect {
        val fingerprint = "v1:${command.documentVersion}:${command.businessRevision}:${command.amountMinor ?: "BALANCE"}"
        val attempt =
            transactor.inTransaction { transaction ->
                val initial = authorized(transaction, command.publicId, command.bearer)
                lock(transaction, initial.inquiryId)
                // Recheck after the same lock used by revocation/rotation, including a waiting request.
                val capability = authorized(transaction, command.publicId, command.bearer)
                val existing = attempts.forKey(transaction, capability.inquiryId, command.key)
                if (existing != null) {
                    if (existing.requestFingerprint != fingerprint) throw CommerceFailure.Conflict("CHECKOUT_KEY_REUSED")
                    return@inTransaction existing
                }
                val request = requests.read(transaction, capability.inquiryId)
                val summary = customerPaymentSummary(request, collectionAttempts(transaction, capability.inquiryId))
                if (!summary.canPay) throw CommerceFailure.Conflict(summary.unavailableReason ?: "PAYMENT_UNAVAILABLE")
                if (summary.documentVersion != command.documentVersion || summary.businessRevision != command.businessRevision) {
                    throw CommerceFailure.Conflict("PAYMENT_TERMS_CHANGED")
                }
                val selected = command.amountMinor ?: stripeMinor(summary.required)
                val selectedAmount = BigDecimal.valueOf(selected, 2)
                if (summary.purpose == PaymentPurpose.DEPOSIT && selectedAmount.compareTo(summary.required) != 0) {
                    throw CommerceFailure.ValidationFailed("The complete approved deposit is required")
                }
                if (selected <= 0 ||
                    selectedAmount > summary.required
                ) {
                    throw CommerceFailure.ValidationFailed("Payment exceeds the positive outstanding amount")
                }
                stripeMinor(selectedAmount)
                val id = UUID.randomUUID()
                val at = now()
                StripeAttempt(
                    id,
                    capability.inquiryId,
                    capability.publicId,
                    command.key,
                    fingerprint,
                    summary.documentId,
                    summary.documentVersion,
                    summary.purpose,
                    summary.businessRevision,
                    request.proposal?.id?.value,
                    (request.deposit.depositRequirement?.requirement as? DepositRequirement.Active)?.revision?.number,
                    selected,
                    "USD",
                    configuration.accountId,
                    configuration.liveMode,
                    "fionas-checkout:$id",
                    at,
                    at.plusSeconds(3600),
                ).also { attempts.insert(transaction, it) }
            }
        return synchronize(attempt)
    }

    /** Staff recovery also works after public access revocation and after a process restart. */
    fun recover(
        inquiry: InquiryId,
        attemptId: UUID,
    ): CheckoutRedirect {
        val attempt =
            transactor.inTransaction { transaction ->
                attempts.find(transaction, attemptId)?.takeIf { it.inquiryId == inquiry }
                    ?: throw CommerceFailure.NotFound("Payment attempt was not found")
            }
        return synchronize(attempt)
    }

    fun staffAttempts(inquiry: InquiryId): List<StripeAttempt> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            requests.read(transaction, inquiry)
            attempts.history(transaction, inquiry)
        }

    private fun synchronize(attempt: StripeAttempt): CheckoutRedirect {
        if (attempt.ledgerPaymentId != null) return CheckoutRedirect(attempt.id, null, attempt.state, attempt.outcome)
        var session =
            when {
                attempt.sessionId != null -> gateway.retrieve(attempt.sessionId)
                // Repeating the durable provider key is safe only inside its retention period, with identical parameters.
                now().isBefore(attempt.createdAt.plusSeconds(1500)) -> gateway.create(attempt)
                else -> gateway.discover(attempt) ?: throw CommerceFailure.Conflict("CHECKOUT_CREATION_UNKNOWN_REQUIRES_RECONCILIATION")
            }
        verifySession(attempt, session)
        if (session.state == "open") {
            val obsolete =
                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    val request = requests.read(transaction, attempt.inquiryId)
                    !termsMatch(attempt, request, customerPaymentSummary(request, emptyList())) ||
                        access.forInquiry(transaction, attempt.inquiryId)?.revoked == true
                }
            if (obsolete) session = gateway.expire(session.id)
        }
        val saved = reconcile(session)
        return CheckoutRedirect(saved.id, session.url?.takeIf { saved.state == StripeAttemptState.OPEN }, saved.state, saved.outcome)
    }

    /** Signature authentication is independent of staff sessions. No acknowledgement precedes a committed outcome. */
    fun webhook(
        rawBody: String,
        signature: String,
    ) {
        val signal = gateway.verify(rawBody, signature)
        if (signal.liveMode != configuration.liveMode || signal.account != null && signal.account != configuration.accountId) {
            throw CommerceFailure.ValidationFailed("Stripe event environment does not match this deployment")
        }
        if (transactor.inTransaction { attempts.hasEvent(it, signal.id) }) return
        if (signal.type.startsWith("checkout.session.")) {
            if (signal.type !in checkoutSignals) {
                transactor.inTransaction { attempts.event(it, signal, null, now(), "IGNORED") }
                return
            }
            // Provider retrieval authenticates both current payment status and server-created session identity.
            reconcile(gateway.retrieve(signal.objectId), signal)
        } else {
            val adjustment = signal.type in adjustmentSignals
            transactor.inTransaction { transaction ->
                attempts.event(transaction, signal, null, now(), if (adjustment) "EXTERNAL_ADJUSTMENT" else "IGNORED")
            }
            if (adjustment) {
                logger.error {
                    "Stripe external adjustment requires staff reconciliation: event=${signal.id}, object=${signal.objectId}"
                }
            }
        }
    }

    private fun reconcile(
        session: StripeCheckout,
        signal: StripeSignal? = null,
    ): StripeAttempt {
        val identity =
            transactor.inTransaction { transaction ->
                attempts.forSession(transaction, session.id)
                    ?: attempts.find(transaction, session.attemptId)
            }
                ?: throw CommerceFailure.NotFound("Stripe session has no server-created payment attempt")
        return transactor.inTransaction { transaction ->
            lock(transaction, identity.inquiryId)
            val attempt = checkNotNull(attempts.find(transaction, identity.id))
            verifySession(attempt, session)
            if (attempt.ledgerPaymentId != null) {
                signal?.let { attempts.event(transaction, it, attempt.id, now(), "PROCESSED") }
                return@inTransaction attempt
            }
            val linked = attempt.copy(sessionId = session.id, paymentIntentId = session.paymentIntentId ?: attempt.paymentIntentId)
            val result =
                if (session.paymentStatus == "paid" && session.state == "complete") {
                    checkNotNull(session.paymentIntentId) { "Paid Stripe session has no PaymentIntent" }
                    val request = requests.read(transaction, attempt.inquiryId)
                    val summary = customerPaymentSummary(request, emptyList())
                    val applicable = termsMatch(attempt, request, summary)
                    val amount = BigDecimal.valueOf(attempt.authorizedMinor, 2)
                    val reference = ExternalPaymentReference("stripe", "${attempt.stripeAccount}:${session.paymentIntentId}")
                    val received = session.paidAt ?: now()
                    val paymentId =
                        if (applicable) {
                            recordPayment
                                .record(
                                    transaction,
                                    RecordDocumentPayment.Command(
                                        attempt.documentId,
                                        Version.of(attempt.documentVersion),
                                        amount,
                                        PaymentMethod.CARD,
                                        received,
                                        reference,
                                        attempt.proposalId?.let(::InquiryProposalId),
                                    ),
                                    now(),
                                ).payment.id
                        } else {
                            val payment =
                                PaymentRecord(
                                    UUID.randomUUID(),
                                    Money(amount, Currency.getInstance("USD")),
                                    PaymentMethod.CARD,
                                    received,
                                    reference,
                                )
                            ledger.recordPayment(transaction, payment)
                            InquiryTimelineRevisionRepository().advance(transaction, setOf(attempt.inquiryId))
                            logger.error { "Stripe captured payment requires staff reconciliation: attempt=${attempt.id}" }
                            payment.id
                        }
                    linked.copy(
                        state = StripeAttemptState.SUCCEEDED,
                        outcome = if (applicable) StripeReconciliation.APPLIED else StripeReconciliation.RECONCILIATION_REQUIRED,
                        ledgerPaymentId = paymentId,
                        diagnostic = if (applicable) null else "AUTHORIZED_TERMS_NO_LONGER_PAYABLE",
                    )
                } else {
                    val state =
                        when {
                            session.state == "expired" -> StripeAttemptState.EXPIRED
                            signal?.type == "checkout.session.async_payment_failed" -> StripeAttemptState.FAILED
                            session.state == "complete" -> StripeAttemptState.PROCESSING
                            else -> StripeAttemptState.OPEN
                        }
                    // An old notification cannot reopen a terminal session or undo a receipt.
                    linked.copy(
                        state =
                            if (attempt.state in
                                setOf(StripeAttemptState.EXPIRED, StripeAttemptState.FAILED)
                            ) {
                                attempt.state
                            } else {
                                state
                            },
                    )
                }
            attempts.save(transaction, result)
            signal?.let { attempts.event(transaction, it, attempt.id, now(), "PROCESSED") }
            result
        }
    }

    private fun verifySession(
        attempt: StripeAttempt,
        session: StripeCheckout,
    ) {
        check(
            session.attemptId == attempt.id &&
                session.amountMinor == attempt.authorizedMinor &&
                session.currency == "usd" &&
                session.liveMode == attempt.liveMode &&
                attempt.liveMode == configuration.liveMode &&
                attempt.stripeAccount == configuration.accountId &&
                java.security.MessageDigest.isEqual(
                    session.authorizationProof.toByteArray(),
                    configuration.authorizationProof(attempt).toByteArray(),
                ) &&
                (attempt.sessionId == null || session.id == attempt.sessionId) &&
                (attempt.paymentIntentId == null || session.paymentIntentId == attempt.paymentIntentId) &&
                session.expiresAt == attempt.expiresAt,
        ) { "Stripe session disagrees with the durable authorization" }
    }

    private fun termsMatch(
        attempt: StripeAttempt,
        request: StaffRequest,
        summary: CustomerPaymentSummary,
    ): Boolean {
        if (!summary.canPay ||
            summary.documentId != attempt.documentId ||
            summary.documentVersion != attempt.documentVersion ||
            summary.businessRevision != attempt.businessRevision ||
            summary.purpose != attempt.purpose
        ) {
            return false
        }
        val deposit = request.deposit.depositRequirement?.requirement as? DepositRequirement.Active
        return if (attempt.purpose == PaymentPurpose.DEPOSIT) {
            request.proposal?.id?.value == attempt.proposalId &&
                deposit?.revision?.number == attempt.depositRevision &&
                stripeMinor(summary.required) == attempt.authorizedMinor &&
                request.deposit.reconciliation.grossAllocated.amount
                    .signum() == 0
        } else {
            BigDecimal.valueOf(attempt.authorizedMinor, 2) <= summary.balance
        }
    }

    /** The historical provider exception remains; only runtime-kept unapplied money blocks new collection. */
    private fun collectionAttempts(
        transaction: Transaction,
        inquiry: InquiryId,
    ): List<StripeAttempt> =
        attempts.history(transaction, inquiry).filter {
            it.outcome != StripeReconciliation.RECONCILIATION_REQUIRED ||
                ledger
                    .paymentHistory(transaction, checkNotNull(it.ledgerPaymentId))
                    .reconciliation.unallocated.amount
                    .signum() > 0
        }

    private fun authorized(
        transaction: Transaction,
        id: UUID,
        bearer: PaymentBearer,
    ): PaymentAccess =
        access.forPublicId(transaction, id)?.takeIf { secrets.matches(it, bearer) }
            ?: throw CommerceFailure.NotFound("Payment access was not found")

    private fun lock(
        transaction: Transaction,
        inquiry: InquiryId,
    ) {
        val document = associations.initialEstimateOf(transaction, inquiry) ?: throw CommerceFailure.NotFound("Inquiry was not found")
        check(associations.lockInquiryOf(transaction, document) == inquiry)
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.SECONDS)
}

internal val checkoutSignals =
    setOf(
        "checkout.session.completed",
        "checkout.session.async_payment_succeeded",
        "checkout.session.async_payment_failed",
        "checkout.session.expired",
    )
internal val adjustmentSignals =
    setOf(
        "charge.refunded",
        "refund.created",
        "refund.updated",
        "refund.failed",
        "charge.dispute.created",
        "charge.dispute.updated",
        "charge.dispute.closed",
        "charge.dispute.funds_withdrawn",
        "charge.dispute.funds_reinstated",
    )
