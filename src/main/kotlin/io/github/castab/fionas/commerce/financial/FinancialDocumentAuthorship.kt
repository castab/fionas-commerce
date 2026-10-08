package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Who committed the financial lines of one exact snapshot: the authenticated principal whose
 * authority the amounts rest on, and when Fiona recorded them. A public inquiry's Estimate v1
 * is authored by the web server's SERVICE principal (the public pricing authority); staff
 * creations and change orders by the verified staff USER. Stage transitions do not author lines:
 * they carry their predecessor's authorship forward, because the lines are the same.
 *
 * Provenance only: it never prices, validates or reconstructs a line.
 */
data class LineAuthorship(
    val author: PrincipalId,
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
        val (kind, id) =
            when (val author = authorship.author) {
                is UserId -> "USER" to author.value
                is ServiceId -> "SERVICE" to author.value
            }
        transaction.handle
            .createUpdate(
                """
                INSERT INTO fionas.financial_document_authorship (document_id, document_version, author_kind, author_id, recorded_at)
                VALUES (:documentId, :version, :kind, :author, :recordedAt)
                """.trimIndent(),
            ).bind("documentId", snapshot.id)
            .bind("version", snapshot.version.number)
            .bind("kind", kind)
            .bind("author", id)
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
                SELECT author_kind, author_id, recorded_at FROM fionas.financial_document_authorship
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
                SELECT document_version, author_kind, author_id, recorded_at FROM fionas.financial_document_authorship
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
        id: UUID,
        row: ResultSet,
    ): LineAuthorship =
        LineAuthorship(
            when (kind) {
                "USER" -> UserId(id)
                "SERVICE" -> ServiceId(id)
                else -> error("Invalid line author kind $kind")
            },
            row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
        )
}
