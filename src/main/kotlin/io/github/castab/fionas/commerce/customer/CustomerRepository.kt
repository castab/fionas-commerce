package io.github.castab.fionas.commerce.customer

import io.github.castab.commerce.runtime.persistence.Transaction

/**
 * Persistence of [Customer]s, inside the caller's [Transaction]. Never begins, commits, or
 * rolls back a transaction; the calling operation owns the boundary.
 */
interface CustomerRepository {
    /** Inserts [customer]. Fails with `CommerceFailure.Conflict` when another customer already has its email. */
    fun insert(
        transaction: Transaction,
        customer: Customer,
    )

    fun findById(
        transaction: Transaction,
        id: CustomerId,
    ): Customer?

    /** The customers among [ids] that exist, by id. */
    fun findByIds(
        transaction: Transaction,
        ids: Set<CustomerId>,
    ): Map<CustomerId, Customer>

    fun findByEmail(
        transaction: Transaction,
        email: Email,
    ): Customer?
}
