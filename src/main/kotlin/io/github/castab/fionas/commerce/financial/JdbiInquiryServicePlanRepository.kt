package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [InquiryServicePlanRepository] on `fionas.inquiry_service_plans`: one immutable row per exact
 * Quote snapshot, whose `plan` jsonb holds the content in Fiona's persisted representation.
 */
class JdbiInquiryServicePlanRepository : InquiryServicePlanRepository {
    override fun insert(
        transaction: Transaction,
        plan: InquiryServicePlan,
    ) {
        // Only the canonical lineage has an approved plan; the caller already holds this lock.
        transaction.handle
            .createQuery(
                "SELECT document_id FROM fionas.inquiry_financial_documents " +
                    "WHERE inquiry_id = :inquiry AND document_id = :document AND purpose = 'INITIAL_ESTIMATE' FOR UPDATE",
            ).bind("inquiry", plan.inquiryId.value)
            .bind("document", plan.quote.id)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseThrow { CommerceFailure.NotFound("Canonical inquiry financial lineage was not found") }
        val principal = plan.principalId
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_service_plans
                        (document_id, document_version, inquiry_id, reviewed_document_version, pricing_basis, catalog_revision,
                         plan, approved_at, principal_kind, principal_id)
                    VALUES (:document, :version, :inquiry, :reviewed, :basis, :revision,
                            CAST(:plan AS jsonb), :at, :principalKind, :principal)
                    """.trimIndent(),
                ).bind("document", plan.quote.id)
                .bind("version", plan.quote.version.number)
                .bind("inquiry", plan.inquiryId.value)
                .bind("reviewed", plan.reviewedEstimate.version.number)
                .bind("basis", plan.basis.name)
                .bind("revision", plan.catalogRevision.number)
                .bind("plan", encodeServicePlanContent(plan))
                .bind("at", plan.approvedAt)
                .bind(
                    "principalKind",
                    when (principal) {
                        is UserId -> "USER"
                        is ServiceId -> "SERVICE"
                    },
                ).bind(
                    "principal",
                    when (principal) {
                        is UserId -> principal.value
                        is ServiceId -> principal.value
                    },
                ).execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("The Quote already has an approved service plan", failure)
            throw failure
        }
    }

    override fun find(
        transaction: Transaction,
        quote: FinancialDocumentReference,
    ): InquiryServicePlan? =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.inquiry_service_plans WHERE document_id = :document AND document_version = :version",
            ).bind("document", quote.id)
            .bind("version", quote.version.number)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    private fun restore(row: ResultSet): InquiryServicePlan {
        val documentId = row.getObject("document_id", UUID::class.java)
        val quote = FinancialDocumentReference(documentId, Version.of(row.getInt("document_version")))
        val owner = "the service plan of $documentId at ${quote.version}"
        val content = restoreServicePlanContent(owner, row.getString("plan"))
        val principalId = row.getObject("principal_id", UUID::class.java)
        return try {
            InquiryServicePlan(
                inquiryId = InquiryId(row.getObject("inquiry_id", UUID::class.java)),
                quote = quote,
                reviewedEstimate = FinancialDocumentReference(documentId, Version.of(row.getInt("reviewed_document_version"))),
                basis = QuotePricingBasis.valueOf(row.getString("pricing_basis")),
                catalogRevision = OfferingsRevision.of(row.getInt("catalog_revision")),
                context = content.context,
                selections = content.selections,
                lines = content.lines,
                approvedAt = row.getObject("approved_at", OffsetDateTime::class.java).toInstant(),
                principalId =
                    when (row.getString("principal_kind")) {
                        "USER" -> UserId(principalId)
                        "SERVICE" -> ServiceId(principalId)
                        else -> error("Invalid service plan principal kind")
                    },
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed $owner: ${e.message}", e)
        }
    }
}
