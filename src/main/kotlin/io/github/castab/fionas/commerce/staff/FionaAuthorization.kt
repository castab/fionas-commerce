package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.Principal
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalResolver
import io.github.castab.commerce.staff.RoleBasedPermissionResolver
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.RoleResolver
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId

/** Current Fiona roles grant only permissions this application actually exposes. */
object FionaRoles : RoleResolver {
    private val administrator =
        RoleDefinition(
            CommerceRoles.Administrator,
            "Administrator",
            "May administer Fiona's offerings catalog",
            setOf(CommercePermissions.OfferingsManage),
        )

    override fun resolve(role: RoleKey): RoleDefinition? = administrator.takeIf { it.key == role }
}

/** Resolves both human and service PrincipalIds; future service credentials use this same path. */
class FionaPrincipalResolver(
    private val transactor: Transactor,
    private val users: StaffRepository,
) : PrincipalResolver {
    override fun resolve(principalId: PrincipalId): Principal? =
        transactor.inTransaction { transaction ->
            when (principalId) {
                is UserId -> users.findUser(transaction, principalId)
                is ServiceId -> users.findService(transaction, principalId)
            }
        }
}

class GetCurrentUser(
    private val transactor: Transactor,
    private val users: StaffRepository,
) {
    fun invoke(id: UserId): User? = transactor.inTransaction { users.findUser(it, id) }
}

fun fionaPermissionResolver(
    transactor: Transactor,
    users: StaffRepository,
) = RoleBasedPermissionResolver(FionaPrincipalResolver(transactor, users), FionaRoles)
