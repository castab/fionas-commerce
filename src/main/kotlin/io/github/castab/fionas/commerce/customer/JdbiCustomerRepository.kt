package io.github.castab.fionas.commerce.customer

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import org.jdbi.v3.core.mapper.RowMapper
import java.time.OffsetDateTime
import java.util.UUID

/** [CustomerRepository] on `fionas.customers`, through the transaction's JDBI handle. */
class JdbiCustomerRepository : CustomerRepository {
    override fun insert(
        transaction: Transaction,
        customer: Customer,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.customers (id, name, email, created_at)
                    VALUES (:id, :name, :email, :createdAt)
                    """.trimIndent(),
                ).bind("id", customer.id.value)
                .bind("name", customer.name.value)
                .bind("email", customer.email.value)
                .bind("createdAt", customer.createdAt)
                .execute()
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                throw CommerceFailure.Conflict("A customer with this email already exists; retry the request", e)
            }
            throw e
        }
    }

    override fun findById(
        transaction: Transaction,
        id: CustomerId,
    ): Customer? =
        transaction.handle
            .createQuery("SELECT id, name, email, created_at FROM fionas.customers WHERE id = :id")
            .bind("id", id.value)
            .map(customerRow)
            .findOne()
            .orElse(null)

    override fun findByIds(
        transaction: Transaction,
        ids: Set<CustomerId>,
    ): Map<CustomerId, Customer> {
        if (ids.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery("SELECT id, name, email, created_at FROM fionas.customers WHERE id = ANY(:ids)")
            .bindArray("ids", UUID::class.java, ids.map { it.value })
            .map(customerRow)
            .list()
            .associateBy { it.id }
    }

    override fun findByEmail(
        transaction: Transaction,
        email: Email,
    ): Customer? =
        transaction.handle
            .createQuery("SELECT id, name, email, created_at FROM fionas.customers WHERE email = :email")
            .bind("email", email.value)
            .map(customerRow)
            .findOne()
            .orElse(null)
}

private val customerRow =
    RowMapper { row, _ ->
        Customer(
            id = CustomerId(row.getObject("id", UUID::class.java)),
            name = CustomerName(row.getString("name")),
            email = Email(row.getString("email")),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }
