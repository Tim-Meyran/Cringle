# Users, groups and role scopes

Users, groups and tokens live in `UserManager` (`kotlin/router`, `cringle.router.users`), stored by `FileUserStore` as `users.json` (owner-only file). Roles: `ADMIN`, `OPERATOR`, `VIEWER`, `END_USER`; `Permission.of(role)` says what a role may do.

## Scopes (#229, Architecture 6.3)

A role is **global** (the `roles` of a user or group, as before) or **scoped**: given for one object only. A `Scope` is `global`, `machine:<id>`, `project:<name>`, `fabric:<id>` or `function:<name>` with the framework functions `trust` (trust of components), `plugin-trust` and `users`. A `RoleAssignment` is a role plus a scope. Users and groups can have scoped assignments (`User.scoped`, `Group.scoped`); a user has those of its groups too (`UserView.effectiveScoped`).

- **Check:** `UserManager.allowed(user, permission, scopes)` is true if a global role of the user gives the permission, or a scoped role does for one of `scopes`. The caller passes every scope the object lies in: for a fabric its own, its machine and its project. `permissions(user)` stays the global answer. `AUTHENTICATED` is always allowed.
- **Rules:** `END_USER` cannot be scoped (it is not a management role); a global role is not given as a scoped one; granting twice is fine; revoking a role the subject does not have is `NOT_FOUND`.
- **API:** `UserService.GrantRole` / `RevokeRole` (`MANAGE_USERS`), subject a `user_id` or a `group`. `User` and `Group` messages carry `scoped_roles` (and `effective_scoped_roles` for a user).
- **Storage:** an optional `"scoped": [{"role": "OPERATOR", "scope": "machine:m1"}]` in the user or group object; a `users.json` without it loads unchanged.
- **Not yet:** the ManagementServer, the CLI and the WebUI do not look at scopes (#230); today they check the global roles only.
