package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.runtime.session.SessionToken
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.http.FionaAuthRoutes
import io.github.castab.fionas.commerce.http.UiApiKey
import org.http4k.core.Filter
import org.http4k.core.NoOp

/** Contract metadata inspection never invokes an authentication operation. */
val metadataAuth =
    FionaAuthRoutes(
        sessions =
            object : SessionManager {
                override fun create(principalId: PrincipalId): IssuedSession = error("not called")

                override fun create(
                    transaction: Transaction,
                    principalId: PrincipalId,
                ): IssuedSession = error("not called")

                override fun resolve(token: SessionToken): PrincipalId? = error("not called")

                override fun revoke(token: SessionToken): Unit = error("not called")

                override fun revoke(
                    transaction: Transaction,
                    token: SessionToken,
                ): Unit = error("not called")

                override fun revokeAll(principalId: PrincipalId): Unit = error("not called")

                override fun revokeAll(
                    transaction: Transaction,
                    principalId: PrincipalId,
                ): Unit = error("not called")
            },
        cookie = SessionCookie("__Host-fionas_session"),
        access = AccessControl(Filter.NoOp, PermissionResolver { emptySet() }),
        origin = Filter.NoOp,
        uiApiKey = UiApiKey(TEST_UI_API_KEY),
    )
