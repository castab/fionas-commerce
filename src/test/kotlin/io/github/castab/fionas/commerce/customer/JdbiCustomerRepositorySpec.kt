package io.github.castab.fionas.commerce.customer

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.customer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

class JdbiCustomerRepositorySpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("an inserted customer is read back by id exactly as it was written") {
            val customer = customer(name = "Jane Doe")

            application.transactor.inTransaction { customers.insert(it, customer) }

            application.transactor.inTransaction { customers.findById(it, customer.id) } shouldBe customer
        }

        test("a customer is found by normalized email") {
            val customer = customer(email = "lookup-${UUID.randomUUID()}@example.com")
            application.transactor.inTransaction { customers.insert(it, customer) }

            application.transactor.inTransaction {
                customers.findByEmail(it, Email.of(customer.email.value.uppercase()))
            } shouldBe customer
        }

        test("an unknown id or email finds nothing") {
            application.transactor.inTransaction { transaction ->
                customers.findById(transaction, CustomerId(UUID.randomUUID())).shouldBeNull()
                customers.findByEmail(transaction, Email.of("nobody-${UUID.randomUUID()}@example.com")).shouldBeNull()
            }
        }

        test("a second customer with the same email is a conflict that reveals no database detail") {
            val first = customer()
            application.transactor.inTransaction { customers.insert(it, first) }

            val failure =
                shouldThrow<CommerceFailure.Conflict> {
                    application.transactor.inTransaction { customers.insert(it, customer(email = first.email.value, name = "Other")) }
                }

            failure.message shouldBe "A customer with this email already exists; retry the request"
            failure.message shouldNotContain "customers_email_key"
            application.transactor.inTransaction { customers.findByEmail(it, first.email) } shouldBe first
        }
    })
