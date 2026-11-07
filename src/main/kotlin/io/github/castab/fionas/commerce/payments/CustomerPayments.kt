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
import java.security.MessageDigest
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
    val restartToken: String? = null,
) {
    override fun toString() = "CheckoutCommand([redacted])"
}

class CheckoutRedirect(
    val attemptId: UUID,
    val url: String?,
    val state: StripeAttemptState,
    val outcome: StripeReconciliation,
    val uiMode: CheckoutMode = CheckoutMode.HOSTED,
    val clientSecret: String? = null,
    val replacedAttemptId: UUID? = null,
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
            manageAccess(transaction, id, action, actor)
        }

    /** Caller holds the canonical association lock; used to atomically issue access with an email intent. */
    internal fun manageAccess(
        transaction: Transaction,
        id: InquiryId,
        action: PaymentAccessAction,
        actor: UserId,
    ): StaffPaymentAccess {
        val request = requests.read(transaction, id)
        val existing = access.forInquiry(transaction, id)
        if (action == PaymentAccessAction.REVOKE) {
            existing?.let { access.save(transaction, it.copy(revoked = true)) }
            return secrets.describe(existing?.copy(revoked = true))
        }
        if (action == PaymentAccessAction.ISSUE &&
            existing != null &&
            !existing.revoked
        ) {
            return secrets.describe(existing)
        }
        if (request.inquiry.lifecycle.stage !in setOf(InquiryStage.QUOTED, InquiryStage.BOOKED, InquiryStage.SERVED)) {
            throw CommerceFailure.IllegalTransition("Payment access can be issued only for published, active inquiries")
        }
        val publicId = existing?.publicId ?: UUID.randomUUID()
        val generation = secrets.generation()
        val next = PaymentAccess(id, publicId, generation, secrets.hash(secrets.bearer(publicId, generation)), false, now(), actor)
        access.save(transaction, next)
        return secrets.describe(next)
    }

    internal fun collectionSummary(
        transaction: Transaction,
        request: StaffRequest,
    ): CustomerPaymentSummary =
        customerPaymentSummary(request, collectionAttempts(transaction, attempts.history(transaction, request.inquiry.inquiry.id)))

    fun summary(
        id: UUID,
        bearer: PaymentBearer,
    ): CustomerPaymentSummary =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val capability = authorized(transaction, id, bearer)
            val history = attempts.history(transaction, capability.inquiryId)
            val request = requests.read(transaction, capability.inquiryId)
            val summary = customerPaymentSummary(request, collectionAttempts(transaction, history))
            val target = history.singleOrNull { it.state == StripeAttemptState.OPEN || it.state == StripeAttemptState.CREATING }
            if (summary.checkoutRecoveryAvailable && target != null) {
                val fence = secrets.restartTarget(capability, target.id, history.size)
                val review = secrets.restartReview(fence, checkoutTerms(request, summary).review())
                summary.copy(checkoutRestartToken = "$fence.$review")
            } else {
                summary
            }
        }

    fun checkout(command: CheckoutCommand): CheckoutRedirect {
        val ordinaryFingerprint = "v1:${command.documentVersion}:${command.businessRevision}:${command.amountMinor ?: "BALANCE"}"
        val fingerprint = command.restartToken?.let { "restart:$it:$ordinaryFingerprint" } ?: ordinaryFingerprint
        // Outside PostgreSQL and before any authorization: a merchant mismatch must not leave an active attempt.
        if (command.restartToken == null) verifyMerchant()
        val selected = selectCheckout(command, fingerprint)
        if (command.restartToken != null) observeStripe { verifyMerchant() }
        val attempt =
            if (selected.recoverActive) {
                // One observed attempt per command. A competing replacement is never followed in a retry loop.
                if (command.restartToken == null) {
                    synchronizeCustomer(selected.attempt)
                } else {
                    confirmRestartExpiry(selected.attempt) { selectCheckout(command, fingerprint).recoverActive }
                }
                selectCheckout(command, fingerprint, selected).attempt
            } else {
                selected.attempt
            }
        return synchronizeCustomer(attempt, command.restartToken?.let(::restartTarget))
    }

    private fun synchronizeCustomer(
        attempt: StripeAttempt,
        replacedAttemptId: UUID? = null,
    ): CheckoutRedirect =
        try {
            synchronize(attempt, replacedAttemptId = replacedAttemptId)
        } catch (failure: CommerceFailure.Conflict) {
            if (failure.message in
                setOf("CHECKOUT_CREATION_UNKNOWN_REQUIRES_RECONCILIATION", "STRIPE_DISCOVERY_LIMIT_REQUIRES_RECONCILIATION")
            ) {
                throw CheckoutRecoveryProblem.UNKNOWN.conflict()
            }
            throw failure
        }

    private data class CheckoutSelection(
        val attempt: StripeAttempt,
        val recoverActive: Boolean,
        val observedAttempts: Set<UUID> = emptySet(),
    )

    private data class CheckoutTerms(
        val summary: CustomerPaymentSummary,
        val proposalId: UUID?,
        val depositRevision: Int?,
    ) {
        // Values are fixed-format identities, enums, decimals and receipt timestamps. Exclude attempt state.
        fun review(): String =
            listOf(
                summary.documentId,
                summary.documentVersion,
                summary.businessRevision,
                summary.lifecycle,
                summary.currency,
                summary.purpose,
                summary.required,
                summary.paid,
                summary.balance,
                proposalId,
                depositRevision,
                summary.history.joinToString(",") { "${it.amount}/${it.refunded}/${it.receivedAt}" },
            ).joinToString(":")
    }

    private fun checkoutTerms(
        request: StaffRequest,
        summary: CustomerPaymentSummary,
    ) = CheckoutTerms(
        summary,
        request.proposal?.id?.value,
        (request.deposit.depositRequirement?.requirement as? DepositRequirement.Active)?.revision?.number,
    )

    private fun selectCheckout(
        command: CheckoutCommand,
        fingerprint: String,
        recovered: CheckoutSelection? = null,
    ): CheckoutSelection =
        transactor.inTransaction { transaction ->
            val initial = authorized(transaction, command.publicId, command.bearer)
            lock(transaction, initial.inquiryId)
            // Recheck after the same lock used by revocation/rotation, including a waiting request.
            val capability = authorized(transaction, command.publicId, command.bearer)
            val existing = attempts.forKey(transaction, capability.inquiryId, command.key)
            if (existing != null) {
                if (existing.requestFingerprint != fingerprint) {
                    if (command.restartToken != null || existing.requestFingerprint.startsWith("restart:")) {
                        throw CheckoutRecoveryProblem.KEY_REUSED.conflict()
                    }
                    throw CommerceFailure.Conflict("CHECKOUT_KEY_REUSED")
                }
                return@inTransaction CheckoutSelection(existing, false)
            }
            val active = attempts.active(transaction, capability.inquiryId)
            val history = attempts.history(transaction, capability.inquiryId)
            val restartToken = command.restartToken
            val target =
                if (restartToken != null) {
                    val target = history.singleOrNull { it.id == restartTarget(restartToken) }
                    if (target == null ||
                        (active != null && active.id != target.id) ||
                        !MessageDigest.isEqual(
                            restartToken.substringBeforeLast('.').toByteArray(),
                            secrets.restartTarget(capability, target.id, history.size).toByteArray(),
                        )
                    ) {
                        throw CheckoutRecoveryProblem.COMPETING.conflict()
                    }
                    if (target.ledgerPaymentId != null) throw CheckoutRecoveryProblem.CHANGED.conflict()
                    target
                } else {
                    if (recovered != null && history.any { it.id !in recovered.observedAttempts }) {
                        throw CheckoutRecoveryProblem.COMPETING.conflict()
                    }
                    null
                }
            if (active != null && target == null) {
                if (recovered == null) {
                    return@inTransaction CheckoutSelection(active, true, history.map { it.id }.toSet())
                }
                throw if (active.state == StripeAttemptState.PROCESSING) {
                    CheckoutRecoveryProblem.PROCESSING.conflict()
                } else {
                    CheckoutRecoveryProblem.OPEN.conflict()
                }
            }
            if (recovered != null && target == null) {
                val previous = history.single { it.id == recovered.attempt.id }
                if (previous.ledgerPaymentId != null) throw CheckoutRecoveryProblem.CHANGED.conflict()
                check(previous.state in setOf(StripeAttemptState.EXPIRED, StripeAttemptState.FAILED)) {
                    "Checkout recovery did not establish a terminal outcome"
                }
            }
            val request = requests.read(transaction, capability.inquiryId)
            val summary = customerPaymentSummary(request, collectionAttempts(transaction, history.filterNot { it.id == target?.id }))
            val terms = checkoutTerms(request, summary)
            if (target != null) {
                if (summary.reconciliationPending) throw CheckoutRecoveryProblem.UNKNOWN.conflict()
                checkNotNull(restartToken)
                val review = secrets.restartReview(restartToken.substringBeforeLast('.'), terms.review())
                if (!summary.canPay || !MessageDigest.isEqual(restartToken.substringAfterLast('.').toByteArray(), review.toByteArray())) {
                    throw CheckoutRecoveryProblem.TERMS.conflict()
                }
            } else if (!summary.canPay) {
                throw CommerceFailure.Conflict(summary.unavailableReason ?: "PAYMENT_UNAVAILABLE")
            }
            val selected = validateCheckoutAmount(command, summary)
            if (target != null) {
                if (recovered == null) return@inTransaction CheckoutSelection(target, true)
                if (target.state != StripeAttemptState.EXPIRED) throw CheckoutRecoveryProblem.UNKNOWN.conflict()
            }
            val id = UUID.randomUUID()
            val at = now()
            val attempt =
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
                    terms.proposalId,
                    terms.depositRevision,
                    selected,
                    "USD",
                    configuration.accountId,
                    configuration.liveMode,
                    "fionas-checkout:$id",
                    at,
                    at.plusSeconds(3600),
                    checkoutMode = configuration.checkoutMode,
                    brandingProfile =
                        when (configuration.checkoutMode) {
                            CheckoutMode.HOSTED, CheckoutMode.EMBEDDED -> CheckoutBrandingProfile.FIONAS_V1
                            CheckoutMode.ELEMENTS -> CheckoutBrandingProfile.NONE
                        },
                ).also { attempts.insert(transaction, it) }
            CheckoutSelection(attempt, false)
        }

    private fun restartTarget(token: String): UUID =
        try {
            UUID.fromString(token.substringBefore('.'))
        } catch (_: IllegalArgumentException) {
            throw CommerceFailure.ValidationFailed("Invalid Checkout restart token")
        }

    private fun validateCheckoutAmount(
        command: CheckoutCommand,
        summary: CustomerPaymentSummary,
    ): Long {
        if (summary.documentVersion != command.documentVersion || summary.businessRevision != command.businessRevision) {
            if (command.restartToken != null) throw CheckoutRecoveryProblem.TERMS.conflict()
            throw CommerceFailure.Conflict("PAYMENT_TERMS_CHANGED")
        }
        val selected = command.amountMinor ?: stripeMinor(summary.required)
        val amount = BigDecimal.valueOf(selected, 2)
        if (summary.purpose == PaymentPurpose.DEPOSIT && amount.compareTo(summary.required) != 0) {
            throw CommerceFailure.ValidationFailed("The complete approved deposit is required")
        }
        if (selected <= 0 || amount > summary.required) {
            throw CommerceFailure.ValidationFailed("Payment exceeds the positive outstanding amount")
        }
        return stripeMinor(amount)
    }

    private fun confirmRestartExpiry(
        attempt: StripeAttempt,
        stillRequested: () -> Boolean,
    ) {
        val observed = observeStripe { retrieveSession(attempt).also { verifySession(attempt, it) } }
        val saved = reconcileRestart(observed)
        if (saved.state == StripeAttemptState.EXPIRED && observed.state == "expired") return
        if (saved.state != StripeAttemptState.OPEN || observed.state != "open") throw CheckoutRecoveryProblem.UNKNOWN.conflict()
        if (!stillRequested()) return

        val result =
            observeStripe {
                val response =
                    try {
                        gateway.expire(observed.id).also { verifySession(saved, it) }
                    } catch (mismatch: StripeMerchantMismatch) {
                        throw mismatch
                    } catch (_: RuntimeException) {
                        // Stripe may have expired or completed the session before its response was lost.
                        null
                    }
                (response?.takeUnless { it.state == "open" } ?: gateway.retrieve(observed.id)).also { verifySession(saved, it) }
            }
        val reconciled = reconcileRestart(result)
        if (reconciled.state != StripeAttemptState.EXPIRED || result.state != "expired") throw CheckoutRecoveryProblem.UNKNOWN.conflict()
    }

    private fun reconcileRestart(session: StripeCheckout): StripeAttempt =
        reconcile(session).also {
            when {
                it.ledgerPaymentId != null -> throw CheckoutRecoveryProblem.CHANGED.conflict()
                it.state == StripeAttemptState.PROCESSING -> throw CheckoutRecoveryProblem.PROCESSING.conflict()
                it.state == StripeAttemptState.FAILED -> throw CheckoutRecoveryProblem.COMPETING.conflict()
            }
        }

    private fun <T> observeStripe(observe: () -> T): T =
        try {
            observe()
        } catch (mismatch: StripeMerchantMismatch) {
            throw mismatch
        } catch (_: RuntimeException) {
            // The boundary includes provider I/O and identity verification only. Database failures propagate.
            throw CheckoutRecoveryProblem.UNKNOWN.conflict()
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
        verifyMerchant()
        return synchronize(attempt, customerLaunch = false)
    }

    /** A deployment error refuses payment loudly for operators; it is never reported as an unknown payment outcome. */
    private fun verifyMerchant() =
        try {
            gateway.verifyMerchant()
        } catch (mismatch: StripeMerchantMismatch) {
            logger.error {
                "Stripe merchant mismatch: STRIPE_SECRET_KEY does not belong to FIONAS_STRIPE_ACCOUNT_ID; checkout refused"
            }
            throw mismatch
        }

    fun staffAttempts(inquiry: InquiryId): List<StripeAttempt> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            requests.read(transaction, inquiry)
            attempts.history(transaction, inquiry)
        }

    private fun synchronize(
        attempt: StripeAttempt,
        customerLaunch: Boolean = true,
        replacedAttemptId: UUID? = null,
    ): CheckoutRedirect {
        if (attempt.ledgerPaymentId != null) return attempt.redirect(replacedAttemptId = replacedAttemptId)
        var session = retrieveSession(attempt)
        verifySession(attempt, session)
        if (session.state == "open") {
            val observed =
                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    val latest = checkNotNull(attempts.find(transaction, attempt.id))
                    if (latest.ledgerPaymentId != null) return@inTransaction latest to false
                    val request = requests.read(transaction, attempt.inquiryId)
                    latest to (
                        !termsMatch(attempt, request, customerPaymentSummary(request, emptyList())) ||
                            access.forInquiry(transaction, attempt.inquiryId)?.revoked == true
                    )
                }
            if (observed.first.ledgerPaymentId !=
                null
            ) {
                return observed.first.redirect(replacedAttemptId = replacedAttemptId)
            }
            if (observed.second) session = gateway.expire(session.id)
        }
        val saved = reconcile(session)
        val launch = customerLaunch && saved.state == StripeAttemptState.OPEN
        val url = session.url?.takeIf { launch && saved.checkoutMode == CheckoutMode.HOSTED }
        val secret = session.clientSecret?.takeIf { launch && saved.checkoutMode != CheckoutMode.HOSTED }
        if (launch && (if (saved.checkoutMode == CheckoutMode.HOSTED) url else secret).isNullOrBlank()) {
            // Keep the reconciled Session identity reserved; missing browser credentials never permit replacement.
            throw CheckoutRecoveryProblem.UNKNOWN.conflict()
        }
        return saved.redirect(url, secret, replacedAttemptId)
    }

    private fun StripeAttempt.redirect(
        url: String? = null,
        secret: String? = null,
        replacedAttemptId: UUID? = null,
    ) = CheckoutRedirect(id, url, state, outcome, checkoutMode, secret, replacedAttemptId)

    private fun retrieveSession(attempt: StripeAttempt): StripeCheckout =
        when {
            attempt.sessionId != null -> gateway.retrieve(attempt.sessionId)
            // Repeating the durable provider key is safe only inside its retention period, with identical parameters.
            now().isBefore(attempt.createdAt.plusSeconds(1500)) -> gateway.create(attempt)
            else -> gateway.discover(attempt) ?: throw CommerceFailure.Conflict("CHECKOUT_CREATION_UNKNOWN_REQUIRES_RECONCILIATION")
        }

    /** Signature authentication is independent of staff sessions. No acknowledgement precedes a committed outcome. */
    fun webhook(
        rawBody: String,
        signature: String,
    ) {
        val signal = gateway.verify(rawBody, signature)
        if (signal.liveMode != configuration.liveMode || signal.account != null) {
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
                            } else if (attempt.state == StripeAttemptState.PROCESSING && state == StripeAttemptState.OPEN) {
                                StripeAttemptState.PROCESSING
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
                session.uiMode == attempt.checkoutMode &&
                (
                    (session.state == "complete" && session.paymentStatus == "paid") ||
                        (session.state in setOf("open", "complete", "expired") && session.paymentStatus == "unpaid")
                ) &&
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
                (
                    attempt.paymentIntentId == null ||
                        session.paymentIntentId == null ||
                        session.paymentIntentId == attempt.paymentIntentId
                ) &&
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
        history: List<StripeAttempt>,
    ): List<StripeAttempt> =
        history.filter {
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
