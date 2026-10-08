# WebUI

The ManagementServer serves a web interface next to its gRPC API (Architecture 7.2, 24). Technology (decided by the owner in #151): server-rendered HTML with **htmx**, small client state with **Alpine.js**, the blueprint editor with **Drawflow**. There is **no build step**: the three libraries are committed unchanged under `kotlin/management-server/src/main/resources/web/vendor/` and served by the ManagementServer itself (no CDN).

| Library | Version | License |
|---|---|---|
| htmx | 2.0.4 | 0BSD |
| Alpine.js | 3.14.8 | MIT |
| Drawflow | 0.0.60 | MIT |

To update one: fetch the package from the npm registry (`npm pack <name>@<version>`), copy the file from its `dist/` folder over the one in `vendor/`, and change this table and `NOTICE`.

## Starting it

```
management-server --web-port 8443 [--web-host 127.0.0.1] [--auth]
```

Without `--web-port` there is no web interface. The default host is `127.0.0.1`: the interface is reachable from the machine of the ManagementServer only; use `--web-host 0.0.0.0` or a reverse proxy to open it. The start prints `web-port=<port>`.

The interface is served over **HTTPS (TLS 1.3)** with the identity of the ManagementServer. The certificate is self-signed, so a browser asks once: compare the key shown by the browser's certificate details with the **server key** printed at the start (`fingerprint=...`), shown on the login page and in the footer of every page.

## Login and sessions

- With `--auth` the login page takes a token (the same tokens as the CLI: `cringle user token ...`). A valid token starts a session: a random 256-bit id in the cookie `cringle_session` (`HttpOnly; Secure; SameSite=Strict`), kept in memory (a restart logs everybody out), ended by `POST /logout` or after 8 hours without a request. A failed login is answered after a pause of one second and logged without the token.
- Without `--auth` the interface is open, as the gRPC API is: everybody who can reach it is administrator, and every page says so.
- Every page and action needs a permission (`READ` for pages, `OPERATE`, `MANAGE_USERS`, `ADMINISTER` for actions), the same as the gRPC methods. Navigation entries and buttons that a user may not use are not shown; a request for them is answered with 403.
- Every `POST` needs the header `X-CSRF-Token` with the token of the session. The pages carry it as `hx-headers` on `<body>`, htmx sends it with every request.

## Security headers

`Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'` (no inline script, no external file). `'unsafe-eval'` is needed by Alpine.js, which evaluates the expressions of `x-data`; the pages contain no script of their own. Also `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, and `Cache-Control: no-store` on HTML.

## How pages are built

Package `cringle.management.web`:

- `Html.kt`: `h("<td>{}</td>", value)` and `html(...)` build HTML; **every dynamic value is escaped**, unless it is itself an `Html`. Do not concatenate strings into markup.
- `Router.kt`: `router.get("/engines/{id}", Permission.READ) { request -> ... }`; the handler returns a `WebResponse` (page, fragment or redirect). `request.isHtmx` tells a fragment request.
- `Layout.page(title, session, content)` wraps the content in the frame; a fragment answer (for `hx-post`) is a bare `Html`.
- `WebServer.navigation` holds the navigation entries (label, path, permission).

To add a page: register its routes on `ManagementServer.web.router` and a `NavItem` in `web.navigation` before `start()`, return `Layout.page(...)` for a full page and an `Html` fragment for htmx requests, show errors of the core (`ManagementException`) inline, and test it with a client like the one in `WebServerTest`.

Static files are served from `/static/...` with an `ETag` (`If-None-Match` gives 304); a path with `..` is 404.

## Pages: machines, engines, fabrics (#208)

The functions of `cringle machine|engine|fabric ...`. Each page is a list that refreshes itself every 5 seconds (`hx-trigger="every 5s"`); an action (`hx-post`) answers with the refreshed list, and an error of the core is shown above it with its gRPC code (for example `ALREADY_EXISTS: machine 'm2' is already known`). Destructive actions ask for confirmation (`hx-confirm`).

| Page | Route | Permission | Actions |
|---|---|---|---|
| Machines | `GET /machines`, `GET /machines/list` | `READ` | add `POST /machines`, remove `POST /machines/{id}/remove` (`ADMINISTER`) |
| Engines | `GET /engines`, `GET /engines/list` | `READ` | create `POST /engines`, `POST /engines/{machine}/{id}/start\|stop\|delete\|tags` (`OPERATE`) |
| Fabrics | `GET /fabrics`, `GET /fabrics/list`, `GET /fabrics/{machine}/{engine}/{fabric}` (blocks, last error) | `READ` | `POST .../start\|stop\|remove` (`OPERATE`) |

Tags are entered as roles `a, b` and labels `key=value, key2=value2`. Buttons and forms the user may not use are not rendered.

## Pages: deployments, logs, metrics, data warehouse (#209)

| Page | Routes | Permission | Notes |
|---|---|---|---|
| Deployments | `GET /deployments`, `GET /deployments/list` | `READ` | deployed projects with their fabrics and states; deploy form (project from the repository, version range, start, relock) `POST /deployments`, `POST /deployments/{project}/undeploy`; bindings of service dependencies `POST /bindings` (fabrics in order of preference), `POST /bindings/{project}/{service}/unbind` (`OPERATE`) |
| Logs | `GET /logs`, `GET /logs/list?machine&engine&fabric&block&level&minutes&limit` | `READ` | newest at the bottom; entries from the collector are marked `(collected)`, the file of foreign lines is the source column; an Alpine switch refreshes every 5 s |
| Metrics | `GET /metrics`, `GET /metrics/list` | `READ` | one block per engine (CPU, heap, threads); click to expand fabrics, blocks and tethers; refreshes every 5 s (an expanded engine collapses on refresh) |
| Data warehouse | `GET /dwh`, `GET /dwh/list`, `GET /dwh/records?fabric&kind&name&limit` | `READ` | partitions with size and retention, records of a partition; `POST /dwh/recording` (recording on/off with default retention) and `POST /dwh/retention` (`OPERATE`) |

Retention is entered as hours and bytes; empty means no limit.

## Pages: users, groups, tokens (#210)

Only with `--auth`; every route needs `MANAGE_USERS`, and the navigation entries are shown only to users that have it. The functions of `cringle user|group|token`: `GET /users` (users with roles, groups, tokens), `POST /users` (name, roles, groups), `POST /users/{id}/delete` (the last administrator cannot be deleted), `POST /users/{id}/tokens` (label, lifetime in hours; the value is shown **once** in the answer and not kept), `POST /tokens/{id}/revoke`, `GET /groups`, `POST /groups`. A token is rotated by creating a new one and revoking the old one. Changing the groups or roles of an existing user or group is not possible in the CLI either and comes with its own issue.

## Pages: trust and packages (#211)

| Page | Routes | Permission | Notes |
|---|---|---|---|
| Trust | `GET /trust`, `GET /trust/list` | `READ` | everything that the ManagementServer and its router trust (`cringle trust list`), the connected routers |
| | `POST /trust/probe` then `POST /trust/routers` | `ADMINISTER` | trusting a router is **two steps** as in the CLI: the first shows the key fingerprint that the router presents (nothing is trusted yet); the operator checks it with the operator of that router and confirms, and the second step connects the router with exactly that fingerprint |
| | `POST /trust/routers/remove`, `POST /trust/components`, `POST /trust/revoke` | `ADMINISTER` | disconnect a router, trust a component (`COMPONENT` or `SERVER`) by fingerprint, revoke (for a router also the engines that came through it) |
| Packages | `GET /packages`, `GET /packages/list` | `READ` | projects and plugins with version, size and the trust status of plugins |
| | `POST /packages/upload` | `OPERATE` | publish a package file (multipart, at most 64 MB); the repository verifies it |
| | `POST /packages/{plugin}/trust` | `ADMINISTER` | `trusted` or `untrusted` for all versions of a plugin |

Deleting a package version is not possible in the repository (and so not here).

## Drafts and the schema editor (#212)

A **draft** is work in progress that the ManagementServer keeps: one JSON file `<management home>/drafts/<kind>/<name>.json` (`kind` is `schema`, later also `project`), written atomically, with a version for the package it becomes and a **revision** that every save raises. A save that names the revision it started from is refused if the draft moved on (`changed meanwhile`), so two editors do not silently overwrite each other. Names follow the package name grammar; no name can leave the folder.

| Route | Permission | Notes |
|---|---|---|
| `GET /drafts`, `POST /drafts`, `POST /drafts/{kind}/{name}/delete` | `READ`, `OPERATE` | list, create (empty), delete |
| `POST /drafts/schema/{name}/publish` | `OPERATE` | validates the schema document and the package, then publishes a **plugin package** named like the draft that carries the document (`schemas/<namespace>.json`) and nothing else |
| `GET /schemas/{name}` | `READ` | the editor |
| `POST /schemas/{name}/check`, `POST /schemas/{name}/save` | `READ`, `OPERATE` | `check` shows the document and its problems with their path and saves nothing; `save` stores the draft (also an invalid one, as work in progress) |

The editor is a form: Alpine.js (`x-data`) holds the rows (types, fields with a name, a type from `cringle.std/...` or a type of the document, and one of *one value*, `list`, `map`, `optional`; enums with their values). Every change posts the rows to `check`; the **server** turns them into the schema document (`SchemaForm`) and validates it with the schema parser, so the form has no logic of its own. Limits: one wrapper per field (no list of optional values), and a type of another namespace is accepted by the parser but a draft that uses one cannot be published yet (the editor knows no dependencies). Project drafts are published by the blueprint editor (#213).
