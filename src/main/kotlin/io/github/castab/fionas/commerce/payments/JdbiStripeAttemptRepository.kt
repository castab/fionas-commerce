package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class JdbiStripeAttemptRepository {
    fun find(
        transaction: Transaction,
        id: UUID,
    ): StripeAttempt? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.stripe_payment_attempts WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    fun forSession(
        transaction: Transaction,
        session: String,
    ): StripeAttempt? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.stripe_payment_attempts WHERE session_id = :id")
            .bind("id", session)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    fun forKey(
        transaction: Transaction,
        inquiry: InquiryId,
        key: String,
    ): StripeAttempt? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.stripe_payment_attempts WHERE inquiry_id = :inquiry AND request_key = :key")
            .bind("inquiry", inquiry.value)
            .bind("key", key)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    fun history(
        transaction: Transaction,
        inquiry: InquiryId,
    ): List<StripeAttempt> =
        transaction.handle
            .createQuery("SELECT * FROM fionas.stripe_payment_attempts WHERE inquiry_id = :id ORDER BY created_at, id")
            .bind("id", inquiry.value)
            .map { row, _ -> restore(row) }
            .list()

    /** Authorization/recovery decisions call this under the canonical association lock. */
    fun active(
        transaction: Transaction,
        inquiry: InquiryId,
    ): StripeAttempt? =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.stripe_payment_attempts WHERE inquiry_id = :id " +
                    "AND provider_state IN ('CREATING', 'OPEN', 'PROCESSING')",
            ).bind("id", inquiry.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    fun insert(
        transaction: Transaction,
        attempt: StripeAttempt,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.stripe_payment_attempts
                    (id, inquiry_id, public_id, request_key, request_fingerprint, document_id, document_version,
                     purpose, business_revision, proposal_id, deposit_revision, authorized_minor, currency,
                     stripe_account, live_mode, provider_key, created_at, expires_at, provider_state, outcome, checkout_mode,
                     checkout_branding_profile)
                VALUES (:id, :inquiry, :public, :key, :fingerprint, :document, :version, :purpose, :business,
                        :proposal, :deposit, :amount, :currency, :account, :live, :providerKey, :created, :expires,
                        'CREATING', 'PENDING', :checkoutMode, :brandingProfile)
                """.trimIndent(),
            ).bind("id", attempt.id)
            .bind("inquiry", attempt.inquiryId.value)
            .bind("public", attempt.publicId)
            .bind("key", attempt.requestKey)
            .bind("fingerprint", attempt.requestFingerprint)
            .bind("document", attempt.documentId)
            .bind("version", attempt.documentVersion)
            .bind("purpose", attempt.purpose.name)
            .bind("business", attempt.businessRevision)
            .bind("proposal", attempt.proposalId)
            .bind("deposit", attempt.depositRevision)
            .bind("amount", attempt.authorizedMinor)
            .bind("currency", attempt.currency)
            .bind("account", attempt.stripeAccount)
            .bind("live", attempt.liveMode)
            .bind("providerKey", attempt.providerKey)
            .bind("created", attempt.createdAt)
            .bind("expires", attempt.expiresAt)
            .bind("checkoutMode", attempt.checkoutMode.name)
            .bind("brandingProfile", attempt.brandingProfile.name)
            .execute()
    }

    /** Caller holds the association lock. Provider identities can only be added, never reassigned. */
    fun save(
        transaction: Transaction,
        attempt: StripeAttempt,
    ) {
        val changed =
            transaction.handle
                .createUpdate(
                    """
                    UPDATE fionas.stripe_payment_attempts SET session_id = :session, payment_intent_id = :intent,
                        provider_state = :state, outcome = :outcome, ledger_payment_id = CAST(:payment AS uuid), diagnostic = :diagnostic
                    WHERE id = :id AND (session_id IS NULL OR session_id = :session)
                        AND (payment_intent_id IS NULL OR payment_intent_id = :intent)
                        AND (ledger_payment_id IS NULL OR ledger_payment_id = CAST(:payment AS uuid))
                    """.trimIndent(),
                ).bind("id", attempt.id)
                .bind("session", attempt.sessionId)
                .bind("intent", attempt.paymentIntentId)
                .bind("state", attempt.state.name)
                .bind("outcome", attempt.outcome.name)
                .bindByType("payment", attempt.ledgerPaymentId, UUID::class.java)
                .bind("diagnostic", attempt.diagnostic)
                .execute()
        check(changed == 1) { "Stripe attempt identity cannot be reassigned" }
    }

    fun hasEvent(
        transaction: Transaction,
        eventId: String,
    ): Boolean =
        transaction.handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM fionas.stripe_webhook_events WHERE event_id = :id)")
            .bind("id", eventId)
            .mapTo(Boolean::class.javaObjectType)
            .one()

    fun event(
        transaction: Transaction,
        signal: StripeSignal,
        attemptId: UUID?,
        at: Instant,
        disposition: String,
    ) {
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.stripe_webhook_events (event_id, event_type, object_id, payment_intent_id, attempt_id, received_at, disposition)
                VALUES (:id, :type, :object, :intent, CAST(:attempt AS uuid), :at, :disposition) ON CONFLICT (event_id) DO NOTHING
                """.trimIndent(),
            ).bind("id", signal.id)
            .bind("type", signal.type)
            .bind("object", signal.objectId)
            .bind("intent", signal.paymentIntentId)
            .bind("attempt", attemptId)
            .bind("at", at)
            .bind("disposition", disposition)
            .execute()
    }

    private fun restore(row: ResultSet) =
        StripeAttempt(
            row.getObject("id", UUID::class.java),
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            row.getObject("public_id", UUID::class.java),
            row.getString("request_key"),
            row.getString("request_fingerprint"),
            row.getObject("document_id", UUID::class.java),
            row.getInt("document_version"),
            PaymentPurpose.valueOf(row.getString("purpose")),
            row.getInt("business_revision"),
            row.getObject("proposal_id", UUID::class.java),
            row.getObject("deposit_revision", Int::class.javaObjectType),
            row.getLong("authorized_minor"),
            row.getString("currency"),
            row.getString("stripe_account"),
            row.getBoolean("live_mode"),
            row.getString("provider_key"),
            row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            row.getString("session_id"),
            row.getString("payment_intent_id"),
            StripeAttemptState.valueOf(row.getString("provider_state")),
            StripeReconciliation.valueOf(row.getString("outcome")),
            row.getObject("ledger_payment_id", UUID::class.java),
            row.getString("diagnostic"),
            CheckoutMode.valueOf(row.getString("checkout_mode")),
            CheckoutBrandingProfile.valueOf(row.getString("checkout_branding_profile")),
        )
}
