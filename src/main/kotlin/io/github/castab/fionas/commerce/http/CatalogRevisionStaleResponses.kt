package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.offering.CatalogRevisionStale
import io.github.castab.fionas.commerce.offering.STAFF_CATALOG_REVISION_STALE_MESSAGE
import org.http4k.contract.RouteMetaDsl
import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with

private val catalogConflictBody = jsonBody(ErrorResponse.serializer())

/** Only pricing staleness has a local code; other conflicts retain runtime handling. */
internal val catalogRevisionStaleResponses =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: CommerceFailure.Conflict) {
                if (failure.cause !is CatalogRevisionStale) throw failure
                Response(Status.CONFLICT)
                    .with(catalogConflictBody of ErrorResponse(CATALOG_REVISION_STALE, failure.message!!))
                    .header("Cache-Control", "no-store")
            }
        }
    }

internal fun RouteMetaDsl.catalogRevisionConflict(extra: String = "") =
    returning(
        Status.CONFLICT,
        catalogConflictBody to ErrorResponse(CATALOG_REVISION_STALE, STAFF_CATALOG_REVISION_STALE_MESSAGE),
        "`CATALOG_REVISION_STALE`: the submitted catalog revision is older than current. Reload the catalog and " +
            "review selections before pricing again. This response has no-store.$extra",
    )
