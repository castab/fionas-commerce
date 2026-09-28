package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.offering.OfferingsHttpAccess
import io.github.castab.commerce.runtime.offering.OfferingsHttpBinding
import org.http4k.contract.Tag
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
 * path, the operationId prefix (part of the API contract), and its OpenAPI group.
 *
 * [OfferingsHttpAccess.ReadWrite] exposes the three routes that append revisions and asks
 * the runtime to require `CommercePermissions.OfferingsManage` through [accessControl].
 */
fun fionaOfferingsBinding(accessControl: AccessControl) =
    OfferingsHttpBinding(
        catalogId = FIONA_OFFERINGS_CATALOG_ID,
        basePath = "/offering-catalog",
        operationIdPrefix = "fionasOfferings",
        access = OfferingsHttpAccess.ReadWrite(accessControl),
        tags = setOf(Tag("Offerings catalog", "Fiona's primary Offerings catalog.")),
    )
