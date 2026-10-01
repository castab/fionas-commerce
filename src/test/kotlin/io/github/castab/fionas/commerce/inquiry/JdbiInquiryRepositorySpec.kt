package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.insertInquiryRecord
import io.github.castab.fionas.commerce.testing.sqlState
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JdbiInquiryRepositorySpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()
        val requested = JdbiInquiryPricingRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("required event ZIP is persisted with its inquiry and returned in detail and list reads") {
            val customer = customer()
            val inquiry = inquiry(customer).copy(zipCode = ZipCode("02108"))
            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                insertInquiryRecord(transaction, inquiry)
            }
            application.transactor.inTransaction { inquiries.findById(it, inquiry.id) } shouldBe inquiry
            application.transactor.inTransaction { inquiries.listNewestFirst(it, null, 100).single { it.id == inquiry.id } } shouldBe
                inquiry
            application.transactor.inTransaction { customers.findById(it, customer.id) } shouldBe customer
        }

        test("null and malformed ZIP columns fail in the database and roll back the caller's complete transaction") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val before = application.database.count("fionas.inquiries")
            listOf("NULL" to "23502", "'bad'" to "23514").forEach { (value, state) ->
                shouldThrowAny {
                    application.transactor.inTransaction { transaction ->
                        customers.insert(transaction, customer)
                        inquiries.insert(transaction, inquiry)
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO fionas.inquiries (id, customer_id, created_at, zip_code, event_date, event_type) " +
                                    "VALUES (:id, :customer, :time, $value, :eventDate, :eventType)",
                            ).bind("id", UUID.randomUUID())
                            .bind("customer", customer.id.value)
                            .bind("time", inquiry.createdAt)
                            .bind("eventDate", inquiry.eventDate.value)
                            .bind("eventType", inquiry.eventType.name)
                            .execute()
                    }
                }.sqlState() shouldBe state
                application.database.count("fionas.inquiries") shouldBe before
                application.transactor.inTransaction { customers.findById(it, customer.id) }.shouldBeNull()
            }
        }

        test("an inserted inquiry is read back by id exactly as it was written") {
            val customer = customer()
            val inquiry = inquiry(customer, message = "Ice cream for 80 guests")

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                insertInquiryRecord(transaction, inquiry)
            }

            application.transactor.inTransaction { inquiries.findById(it, inquiry.id) } shouldBe inquiry
        }

        test("database rejects missing event facts, unsupported event types and dates outside the wire year range") {
            val customer = customer()
            application.transactor.inTransaction { customers.insert(it, customer) }
            listOf(
                "NULL" to "'BIRTHDAY'" to "23502",
                "DATE '2026-12-05'" to "NULL" to "23502",
                "DATE '2026-12-05'" to "'UNKNOWN'" to "23514",
                "DATE '10000-01-01'" to "'BIRTHDAY'" to "23514",
                "DATE '0001-01-01 BC'" to "'BIRTHDAY'" to "23514",
            ).forEach { (values, state) ->
                val (date, type) = values
                shouldThrowAny {
                    application.transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO fionas.inquiries (id, customer_id, created_at, zip_code, event_date, event_type) " +
                                    "VALUES (:id, :customer, :time, '92626', $date, $type)",
                            ).bind("id", UUID.randomUUID())
                            .bind("customer", customer.id.value)
                            .bind("time", inquiry(customer).createdAt)
                            .execute()
                    }
                }.sqlState() shouldBe state
            }
        }

        test("an inquiry without a message is stored and read back without one") {
            val customer = customer()
            val inquiry = inquiry(customer, message = null)

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                insertInquiryRecord(transaction, inquiry)
            }

            application.transactor
                .inTransaction { inquiries.findById(it, inquiry.id) }
                ?.message
                .shouldBeNull()
        }

        test("an unknown id finds nothing") {
            application.transactor.inTransaction { inquiries.findById(it, InquiryId(UUID.randomUUID())) }.shouldBeNull()
        }

        test("an inquiry cannot reference a customer that does not exist") {
            val before = application.database.count("fionas.inquiries")

            val failure =
                shouldThrowAny {
                    application.transactor.inTransaction { inquiries.insert(it, inquiry(customer())) }
                }

            failure.sqlState() shouldBe "23503"
            application.database.count("fionas.inquiries") shouldBe before
        }

        test("inquiries are listed newest first, ties broken by id, continuing strictly after a position") {
            // Later than every other inquiry of this spec, so these lead the list.
            val customer = customer()
            val newest = inquiry(customer, createdAt = Instant.parse("2200-01-01T00:00:02Z"))
            val tied = List(4) { inquiry(customer, createdAt = Instant.parse("2200-01-01T00:00:01Z")) }
            val oldest = inquiry(customer, createdAt = Instant.parse("2200-01-01T00:00:00Z"))
            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                (tied + oldest + newest).forEach { insertInquiryRecord(transaction, it) }
            }
            // PostgreSQL orders uuids as their canonical lowercase text.
            val expected = listOf(newest) + tied.sortedByDescending { it.id.value.toString() } + oldest

            fun list(
                after: Inquiry?,
                limit: Int,
            ) = application.transactor.inTransaction { transaction ->
                inquiries.listNewestFirst(transaction, after?.let { InquiryListPosition(it.createdAt, it.id) }, limit)
            }

            list(null, 6) shouldContainExactly expected
            list(expected[1], 2) shouldContainExactly expected.subList(2, 4)
            list(expected[3], 2) shouldContainExactly expected.subList(4, 6)
            // Pages of one never repeat or skip an inquiry sharing a timestamp.
            generateSequence(list(null, 1).single()) { previous -> list(previous, 1).single() }
                .take(6)
                .toList() shouldContainExactly expected
        }

        test("the list reads the inquiries index backwards, without sorting, for the first and every later page") {
            listOf(
                "SELECT id, customer_id, message, created_at, zip_code FROM fionas.inquiries ORDER BY created_at DESC, id DESC LIMIT 26",
                "SELECT id, customer_id, message, created_at, zip_code FROM fionas.inquiries " +
                    "WHERE (created_at, id) < (now(), gen_random_uuid()) " +
                    "ORDER BY created_at DESC, id DESC LIMIT 26",
            ).forEach { query ->
                val plan =
                    application.database.connect { connection ->
                        connection.createStatement().use { statement ->
                            // A handful of test rows would otherwise make any plan cheapest.
                            statement.execute("SET enable_seqscan = off")
                            statement.executeQuery("EXPLAIN $query").use { rows ->
                                buildList { while (rows.next()) add(rows.getString(1)) }.joinToString(" / ")
                            }
                        }
                    }
                plan shouldContain "Index Scan Backward using inquiries_created_at_id_idx"
                plan shouldNotContain "Sort"
            }
        }

        test("an inquiry's requested pricing inputs are read back exactly, in order, empty blocks included") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val inputs =
                FionasPricingInputs(
                    catalogRevision = OfferingsRevision.of(7),
                    selections =
                        OfferingSelections(
                            listOf(
                                OfferingCategorySelection(OfferingCategoryKey("topping"), listOf("oreos", "sprinkles").map(::OfferingKey)),
                                OfferingCategorySelection(OfferingCategoryKey("cone-option"), emptyList()),
                                OfferingCategorySelection(OfferingCategoryKey("soft-serve-flavor"), listOf(OfferingKey("vanilla"))),
                            ),
                        ),
                    context = FionasOfferingsContext(guestCount = 100, guestCountIsMinimum = true, duration = Duration.ofMinutes(150)),
                )

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry)
                requested.insert(transaction, inquiry.id, inputs)
            }

            application.transactor.inTransaction { requested.find(it, inquiry.id) } shouldBe inputs
            application.transactor.inTransaction { requested.find(it, InquiryId(UUID.randomUUID())) }.shouldBeNull()
        }

        test("an inquiry cannot commit without its requested pricing inputs") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val before = application.database.count("fionas.inquiries")

            val failure =
                shouldThrowAny {
                    application.transactor.inTransaction { transaction ->
                        customers.insert(transaction, customer)
                        inquiries.insert(transaction, inquiry)
                    }
                }

            // The deferred reference is checked at commit, so the whole transaction rolls back.
            failure.sqlState() shouldBe "23503"
            application.database.count("fionas.inquiries") shouldBe before
            application.transactor.inTransaction { customers.findById(it, customer.id) }.shouldBeNull()
        }

        test("requested pricing inputs cannot exist without their inquiry") {
            val failure =
                shouldThrowAny {
                    application.transactor.inTransaction {
                        requested.insert(
                            it,
                            InquiryId(UUID.randomUUID()),
                            FionasPricingInputs(
                                OfferingsRevision.of(1),
                                OfferingSelections(emptyList()),
                                FionasOfferingsContext(1, false, Duration.ofMinutes(90)),
                            ),
                        )
                    }
                }

            failure.sqlState() shouldBe "23503"
        }

        test("customers are found by id in one read, and unknown ids are absent") {
            val first = customer()
            val second = customer()
            application.transactor.inTransaction { transaction -> listOf(first, second).forEach { customers.insert(transaction, it) } }

            application.transactor.inTransaction {
                customers.findByIds(it, setOf(first.id, second.id, CustomerId(UUID.randomUUID())))
            } shouldBe mapOf(first.id to first, second.id to second)
            application.transactor.inTransaction { customers.findByIds(it, emptySet()) } shouldBe emptyMap()
        }
    })
