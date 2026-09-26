package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.inquiry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Fiona repositories participate in commerce-runtime's transaction: they write through the
 * `Transaction` they are given, on the runtime's `Transactor`, and open nothing of their own.
 */
class RuntimeTransactionSpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("a customer and an inquiry written in one runtime transaction both roll back when it fails") {
            val customer = customer()
            val inquiry = inquiry(customer)

            val failure =
                shouldThrow<IllegalStateException> {
                    application.transactor.inTransaction { transaction ->
                        customers.insert(transaction, customer)
                        inquiries.insert(transaction, inquiry)
                        // Both writes are visible inside the transaction...
                        customers.findById(transaction, customer.id) shouldBe customer
                        inquiries.findById(transaction, inquiry.id) shouldBe inquiry
                        error("the operation failed after both writes")
                    }
                }

            // ...and neither survives its rollback.
            failure.message shouldBe "the operation failed after both writes"
            application.transactor.inTransaction { transaction ->
                customers.findById(transaction, customer.id).shouldBeNull()
                inquiries.findById(transaction, inquiry.id).shouldBeNull()
            }
        }

        test("repository writes are invisible to other connections until the runtime transaction commits") {
            val customer = customer()
            val inquiry = inquiry(customer)
            val customersBefore = application.database.count("public.customers")
            val inquiriesBefore = application.database.count("public.inquiries")

            application.transactor.inTransaction { transaction ->
                customers.insert(transaction, customer)
                inquiries.insert(transaction, inquiry)
                // A repository that auto-committed or used its own transaction would already
                // have made these rows visible to another connection.
                application.database.count("public.customers") shouldBe customersBefore
                application.database.count("public.inquiries") shouldBe inquiriesBefore
            }

            application.database.count("public.customers") shouldBe customersBefore + 1
            application.database.count("public.inquiries") shouldBe inquiriesBefore + 1
        }
    })
