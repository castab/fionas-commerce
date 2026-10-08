package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.changeLatest
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.insertInquiryRecord
import io.github.castab.fionas.commerce.testing.invoiceLatest
import io.github.castab.fionas.commerce.testing.quoteLatest
import io.github.castab.fionas.commerce.testing.sqlState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Fiona's financial-document context on real PostgreSQL: the inquiry association and the
 * per-snapshot line authorship, in runtime transactions, keyed to commerce-runtime's exact
 * `(document_id, version)` snapshots.
 */
class FinancialDocumentRepositoriesSpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val authorship = JdbiFinancialDocumentAuthorshipRepository()

        val dollars = Currency.getInstance("USD")

        fun usd(amount: String) = Money(BigDecimal(amount), dollars)

        fun line() = LineItem(UUID.randomUUID(), "Ice cream service", "75 guests", BigDecimal("75"), usd("4.00"), usd("0.00"))

        fun newLineage() = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))

        val service = LineAuthorship(ServiceId(UUID.fromString("00000000-0000-0000-0000-00000000000a")), STORED_INSTANT)
        val staff = LineAuthorship(UserId(UUID.fromString("00000000-0000-0000-0000-00000000000b")), STORED_INSTANT.plusSeconds(60))

        /** A persisted inquiry and the first snapshot of a new commerce-runtime lineage, in [transaction]. */
        fun inquiryAndEstimate(transaction: Transaction): Pair<InquiryId, FinancialDocument> {
            val customer = customer().also { customers.insert(transaction, it) }
            val inquiry = inquiry(customer).also { insertInquiryRecord(transaction, it) }
            val estimate = application.context.financialLedger.create(transaction, newLineage())
            return inquiry.id to estimate
        }

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("an association records which inquiry owns a lineage, and an inquiry may own several") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, first) = inquiryAndEstimate(transaction)
                val second = application.context.financialLedger.create(transaction, newLineage())
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, first.id, STORED_INSTANT))
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, second.id, STORED_INSTANT))

                associations.inquiryOf(transaction, first.id) shouldBe inquiryId
                associations.lockInquiryOf(transaction, second.id) shouldBe inquiryId
                associations.documentsOf(transaction, inquiryId) shouldContainExactlyInAnyOrder listOf(first.id, second.id)
                associations.inquiryOf(transaction, UUID.randomUUID()).shouldBeNull()
                associations.documentsOf(transaction, InquiryId(UUID.randomUUID())).shouldBeEmpty()
            }
        }

        test("a lineage belongs to one inquiry only") {
            var lineage: UUID? = null
            val failure =
                shouldThrow<CommerceFailure.Conflict> {
                    application.transactor.inTransaction { transaction ->
                        val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                        val (otherInquiry, _) = inquiryAndEstimate(transaction)
                        lineage = estimate.id
                        associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                        associations.associate(transaction, InquiryDocumentAssociation(otherInquiry, estimate.id, STORED_INSTANT))
                    }
                }
            failure.message shouldBe "Financial document $lineage already belongs to an inquiry"
        }

        test("an association references a real inquiry and commerce-runtime's first snapshot of the lineage") {
            // No such lineage in the ledger.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, _) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, UUID.randomUUID(), STORED_INSTANT))
                }
            }.sqlState() shouldBe "23503"
            // No such inquiry.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (_, estimate) = inquiryAndEstimate(transaction)
                    val unknown = InquiryId(UUID.randomUUID())
                    associations.associate(transaction, InquiryDocumentAssociation(unknown, estimate.id, STORED_INSTANT))
                }
            }.sqlState() shouldBe "23503"
        }

        test("line authorship round-trips for an exact snapshot, for a SERVICE or a USER author") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                authorship.insert(transaction, estimate.reference, service)

                authorship.find(transaction, estimate.reference) shouldBe service
                authorship.find(transaction, FinancialDocumentReference(estimate.id, Version.of(2))).shouldBeNull()
                authorship.findAll(transaction, estimate.id) shouldBe mapOf(Version.INITIAL to service)
                val revised =
                    application.context.financialLedger.changeLatest(
                        transaction,
                        estimate.id,
                        ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line()))),
                    )
                authorship.insert(transaction, revised.reference, staff)
                authorship.findAll(transaction, estimate.id) shouldBe mapOf(Version.INITIAL to service, Version.of(2) to staff)
            }
        }

        test("a transition's successor carries its source's authorship forward; a snapshot without one carries none") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                authorship.insert(transaction, estimate.reference, staff)
                val quote = application.context.financialLedger.quoteLatest(transaction, estimate.id)
                authorship.copy(transaction, estimate.reference, quote.reference)
                val invoice = application.context.financialLedger.invoiceLatest(transaction, estimate.id)
                authorship.copy(transaction, quote.reference, invoice.reference)

                authorship.findAll(transaction, estimate.id) shouldBe
                    mapOf(Version.INITIAL to staff, Version.of(2) to staff, Version.of(3) to staff)
                authorship.findAll(transaction, UUID.randomUUID()) shouldBe emptyMap()
            }
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                val quote = application.context.financialLedger.quoteLatest(transaction, estimate.id)
                authorship.copy(transaction, estimate.reference, quote.reference)
                authorship.find(transaction, quote.reference).shouldBeNull()
            }
        }

        test("authorship references commerce-runtime's exact snapshot of a lineage Fiona owns, once per version") {
            // A snapshot version the ledger does not have.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    authorship.insert(transaction, FinancialDocumentReference(estimate.id, Version.of(2)), service)
                }
            }.sqlState() shouldBe "23503"
            // A lineage no inquiry owns.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (_, estimate) = inquiryAndEstimate(transaction)
                    authorship.insert(transaction, estimate.reference, service)
                }
            }.sqlState() shouldBe "23503"
            // An exact version has at most one author.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    authorship.insert(transaction, estimate.reference, service)
                    authorship.insert(transaction, estimate.reference, staff)
                }
            }.sqlState() shouldBe "23505"
        }

        test("authorship rolls back with the caller's transaction, and the database accepts only USER or SERVICE authors") {
            val before = application.database.count("fionas.financial_document_authorship")
            shouldThrow<IllegalStateException> {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    authorship.insert(transaction, estimate.reference, service)
                    error("a later write failed")
                }
            }
            application.database.count("fionas.financial_document_authorship") shouldBe before
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    transaction.handle
                        .createUpdate(
                            "INSERT INTO fionas.financial_document_authorship " +
                                "(document_id, document_version, author_kind, author_id, recorded_at) " +
                                "VALUES (:id, 1, 'BROWSER', :author, now())",
                        ).bind("id", estimate.id)
                        .bind("author", UUID.randomUUID())
                        .execute()
                }
            }.sqlState() shouldBe "23514"
        }
    })
