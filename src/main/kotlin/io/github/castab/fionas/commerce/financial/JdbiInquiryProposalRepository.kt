package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** [InquiryProposalRepository] on `fionas.inquiry_proposals`; the publisher references the runtime's published `commerce.users`. */
class JdbiInquiryProposalRepository : InquiryProposalRepository {
    override fun append(
        transaction: Transaction,
        proposal: InquiryProposal,
    ) {
        transaction.handle
            .createQuery(
                "SELECT document_id FROM fionas.inquiry_financial_documents " +
                    "WHERE inquiry_id = :inquiry AND document_id = :document AND purpose = 'INITIAL_ESTIMATE' FOR UPDATE",
            ).bind("inquiry", proposal.inquiryId.value)
            .bind("document", proposal.documentReference.id)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseThrow { CommerceFailure.NotFound("Canonical inquiry financial lineage was not found") }
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_proposals
                        (id, inquiry_id, document_id, document_version, deposit_requirement_revision, kind, issued_at, issued_by)
                    VALUES (:id, :inquiry, :document, :version, :revision, :kind, :at, :issuedBy)
                    """.trimIndent(),
                ).bind("id", proposal.id.value)
                .bind("inquiry", proposal.inquiryId.value)
                .bind("document", proposal.documentReference.id)
                .bind("version", proposal.documentReference.version.number)
                .bind("revision", proposal.depositRequirementRevision.number)
                .bind("kind", proposal.kind.name)
                .bind("at", proposal.issuedAt)
                .bind("issuedBy", proposal.issuedBy.value)
                .execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Proposal has already been issued", failure)
            throw failure
        }
    }

    override fun latest(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryProposal? =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.inquiry_proposals WHERE inquiry_id = :inquiry ORDER BY recorded_order DESC LIMIT 1",
            ).bind("inquiry", inquiryId.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    override fun history(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<InquiryProposal> =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.inquiry_proposals WHERE inquiry_id = :inquiry ORDER BY recorded_order",
            ).bind("inquiry", inquiryId.value)
            .map { row, _ -> restore(row) }
            .list()

    override fun find(
        transaction: Transaction,
        id: InquiryProposalId,
    ): InquiryProposal? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_proposals WHERE id = :id")
            .bind("id", id.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    private fun restore(row: ResultSet): InquiryProposal =
        InquiryProposal(
            InquiryProposalId(row.getObject("id", UUID::class.java)),
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            FinancialDocumentReference(row.getObject("document_id", UUID::class.java), Version.of(row.getInt("document_version"))),
            DepositRequirementRevision.of(row.getInt("deposit_requirement_revision")),
            row.getObject("issued_at", OffsetDateTime::class.java).toInstant(),
            UserId(row.getObject("issued_by", UUID::class.java)),
            ProposalIssuanceKind.valueOf(row.getString("kind")),
        )
}
