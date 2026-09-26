package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.http.inquiryRoutes
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import java.time.Clock

/**
 * Where Fiona's own migrations live: Fiona's migration stream, applied by commerce-runtime
 * after its own. Never `db/commerce`; the runtime discovers and applies its migrations itself.
 */
const val FIONA_MIGRATION_LOCATION = "classpath:db/fionas"

/**
 * Everything Fiona's contributes to commerce-runtime: the location of its own migrations
 * (never the runtime's) and its routes.
 *
 * This is the application's composition root. Repositories and operations are built here
 * with ordinary Kotlin from the runtime's `CommerceRuntimeContext`, so every Fiona write
 * goes through the runtime's single `Transactor`.
 */
fun fionaApplication(clock: Clock = Clock.systemUTC()): ApplicationContributions =
    ApplicationContributions(
        migrationLocations = listOf(FIONA_MIGRATION_LOCATION),
        routes = { context ->
            val customers = JdbiCustomerRepository()
            val inquiries = JdbiInquiryRepository()
            listOf(
                inquiryRoutes(
                    createInquiry = CreateInquiry(context.transactor, customers, inquiries, clock),
                    getInquiry = GetInquiry(context.transactor, customers, inquiries),
                ),
            )
        },
    )
