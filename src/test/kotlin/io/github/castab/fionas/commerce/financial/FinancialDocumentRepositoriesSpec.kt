package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.insertInquiryRecord
import io.github.castab.fionas.commerce.testing.sqlState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

/**
 * Fiona's financial-document context on real PostgreSQL: the inquiry association and the
 * per-snapshot pricing source, in runtime transactions, keyed to commerce-runtime's exact
 * `(document_id, version)` snapshots.
 */
class FinancialDocumentRepositoriesSpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        val dollars = Currency.getInstance("USD")

        fun usd(amount: String) = Money(BigDecimal(amount), dollars)

        fun line() = LineItem(UUID.randomUUID(), "Ice cream service", "75 guests", BigDecimal("75"), usd("4.00"), usd("0.00"))

        fun newLineage() = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))

        val inputs =
            FionasPricingInputs(
                OfferingsRevision.of(12),
                OfferingSelections(
                    listOf(
                        OfferingCategorySelection(
                            OfferingCategoryKey("soft-serve-flavor"),
                            listOf("vanilla", "horchata").map(::OfferingKey),
                        ),
                        // An explicitly empty block is kept as submitted.
                        OfferingCategorySelection(OfferingCategoryKey("sauce"), emptyList()),
                        OfferingCategorySelection(
                            OfferingCategoryKey("topping"),
                            listOf("oreos", "sprinkles", "brownies").map(::OfferingKey),
                        ),
                    ),
                ),
                FionasOfferingsContext(75, true, Duration.ofMinutes(150)),
            )

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

        test("pricing inputs round-trip for an exact snapshot, in order, with an explicitly empty block") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                sources.insert(transaction, estimate.reference, inputs)

                sources.find(transaction, estimate.reference) shouldBe inputs
                sources.find(transaction, FinancialDocumentReference(estimate.id, Version.of(2))).shouldBeNull()
                sources.findAll(transaction, estimate.id) shouldBe mapOf(Version.INITIAL to inputs)
            }
        }

        test("a transition's successor receives an exact copy of its source's pricing inputs") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                sources.insert(transaction, estimate.reference, inputs)
                val quote = application.context.financialLedger.issueQuote(transaction, estimate.id)

                sources.copy(transaction, estimate.reference, quote.reference)

                sources.find(transaction, quote.reference) shouldBe inputs
                sources.findAll(transaction, estimate.id) shouldBe mapOf(Version.INITIAL to inputs, Version.of(2) to inputs)
            }
        }

        test("a materialized snapshot needs no pricing source to transition") {
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                val quote = application.context.financialLedger.issueQuote(transaction, estimate.id)
                sources.copy(transaction, estimate.reference, quote.reference)
                sources.find(transaction, quote.reference).shouldBeNull()
            }
        }

        test("a pricing source references commerce-runtime's exact snapshot of a lineage Fiona owns") {
            // A snapshot version the ledger does not have.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    sources.insert(transaction, FinancialDocumentReference(estimate.id, Version.of(2)), inputs)
                }
            }.sqlState() shouldBe "23503"
            // A lineage no inquiry owns.
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (_, estimate) = inquiryAndEstimate(transaction)
                    sources.insert(transaction, estimate.reference, inputs)
                }
            }.sqlState() shouldBe "23503"
        }

        /** A committed inquiry-owned lineage whose first snapshot was priced from [inputs]. */
        fun pricedLineage(): FinancialDocument =
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                sources.insert(transaction, estimate.reference, inputs)
                estimate
            }

        fun storedPricing(snapshot: FinancialDocumentReference) =
            application.database.strings(
                "SELECT pricing_inputs::text FROM fionas.financial_document_pricing " +
                    "WHERE document_id = '${snapshot.id}' AND document_version = ${snapshot.version.number}",
            )

        test("a pricing source is one row holding the complete inputs in Fiona's persisted representation") {
            val estimate = pricedLineage()

            storedPricing(estimate.reference).map(Json::parseToJsonElement) shouldBe
                listOf(
                    Json.parseToJsonElement(
                        """
                        {"catalogRevision": 12,
                         "context": {"guestCount": 75, "guestCountIsMinimum": true, "durationMinutes": 150},
                         "selections": [{"categoryKey": "soft-serve-flavor", "offeringKeys": ["vanilla", "horchata"]},
                                        {"categoryKey": "sauce", "offeringKeys": []},
                                        {"categoryKey": "topping", "offeringKeys": ["oreos", "sprinkles", "brownies"]}]}
                        """.trimIndent(),
                    ),
                )
        }

        test("every version's own pricing source is found in version order") {
            val revised =
                inputs.copy(
                    catalogRevision = OfferingsRevision.of(13),
                    selections =
                        OfferingSelections(
                            listOf(
                                OfferingCategorySelection(OfferingCategoryKey("topping"), listOf("sprinkles", "oreos").map(::OfferingKey)),
                            ),
                        ),
                    context = FionasOfferingsContext(120, false, Duration.ofMinutes(90)),
                )
            application.transactor.inTransaction { transaction ->
                val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                sources.insert(transaction, estimate.reference, inputs)
                val quote = application.context.financialLedger.issueQuote(transaction, estimate.id)
                sources.insert(transaction, quote.reference, revised)
                val invoice = application.context.financialLedger.issueInvoice(transaction, estimate.id)
                sources.copy(transaction, quote.reference, invoice.reference)

                val all = sources.findAll(transaction, estimate.id)
                all.keys.toList() shouldContainExactly listOf(Version.INITIAL, Version.of(2), Version.of(3))
                all shouldBe mapOf(Version.INITIAL to inputs, Version.of(2) to revised, Version.of(3) to revised)
                sources.findAll(transaction, UUID.randomUUID()) shouldBe emptyMap()
            }
        }

        test("an exact version has at most one pricing source") {
            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    sources.insert(transaction, estimate.reference, inputs)
                    sources.insert(transaction, estimate.reference, inputs)
                }
            }.sqlState() shouldBe "23505"
        }

        test("a pricing source rolls back with the caller's transaction") {
            var reference: FinancialDocumentReference? = null
            val before = application.database.count("fionas.financial_document_pricing")
            shouldThrow<IllegalStateException> {
                application.transactor.inTransaction { transaction ->
                    val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                    associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                    sources.insert(transaction, estimate.reference, inputs)
                    reference = estimate.reference
                    error("a later write failed")
                }
            }
            application.database.count("fionas.financial_document_pricing") shouldBe before
            storedPricing(checkNotNull(reference)).shouldBeEmpty()
        }

        test("the database requires each pricing source's inputs, as a JSON object") {
            listOf("NULL" to "23502", "'[]'" to "23514", "'12'" to "23514").forEach { (value, state) ->
                shouldThrowAny {
                    application.transactor.inTransaction { transaction ->
                        val (inquiryId, estimate) = inquiryAndEstimate(transaction)
                        associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, STORED_INSTANT))
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO fionas.financial_document_pricing (document_id, document_version, pricing_inputs) " +
                                    "VALUES (:id, 1, $value)",
                            ).bind("id", estimate.id)
                            .execute()
                    }
                }.sqlState() shouldBe state
            }
        }

        test("a malformed stored pricing source fails find, findAll, and copy, naming its exact version, and is never copied") {
            listOf(
                """{"catalogRevision": 12, "selections": []}""",
                """{"catalogRevision": "12", "context": {"guestCount": 75, "guestCountIsMinimum": true, "durationMinutes": 150}, "selections": []}""",
                """{"catalogRevision": 12, "context": {"guestCount": 75, "guestCountIsMinimum": true, "durationMinutes": 150}, """ +
                    """"selections": [], "total": "1.00"}""",
                """{"catalogRevision": 12, "context": {"guestCount": 75, "guestCountIsMinimum": true, "durationMinutes": 150}, """ +
                    """"selections": [{"categoryKey": "topping", "offeringKeys": ["oreos", "oreos"]}]}""",
            ).forEach { corrupt ->
                val estimate = pricedLineage()
                application.database.execute(
                    "UPDATE fionas.financial_document_pricing SET pricing_inputs = '$corrupt' WHERE document_id = '${estimate.id}'",
                )
                val named = "pricing inputs of financial document ${estimate.id} version 1"

                withClue(corrupt) {
                    shouldThrow<IllegalStateException> {
                        application.transactor.inTransaction { sources.find(it, estimate.reference) }
                    }.message shouldContain named
                    shouldThrow<IllegalStateException> {
                        application.transactor.inTransaction { sources.findAll(it, estimate.id) }
                    }.message shouldContain named
                    var quote: FinancialDocumentReference? = null
                    shouldThrow<IllegalStateException> {
                        application.transactor.inTransaction { transaction ->
                            quote =
                                application.context.financialLedger
                                    .issueQuote(transaction, estimate.id)
                                    .reference
                            sources.copy(transaction, estimate.reference, checkNotNull(quote))
                        }
                    }.message shouldContain named
                    storedPricing(checkNotNull(quote)).shouldBeEmpty()
                    storedPricing(estimate.reference).map(Json::parseToJsonElement) shouldBe listOf(Json.parseToJsonElement(corrupt))
                }
            }
        }
    })
