package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.runtime.offering.OfferingsHttpAccess
import io.github.castab.commerce.runtime.offering.OfferingsHttpBinding
import java.util.UUID

/**
 * Fiona's primary Offerings catalog: what Fiona's sells, as commerce-runtime's append-only
 * catalog snapshots record it.
 *
 * The id is Fiona's choice and part of its source: never generated at startup, never
 * configured, and never stored in a Fiona table. Every environment has its own database, so
 * development, test, staging, and production all use this same logical id. Changing it
 * orphans every revision recorded under the old one.
 */
val FIONA_OFFERINGS_CATALOG_ID = OfferingsCatalogId(UUID.fromString("0cde8e0b-aa9c-4129-9853-8db2cbbb909b"))

/**
 * Where Fiona serves its catalog through commerce-runtime's Offerings HTTP capability, which
 * implements every route, body, and revision rule: Fiona chooses only the catalog, the base
 * path, and the operationId prefix (operationIds are part of the API contract).
 *
 * [OfferingsHttpAccess.READ_WRITE] exposes the three routes that append revisions, so the
 * catalog can be administered through the API. It is route exposure, not protection: the API
 * has no authentication, so these routes must stay inside the deployment's trusted boundary
 * until authentication and authorization exist.
 */
val FIONA_OFFERINGS_BINDING =
    OfferingsHttpBinding(
        catalogId = FIONA_OFFERINGS_CATALOG_ID,
        basePath = "/offering-catalog",
        operationIdPrefix = "fionasOfferings",
        access = OfferingsHttpAccess.READ_WRITE,
    )
