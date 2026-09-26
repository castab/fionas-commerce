package io.github.castab.fionas.commerce.inquiry

import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.sqlState
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class JdbiInquiryRepositorySpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("an inserted inquiry is read back by id exactly as it was written") {
            val customer = customer()
            val inquiry = inquiry(customer, message = "Ice cream for 80 guests")

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry)
            }

            application.transactor.inTransaction { inquiries.findById(it, inquiry.id) } shouldBe inquiry
        }

        test("an inquiry without a message is stored and read back without one") {
            val customer = customer()
            val inquiry = inquiry(customer, message = null)

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry)
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
    })
