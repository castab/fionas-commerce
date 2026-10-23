package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.InternalComponentId
import io.github.castab.commerce.runtime.operation.OperationOrigin
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Who committed the financial lines of one exact snapshot: an authenticated principal or
 * a trusted internal component, and when Fiona recorded them. The web supplies intake prices;
 * the intake component commits them. Stage transitions preserve the source authorship.
 *
 * Provenance only: it never prices, validates or reconstructs a line.
 */
data class LineAuthorship(
    val author: OperationOrigin,
    val recordedAt: Instant,
)

/**
 * Line authorship per exact `(document id, version)`, inside the caller's [Transaction]. Never
 * begins, commits, or rolls back. Records are written once and never changed.
 */
interface FinancialDocumentAuthorshipRepository {
    /** Records [authorship] for [snapshot], which must exist and belong to an inquiry. */
    fun insert(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
        authorship: LineAuthorship,
    )

    /** The authorship of exactly [snapshot], or `null` when none is recorded. */
    fun find(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
    ): LineAuthorship?

    /** The authorship of every recorded version of the lineage [documentId]. */
    fun findAll(
        transaction: Transaction,
        documentId: UUID,
    ): Map<Version, LineAuthorship>

    /** Records [from]'s authorship unchanged for [to], a later snapshot with the same lines; a no-op when [from] has none. */
    fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    )
}

/** [FinancialDocumentAuthorshipRepository] on `fionas.financial_document_authorship`. */
class JdbiFinancialDocumentAuthorshipRepository : FinancialDocumentAuthorshipRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
        authorship: LineAuthorship,
    ) {
        val principal = (authorship.author as? OperationOrigin.Principal)?.principalId
        val component = (authorship.author as? OperationOrigin.Internal)?.component?.value
        val kind =
            when (principal) {
                is UserId -> "USER"
                is ServiceId -> "SERVICE"
                null -> "INTERNAL"
            }
        val id =
            when (principal) {
                is UserId -> principal.value
                is ServiceId -> principal.value
                null -> null
            }
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_authorship (document_id, document_version, author_kind, author_id, author_component, recorded_at)
                VALUES (:documentId, :version, :kind, CAST(:author AS uuid), :component, :recordedAt)
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .bind("kind", kind)
            .bindByType("author", id, UUID::class.java)
            .bind("component", component)
            .bind("recordedAt", authorship.recordedAt)
            .execute()
    }

    override fun find(
        transaction: Transaction,
        snapshot: FinancialDocumentReference,
    ): LineAuthorship? =
        transaction.handle
            .createQuery(
                """
                SELECT author_kind, author_id, author_component, recorded_at FROM fionas.financial_document_authorship
                WHERE document_id = :documentId AND document_version = :version
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .map { row, _ -> authorship(row.getString("author_kind"), row.getObject("author_id", UUID::class.java), row) }
            .findOne()
            .orElse(null)

    override fun findAll(
        transaction: Transaction,
        documentId: UUID,
    ): Map<Version, LineAuthorship> =
        transaction.handle
            .createQuery(
                """
                SELECT document_version, author_kind, author_id, author_component, recorded_at FROM fionas.financial_document_authorship
                WHERE document_id = :documentId ORDER BY document_version
                """.trimIndent(),
            ).bind("documentId", documentId)
            .map { row, _ ->
                Version.of(row.getInt("document_version")) to
                    authorship(row.getString("author_kind"), row.getObject("author_id", UUID::class.java), row)
            }.list()
            .toMap()

    override fun copy(
        transaction: Transaction,
        from: FinancialDocumentReference,
        to: FinancialDocumentReference,
    ) {
        check(from.id == to.id && from.version < to.version) { "Authorship is carried forward within one lineage, not $from to $to" }
        val source = find(transaction, from) ?: return
        insert(transaction, to, source)
    }

    private fun authorship(
        kind: String,
        id: UUID?,
        row: ResultSet,
    ): LineAuthorship =
        LineAuthorship(
            when (kind) {
                "USER" -> OperationOrigin.Principal(UserId(checkNotNull(id)))
                "SERVICE" -> OperationOrigin.Principal(ServiceId(checkNotNull(id)))
                "INTERNAL" -> OperationOrigin.Internal(InternalComponentId(row.getString("author_component")))
                else -> error("Invalid line author kind $kind")
            },
            row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
        )
}
