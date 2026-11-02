package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.inquiryProposals
import io.github.castab.fionas.commerce.testing.issueProposal
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

class CustomerFacingRevisionMigrationSpec :
    FunSpec({
        val migration = checkNotNull(javaClass.getResource("/db/fionas/V9__customer_facing_revisions.sql")).readText()

        test("V9 backfills repeated financial snapshots in durable publication order independently of timestamps") {
            TestApplication.create().use { app ->
                val inquiry = InquiryId(UUID.fromString(app.createInquiry()))
                app.issueProposal(inquiry, "100")
                (1..2).forEach { index ->
                    ReviseInquiryProposalDeposit(app.transactor, app.inquiryProposals())(
                        ReviseInquiryProposalDeposit.Command(
                            inquiry,
                            Version.of(2),
                            DepositRequirementRevision.of(index),
                            DepositTerms.Fixed(Money(BigDecimal(100 + index), Currency.getInstance("USD"))),
                            app.adminId,
                            index + 1,
                        ),
                    )
                }
                app.database.execute("UPDATE fionas.inquiry_proposals SET issued_at = issued_at - quote_revision * interval '1 day'")
                // Exercise the actual V9 SQL against the exact V8 table shape. Roll back the
                // fixture DDL afterwards; normal database creation above still uses runtime Flyway.
                app.database.connect { connection ->
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.execute("DROP TABLE fionas.inquiry_invoice_amendments")
                            statement.execute("ALTER TABLE fionas.inquiry_proposals DROP COLUMN quote_revision")
                            statement.execute(migration)
                            statement
                                .executeQuery(
                                    "SELECT quote_revision, document_version FROM fionas.inquiry_proposals ORDER BY recorded_order",
                                ).use { rows ->
                                    buildList { while (rows.next()) add(rows.getInt(1) to rows.getInt(2)) } shouldBe
                                        listOf(2 to 2, 3 to 2, 4 to 2)
                                }
                        }
                    } finally {
                        connection.rollback()
                    }
                }
            }
        }

        test("V9 fails before any data change when pre-production booked history needs an operator reset") {
            TestApplication.create().use { app ->
                val inquiry = InquiryId(UUID.fromString(app.createInquiry()))
                val issued = app.issueProposal(inquiry, "100")
                val proposal = issued.proposal
                app
                    .adminPost(
                        "/financial-documents/${proposal.documentReference.id}/payments",
                        """{"documentVersion":2,"amount":"100","method":"CASH","expectedProposalId":"${proposal.id.value}"}""",
                    ).status shouldBe Status.CREATED
                val before = app.database.count("commerce.financial_document_snapshots")
                shouldThrowAny { app.database.execute(migration) }.message.orEmpty() shouldContain "operator reset"
                app.database.count("commerce.financial_document_snapshots") shouldBe before
                app.database.count("fionas.inquiry_invoice_amendments") shouldBe 1
                app.database.count("fionas.inquiry_proposals") shouldBe 1
            }
        }
    })
