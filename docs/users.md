# Users, groups and role scopes

Users, groups and tokens live in `UserManager` (`kotlin/router`, `cringle.router.users`), stored by `FileUserStore` as `users.json` (owner-only file). Roles: `ADMIN`, `OPERATOR`, `VIEWER`, `END_USER`; `Permission.of(role)` says what a role may do.

## Scopes (#229, Architecture 6.3)

A role is **global** (the `roles` of a user or group, as before) or **scoped**: given for one object only. A `Scope` is `global`, `machine:<id>`, `project:<name>`, `fabric:<id>` or `function:<name>` with the framework functions `trust` (trust of components), `plugin-trust` and `users`. A `RoleAssignment` is a role plus a scope. Users and groups can have scoped assignments (`User.scoped`, `Group.scoped`); a user has those of its groups too (`UserView.effectiveScoped`).

- **Check:** `UserManager.allowed(user, permission, scopes)` is true if a global role of the user gives the permission, or a scoped role does for one of `scopes`. The caller passes every scope the object lies in: for a fabric its own, its machine and its project. `permissions(user)` stays the global answer. `AUTHENTICATED` is always allowed.
- **Rules:** `END_USER` cannot be scoped (it is not a management role); a global role is not given as a scoped one; granting twice is fine; revoking a role the subject does not have is `NOT_FOUND`.
- **API:** `UserService.GrantRole` / `RevokeRole` (`MANAGE_USERS`), subject a `user_id` or a `group`. `User` and `Group` messages carry `scoped_roles` (and `effective_scoped_roles` for a user).
- **Storage:** an optional `"scoped": [{"role": "OPERATOR", "scope": "machine:m1"}]` in the user or group object; a `users.json` without it loads unchanged.
- **Enforcement in the ManagementServer (#269):** the interceptor in front of a method lets a call in if the caller has the permission globally or for any scope (`allowedAnywhere`); the method then asks `Access` (`ManagementCore.access`) with the scopes of the object. Without `--auth` everything is allowed. A refusal is `PERMISSION_DENIED: insufficient rights for <scopes>`.

  | Call | Scopes that allow it (or the role globally) |
  |---|---|
  | machine and engine calls, `CreateEngine`, `Start|Stop|DeleteEngine`, `SetEngineTags`, `SetLogCollection`, `CleanupCache` (with a machine), reading engines, logs, metrics | `machine:<id>` |
  | `Start|Stop|RemoveFabric`, `GetFabric`, `SetRecording`, `SetDwhRetention`, `QueryDwh`, `ListDwhPartitions` | `fabric:<id>`, the `machine:` it runs on, the `project:` it was deployed from |
  | `DeployFabric` | the `machine:` or the `project:` of the request |
  | `Deploy`, `Rollback`, `Undeploy`, `Bind`, `Unbind`, `ListBindings` | `project:<name>` |
  | remote routers, `ListTrust`, `AddTrustedComponent`, `RemoveTrust` | `function:trust` |
  | `SetPluginTrust` | `function:plugin-trust` |
  | user service (`UserService`) | `function:users` |
  | `AddMachine`, `RemoveMachine`, `Recover`, a list or query without a filter that is not scoped | the role globally |
  | packages of the repository (`PublishPackage`, `ListPackages`, `ListVersions`, `GetPackage`, `DownloadPackage`) | the role globally or for any scope (the repository is not scoped yet) |

  List calls (`ListMachines`, `ListEngines`, `ListFabrics`, `ListBindings`, `ListDwhPartitions`, `GetMetrics`, `QueryLogs`) return only what the caller may read. A role on a machine covers what runs on it; a role on a project covers its fabrics wherever they run; a deploy needs the role for the project only.
- **Grant and revoke (#270):** `cringle user grant|revoke <user-id> <role> --scope <scope>` and `cringle group grant|revoke <group> <role> --scope <scope>` (`user list` and `group list` show `operator@MACHINE:m1`); in the WebUI the *Scoped roles* popover of a user or group (`POST /users/{id}/grant|revoke`, `POST /groups/{name}/grant|revoke`, role, kind and name, or the scope text for a revoke). `--scope global` is refused: a global role is given with `user create --role`.
- **WebUI (#271):** a page opens for a user who has the permission globally or for any scope (the navigation shows an entry on the same rule; Users and Groups need `function:users`); what it shows and does is checked per object with the same scopes as in the table above. Lists, the dashboard, metrics, logs and the data warehouse show only what the user may read; a button appears only for objects the user may act on; an action on another object answers with a flash `PERMISSION_DENIED: insufficient rights for ...` and changes nothing. Pages for the trust of components and of plugins need `function:trust` and `function:plugin-trust`; packages, drafts and the editors need the permission globally or for any scope. A user without any role gets 403 on every page.

## Invite links (#303)

An administrator invites somebody with a **one-time link** (WebUI, page *Invites*; `MANAGE_USERS` for `function:users`). The invite fixes what the new user gets (roles including administrator, groups, scoped roles as `role@scope` lines) and how long the link is valid (24 hours by default, at most 30 days). The link `<base>/invite/inv_...` and its QR code are shown once. Whoever opens it, without a login, names a user; the user is created with exactly what the invite gives, gets one token that does not expire, and sees it with a login link and QR code (as in #302). The invite is used up in the same step; a name that exists is refused and the invite stays valid. An unknown, used, revoked or expired link is the same neutral page (the failure is slowed down like a failed login), the pages are sent with `Cache-Control: no-store` and `Referrer-Policy: no-referrer` because the secret is in the path, and only the SHA-256 hash of the secret is stored in `users.json` (`invites`; an older file loads unchanged). Invites are not in the gRPC `UserService` and have no CLI command yet. `<base>` is `--web-url` or the `Host` header.

**Token as QR code in the terminal (#308).** `cringle token create <user-id> --web-url https://host:port --qr` prints the value, the login link and the QR code of the link (`QrCode.toText()`); the WebUI shows the same (`webui.md`, #302). Treat the terminal like a password: the code holds the token.
