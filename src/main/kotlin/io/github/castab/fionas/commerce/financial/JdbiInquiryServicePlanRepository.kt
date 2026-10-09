package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.StrictIntSerializer
import io.github.castab.fionas.commerce.StrictStringSerializer
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.persistedJson
import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/*
 * Fiona's persisted representation of an approved service plan's content, the `plan` jsonb of
 * `fionas.inquiry_service_plans`; the row's own columns hold its identities and provenance:
 *
 * ```
 * {"description": "Churro catering for an evening reception",
 *  "guestCount": 100, "durationMinutes": 120,
 *  "items": ["Churros with chocolate sauce", "Cinnamon sugar"],
 *  "lineNotes": [{"lineItemId": "…", "note": "Negotiated with the venue"}]}
 * ```
 *
 * It holds no money: each note names its commerce-runtime ledger line by id. Decoded strictly:
 * every property is required, `null` only for `guestCount` and `durationMinutes`, unknown
 * properties and wrong JSON types fail, and the domain types' invariants are checked again.
 */

@Serializable
private class ServicePlanJson(
    @Serializable(with = StrictStringSerializer::class) val description: String,
    @Serializable(with = StrictIntSerializer::class) val guestCount: Int?,
    @Serializable(with = StrictIntSerializer::class) val durationMinutes: Int?,
    val items: List<
        @Serializable(with = StrictStringSerializer::class)
        String,
    >,
    val lineNotes: List<LineNoteJson>,
)

@Serializable
private class LineNoteJson(
    @Serializable(with = StrictStringSerializer::class) val lineItemId: String,
    @Serializable(with = StrictStringSerializer::class) val note: String,
)

/**
 * [InquiryServicePlanRepository] on `fionas.inquiry_service_plans`: one immutable row per exact
 * Quote snapshot. The approving user references the runtime's published `commerce.users`.
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
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_service_plans
                        (document_id, document_version, inquiry_id, reviewed_document_version, plan, approved_at, approved_by)
                    VALUES (:document, :version, :inquiry, :reviewed, CAST(:plan AS jsonb), :at, :approvedBy)
                    """.trimIndent(),
                ).bind("document", plan.quote.id)
                .bind("version", plan.quote.version.number)
                .bind("inquiry", plan.inquiryId.value)
                .bind("reviewed", plan.reviewedVersion.number)
                .bind("plan", encode(plan))
                .bind("at", plan.approvedAt)
                .bind("approvedBy", plan.approvedBy.value)
                .execute()
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

    private fun encode(plan: InquiryServicePlan): String {
        val persisted =
            ServicePlanJson(
                plan.service.description,
                plan.service.guestCount,
                plan.service.durationMinutes,
                plan.service.items,
                plan.lineNotes.map { LineNoteJson(it.lineItemId.toString(), it.note) },
            )
        // Write only what reading would accept, so no invalid value is ever stored.
        check(persisted.restore() == (plan.service to plan.lineNotes)) { "A service plan must survive its persisted representation" }
        return persistedJson.encodeToString(ServicePlanJson.serializer(), persisted)
    }

    private fun restore(row: ResultSet): InquiryServicePlan {
        val documentId = row.getObject("document_id", UUID::class.java)
        val quote = FinancialDocumentReference(documentId, Version.of(row.getInt("document_version")))
        val owner = "the service plan of $documentId at ${quote.version}"
        return try {
            val (service, notes) = persistedJson.decodeFromString(ServicePlanJson.serializer(), row.getString("plan")).restore()
            InquiryServicePlan(
                inquiryId = InquiryId(row.getObject("inquiry_id", UUID::class.java)),
                quote = quote,
                reviewedVersion = Version.of(row.getInt("reviewed_document_version")),
                service = service,
                lineNotes = notes,
                approvedAt = row.getObject("approved_at", OffsetDateTime::class.java).toInstant(),
                approvedBy = UserId(row.getObject("approved_by", UUID::class.java)),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Malformed $owner: ${e.message}", e)
        }
    }
}

private fun ServicePlanJson.restore(): Pair<ServiceCommitment, List<ServicePlanLineNote>> =
    ServiceCommitment(description, guestCount, durationMinutes, items) to
        lineNotes.map { note ->
            val id = UUID.fromString(note.lineItemId)
            require(id.toString() == note.lineItemId) { "A noted line id must be a canonical UUID" }
            ServicePlanLineNote(id, note.note)
        }
