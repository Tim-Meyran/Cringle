# MCP server of the ManagementServer

The ManagementServer has an MCP server (Model Context Protocol, revision 2025-03-26, also 2024-11-05): an AI client can read the state of Cringle and control it completely, with the same rights as the user whose token it uses (#397).

## Connect

The endpoint is `POST https://<host>:<web port>/mcp` of the web interface (default `https://localhost:8443/mcp`). It speaks JSON-RPC 2.0 over Streamable HTTP: one message per `POST`, the answer is the JSON of the response (no event stream; `GET` answers `405`). Sign in with the **token of a user** (`cringle token create <user-id>`) in the header `Authorization: Bearer <token>`; without `--auth` everybody is administrator, as everywhere. The certificate of the server is self-signed: the client has to trust it (the fingerprint of the server key is on the login page and in `cringle login`).

```bash
claude mcp add --transport http cringle https://localhost:8443/mcp --header "Authorization: Bearer <token>"
```

A request with an `Origin` header (a web page) is refused (`403`) and so is one that is not `application/json` (`415`), so that a page in the browser of an administrator cannot control a server without `--auth`.

## Settings

| What | Values | Default |
|---|---|---|
| `--mcp` of the management server, setting `management.mcp` (`cringle config set management.mcp read-only`) | `on`: all tools; `read-only`: only the tools that read, the others are unknown; `off`: no endpoint | `on` |

## Rights

Every tool checks the permission for the object it touches, like the WebUI: reading needs `READ` (for the machine, fabric or project, or globally), changing needs `OPERATE`, machines, trust-relevant settings and `set_config` need `ADMINISTER`. `tools/list` shows a user only the tools the roles allow somewhere; lists are filtered to what the user may see. A refused call is a tool result with `isError` and `PERMISSION_DENIED`. The server does not ask for confirmation: the MCP client asks the user (the write tools carry `readOnlyHint: false`, the removing ones `destructiveHint: true`).

## Tools

Read: `whoami`, `list_machines`, `list_engines`, `list_fabrics`, `get_fabric`, `query_logs`, `get_metrics`, `list_deployments`, `list_bindings`, `list_packages`, `list_blocks`, `dwh_list`, `dwh_query`, `debug_state`, `get_config`, `list_trust`, `list_projects`, `get_blueprint`, `check_blueprint`.

Write: `add_machine`, `remove_machine`, `create_engine`, `start_engine`, `stop_engine`, `delete_engine`, `set_engine_tags`, `start_fabric`, `stop_fabric`, `remove_fabric`, `deploy`, `undeploy`, `rollback`, `bind`, `unbind`, `set_recording`, `set_breakpoint`, `resume_fabric`, `set_config`, `recover`, `create_project`, `save_blueprint`, `publish_project`.

Building an application: `list_blocks` (the blocks with their ports, schemas and type parameters), `create_project`, `save_blueprint` (blocks with `config`, `varArgCounts` and `types`; tethers; a blueprint with problems is stored as work in progress and the problems are returned), `check_blueprint`, `publish_project`, `deploy`, then `get_fabric` and `query_logs` to see it run. The projects are the project drafts of the blueprint editor, so the WebUI shows the same.

Not offered: users, tokens and trust changes (security boundaries of their own: the CLI and the WebUI).
