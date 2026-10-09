package io.github.castab.fionas.commerce.inquiry

import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.insertInquiryRecord
import io.github.castab.fionas.commerce.testing.requestedService
import io.github.castab.fionas.commerce.testing.sqlState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class JdbiInquiryRepositorySpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()

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
                        inquiries.insert(transaction, inquiry, requestedService())
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO fionas.inquiries " +
                                    "(id, customer_id, created_at, zip_code, event_date, event_type, requested_service) " +
                                    "VALUES (:id, :customer, :time, $value, :eventDate, :eventType, '{}')",
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
                                "INSERT INTO fionas.inquiries " +
                                    "(id, customer_id, created_at, zip_code, event_date, event_type, requested_service) " +
                                    "VALUES (:id, :customer, :time, '92626', $date, $type, '{}')",
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
                    application.transactor.inTransaction { inquiries.insert(it, inquiry(customer()), requestedService()) }
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

        // Every stored property of a RequestedService, ordered items, and optional values present and absent.
        val orderedRequest =
            RequestedService(
                guestCount = 100,
                guestCountIsMinimum = true,
                durationMinutes = 150,
                items =
                    listOf(
                        RequestedServiceItem("Sprinkles", "Toppings", "sprinkles"),
                        RequestedServiceItem("Churros, if possible", null, null),
                        RequestedServiceItem("Horchata soft serve", "Soft serve", "horchata"),
                    ),
                pricingReference = "fionas-web-pricing@2026-10-01",
            )

        fun storedRequest(id: InquiryId) =
            Json.parseToJsonElement(
                application.database.strings("SELECT requested_service::text FROM fionas.inquiries WHERE id = '${id.value}'").single(),
            )

        test("an inquiry and its complete requested service are one row, in Fiona's persisted representation") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val before = application.database.count("fionas.inquiries")

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry, orderedRequest)
            }

            application.database.count("fionas.inquiries") shouldBe before + 1
            storedRequest(inquiry.id) shouldBe
                Json.parseToJsonElement(
                    """
                    {"guestCount": 100, "guestCountIsMinimum": true, "durationMinutes": 150,
                     "items": [{"label": "Sprinkles", "group": "Toppings", "key": "sprinkles"},
                               {"label": "Churros, if possible", "group": null, "key": null},
                               {"label": "Horchata soft serve", "group": "Soft serve", "key": "horchata"}],
                     "pricingReference": "fionas-web-pricing@2026-10-01"}
                    """.trimIndent(),
                )
        }

        test("an inquiry's requested service is read back with it exactly, in order, absent values included") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val minimal = RequestedService(5, false, null, emptyList(), null)
            val other = inquiry(customer)
            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry, orderedRequest)
                inquiries.insert(transaction, other, minimal)
            }

            val read = checkNotNull(application.transactor.inTransaction { inquiries.findRequested(it, inquiry.id) })

            read shouldBe RequestedInquiry(inquiry, orderedRequest)
            read.requestedService.items.map { it.label } shouldContainExactly
                listOf("Sprinkles", "Churros, if possible", "Horchata soft serve")
            application.transactor.inTransaction { inquiries.findRequested(it, other.id) }?.requestedService shouldBe minimal
            application.transactor.inTransaction { inquiries.findRequested(it, InquiryId(UUID.randomUUID())) }.shouldBeNull()
        }

        test("an inquiry and its requested service roll back together with the caller's transaction") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val before = application.database.count("fionas.inquiries")

            shouldThrowAny {
                application.transactor.inTransaction { transaction ->
                    customers.insert(transaction, customer)
                    inquiries.insert(transaction, inquiry, orderedRequest)
                    error("a later write in the same operation failed")
                }
            }

            application.database.count("fionas.inquiries") shouldBe before
            application.transactor.inTransaction { inquiries.findRequested(it, inquiry.id) }.shouldBeNull()
            application.transactor.inTransaction { customers.findById(it, customer.id) }.shouldBeNull()
        }

        test("the database requires every inquiry's requested service, as a JSON object") {
            val customer = customer()
            application.transactor.inTransaction { customers.insert(it, customer) }
            listOf("NULL" to "23502", "'[]'" to "23514", "'\"service\"'" to "23514", "'7'" to "23514").forEach { (value, state) ->
                shouldThrowAny {
                    application.transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO fionas.inquiries " +
                                    "(id, customer_id, created_at, zip_code, event_date, event_type, requested_service) " +
                                    "VALUES (:id, :customer, :time, '92626', DATE '2026-12-05', 'BIRTHDAY', $value)",
                            ).bind("id", UUID.randomUUID())
                            .bind("customer", customer.id.value)
                            .bind("time", inquiry(customer).createdAt)
                            .execute()
                    }
                }.sqlState() shouldBe state
            }
        }

        test("a requested service that breaks its invariants cannot even be constructed") {
            listOf<() -> Any>(
                { RequestedService(0, false, null, emptyList(), null) },
                { RequestedService(1, false, 0, emptyList(), null) },
                { RequestedService(1, false, null, emptyList(), " padded ") },
                { RequestedService(1, false, null, List(101) { RequestedServiceItem("Item $it", null, null) }, null) },
                { RequestedServiceItem("  ", null, null) },
                { RequestedServiceItem("Label", "", null) },
                { RequestedServiceItem("x".repeat(201), null, null) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }

        test("a malformed stored requested service fails loudly, naming its inquiry, and is never repaired") {
            val customer = customer()
            val inquiry = inquiry(customer)
            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry, orderedRequest)
            }

            fun item(
                label: String = "\"Sprinkles\"",
                extra: String = "",
            ) = """{"label": $label, "group": null, "key": null$extra}"""

            fun stored(
                guests: String = "100",
                minimum: String = "true",
                minutes: String = "150",
                items: String = "[${item()}]",
                reference: String = "null",
                extra: String = "",
            ) = """{"guestCount": $guests, "guestCountIsMinimum": $minimum, "durationMinutes": $minutes, "items": $items, """ +
                """"pricingReference": $reference$extra}"""
            listOf(
                // Missing and null properties are never defaulted.
                """{"guestCountIsMinimum": true, "durationMinutes": 150, "items": [], "pricingReference": null}""",
                """{"guestCount": 100, "durationMinutes": 150, "items": [], "pricingReference": null}""",
                """{"guestCount": 100, "guestCountIsMinimum": true, "durationMinutes": 150, "pricingReference": null}""",
                """{"guestCount": 100, "guestCountIsMinimum": true, "durationMinutes": 150, "items": []}""",
                stored(guests = "null"),
                stored(items = "null"),
                stored(items = "[{\"label\": \"x\"}]"),
                stored(items = "[${item(label = "null")}]"),
                // Unknown properties are never ignored.
                stored(extra = """, "total": "250.00""""),
                stored(items = "[${item(extra = """, "price": 1""")}]"),
                // JSON values of the wrong type are never coerced.
                stored(guests = "\"75\""),
                stored(guests = "7.5"),
                stored(minimum = "\"true\""),
                stored(minimum = "1"),
                stored(minutes = "2147483648"),
                stored(items = item()),
                stored(reference = "5"),
                // Values the domain rejects.
                stored(guests = "0"),
                stored(minutes = "0"),
                stored(items = "[${item(label = "\" padded \"")}]"),
                stored(reference = "\"\""),
            ).forEach { corrupt ->
                application.database.execute(
                    "UPDATE fionas.inquiries SET requested_service = '${corrupt.replace("'", "''")}' WHERE id = '${inquiry.id.value}'",
                )

                val failure =
                    shouldThrow<IllegalStateException> {
                        application.transactor.inTransaction { inquiries.findRequested(it, inquiry.id) }
                    }

                withClue(corrupt) { failure.message shouldContain "requested service of inquiry ${inquiry.id.value}" }
                storedRequest(inquiry.id) shouldBe Json.parseToJsonElement(corrupt)
            }
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
