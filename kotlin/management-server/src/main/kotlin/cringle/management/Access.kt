// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.router.users.AuthInterceptor
import cringle.router.users.Permission
import cringle.router.users.Scope
import cringle.router.users.ScopeKind
import cringle.router.users.UserManager
import io.grpc.Status

/**
 * Checks a permission against the object a call touches (#269, Architecture 6.3). The interceptor in front of a method only asks whether the caller has
 * the permission somewhere; here the scopes of the object are known. A role assigned for one of the scopes, or a global role, allows it. A server without user
 * management (`--auth` not given) allows everything, as before. The caller is `AuthInterceptor.CURRENT_USER` of the gRPC call; web pages pass their user.
 */
public class Access(private val core: ManagementCore, private val users: UserManager?) {
    private fun scope(kind: ScopeKind, name: String?): List<Scope> =
        if (name.isNullOrEmpty()) emptyList() else runCatching { listOf(Scope(kind, name)) }.getOrDefault(emptyList())

    /** The scopes of machine [id]. */
    public fun machine(id: String?): List<Scope> = scope(ScopeKind.MACHINE, id)

    /** The scopes of project [name]. */
    public fun project(name: String?): List<Scope> = scope(ScopeKind.PROJECT, name)

    /** The framework function [name]: `trust`, `plugin-trust` or `users`. */
    public fun function(name: String): List<Scope> = scope(ScopeKind.FUNCTION, name)

    /** The scopes of a fabric: itself, the machine it runs on and its project (none for a fabric that was not deployed from a project). */
    public fun fabric(machine: String?, fabricId: String?): List<Scope> =
        scope(ScopeKind.FABRIC, fabricId) + machine(machine) + project(core.projectOfFabric(fabricId))

    /** The scopes of a fabric that is known by its id only; empty if it is unknown. */
    public fun fabricById(fabricId: String?): List<Scope> {
        val machine = core.machineOfFabric(fabricId)
        return fabric(machine, fabricId)
    }

    /** Whether the caller may do [permission] to an object in [scopes]; an empty list asks for the permission globally. */
    public fun allowed(permission: Permission, scopes: List<Scope>, user: cringle.contract.AuthenticatedUser? = AuthInterceptor.CURRENT_USER.get()): Boolean =
        users == null || user == null || users.allowed(user, permission, scopes)

    /** Like [allowed], but a refusal is a [ManagementException] with `PERMISSION_DENIED`. */
    public fun require(permission: Permission, scopes: List<Scope>, user: cringle.contract.AuthenticatedUser? = AuthInterceptor.CURRENT_USER.get()) {
        if (!allowed(permission, scopes, user)) {
            val where = if (scopes.isEmpty()) "this operation (it needs the role globally)" else scopes.joinToString(" or ")
            throw ManagementException(Status.Code.PERMISSION_DENIED, "insufficient rights for $where")
        }
    }

    /** The caller has [permission] globally or for any object (used where nothing is scoped yet, such as the packages of the repository). */
    public fun requireAnywhere(permission: Permission, user: cringle.contract.AuthenticatedUser? = AuthInterceptor.CURRENT_USER.get()) {
        if (users != null && user != null && !users.allowedAnywhere(user, permission)) {
            throw ManagementException(Status.Code.PERMISSION_DENIED, "insufficient rights")
        }
    }
}
