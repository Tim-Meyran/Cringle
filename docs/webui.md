# WebUI

The ManagementServer serves a web interface next to its gRPC API (Architecture 7.2, 24). Technology (decided by the owner in #151): server-rendered HTML with **htmx**, small client state with **Alpine.js**, the blueprint editor with **Drawflow**. There is **no build step**: the three libraries are committed unchanged under `kotlin/management-server/src/main/resources/web/vendor/` and served by the ManagementServer itself (no CDN).

| Library | Version | License |
|---|---|---|
| htmx | 2.0.4 | 0BSD |
| Alpine.js | 3.14.8 | MIT |
| Drawflow | 0.0.60 | MIT |
| idiomorph (htmx extension `morph`) | 0.8.0 | 0BSD |

To update one: fetch the package from the npm registry (`npm pack <name>@<version>`), copy the file from its `dist/` folder over the one in `vendor/`, and change this table and `NOTICE`.

## Starting it

```
management-server --web-port 8443 [--web-host 127.0.0.1] [--web-url https://host:port] [--bind loopback|all|address] [--auth]
```

Without `--web-port` there is no web interface. The default host is `127.0.0.1`: the interface is reachable from the machine of the ManagementServer only; use `--bind all` (the gRPC port and the web interface listen on all interfaces, see `management-server.md`), `--web-host <address>` for the web interface alone, or a reverse proxy to open it. The start prints `web-port=<port>`. `--web-url <https url>` is the public address for links and QR codes (`https://host[:port]`, no path, query or fragment; refused at start otherwise); without it the `Host` header of the request is used.

The interface is served over **HTTPS (TLS 1.3)** with the identity of the ManagementServer. The certificate is self-signed, so a browser asks once: compare the key shown by the browser's certificate details with the **server key** printed at the start (`fingerprint=...`), shown on the login page and in the footer of every page.

## Login and sessions

- With `--auth` the login page takes a token (the same tokens as the CLI: `cringle user token ...`). A valid token starts a session: a random 256-bit id in the cookie `cringle_session` (`HttpOnly; Secure; SameSite=Strict`), kept in memory (a restart logs everybody out), ended by `POST /logout` or after 8 hours without a request. A failed login is answered after a pause of one second and logged without the token.
- Without `--auth` the interface is open, as the gRPC API is: everybody who can reach it is administrator, and every page says so.
- Every page and action needs a permission (`READ` for pages, `OPERATE`, `MANAGE_USERS`, `ADMINISTER` for actions), the same as the gRPC methods. Navigation entries and buttons that a user may not use are not shown; a request for them is answered with 403. With scoped roles (`docs/users.md`, #271) a page opens when the user has the permission globally or for any scope, lists show only the objects the user may read, buttons appear only for objects the user may act on, and an action on another object answers with a flash `PERMISSION_DENIED`.
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
| Fabrics (a failed migration shows as *migration failed* with step and backup, the *Start* button reads *Retry*; the dashboard lists it) | `GET /fabrics`, `GET /fabrics/list`, `GET /fabrics/{machine}/{engine}/{fabric}` (blocks, last error, the assertions of the blueprint with state and since; the list shows "n violated" next to the state) | `READ` | `POST .../start\|stop\|remove` (`OPERATE`) |

Tags are entered as roles `a, b` and labels `key=value, key2=value2`. Buttons and forms the user may not use are not rendered.

## Pages: deployments, logs, metrics, data warehouse (#209)

| Page | Routes | Permission | Notes |
|---|---|---|---|
| Deployments | `GET /deployments`, `GET /deployments/list` | `READ` | deployed projects with their fabrics and states; deploy form (project from the repository, version range, start, relock) `POST /deployments`, `POST /deployments/{project}/undeploy`, `POST /deployments/{project}/rollback` (button *Roll back* when an earlier version exists); bindings of service dependencies `POST /bindings` (fabrics in order of preference), `POST /bindings/{project}/{service}/unbind` (`OPERATE`) |
| Logs | `GET /logs`, `GET /logs/list?machine&engine&fabric&block&level&minutes&limit` | `READ` | newest at the bottom; entries from the collector are marked `(collected)`, the file of foreign lines is the source column; an Alpine switch refreshes every 5 s |
| Metrics | `GET /metrics`, `GET /metrics/list` | `READ` | one block per engine (CPU, heap, threads); click to expand fabrics, blocks and tethers; refreshes every 5 s (an expanded engine collapses on refresh) |
| Data warehouse | `GET /dwh`, `GET /dwh/list`, `GET /dwh/records?fabric&kind&name&limit` | `READ` | partitions with size and retention, records of a partition; `POST /dwh/recording` (recording on/off with default retention) and `POST /dwh/retention` (`OPERATE`) |

Retention is entered as hours and bytes; empty means no limit.

## Pages: users, groups, tokens (#210)

Only with `--auth`; every route needs `MANAGE_USERS`, and the navigation entries are shown only to users that have it. The functions of `cringle user|group|token`: `GET /users` (users with roles, groups, tokens), `POST /users` (name, roles, groups), `POST /users/{id}/delete` (the last administrator cannot be deleted), `POST /users/{id}/tokens` (label, lifetime in hours; the value is shown **once** in the answer and not kept), `POST /tokens/{id}/revoke`, `GET /groups`, `POST /groups`. A token is rotated by creating a new one and revoking the old one. Changing the groups or roles of an existing user or group is not possible in the CLI either and comes with its own issue.

## Page: Connect

`GET /connect` (Overview, needs `READ`; the machines it shows are the ones the user may read): *This server* (the address of the gRPC port of this management server, built from the `cringle.host` of the machine this server runs on, else from the address of the page, and its key, with the `cringle login` command) and one panel per machine with the daemon, router, management server, web interface and repository: address (`cringle.host:port`), key fingerprint (read by a TLS handshake with the component, 2 s; "not read" if it did not answer), what it is for, and the command that uses it. Every value copies with a click. Notes on the page: which name was taken when `cringle.host` is not set (with the command to set it and a link to *Configuration*), and that a machine listens on the loopback interface only. No polling: the keys are read when the page opens. The form for the address of a router on the page *Trust* points here.

## Page: configuration (#317)

Reading needs `READ`, changing `ADMINISTER`, both for the machine or for the function `config`. `GET /config/{machine}` (and `/list`, which the page polls) shows the settings of the daemon of a machine: key, value in an edit form (`POST /config/{machine}/{key}`, field `value`), the default, whether it is set or an argument of the daemon wins, what a change restarts, and *Reset* (`POST /config/{machine}/{key}/unset`). The answer is a `flash`: what was restarted, or that the daemon has to be restarted itself. Without the right the values are shown without forms. A line of the settings file that cannot be used is shown above the table. See `daemon-service.md`.

## Page: registries of other sites (#295)

Only with `--auth`, every route needs `MANAGE_USERS` (or the `users` function). `GET /registries` and `GET /registries/list` show the trusted registries (name, key fingerprint, roles, scoped roles) and the public key of this registry; `POST /registries` (name, PEM key or certificate, roles) trusts one, `POST /registries/{name}/delete` stops trusting it, `POST /registries/{name}/grant|revoke` give and take scoped roles, `POST /registries/token` (user, known-there-as, hours) issues a federated token, shown once. See `trust.md`.

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

## Projects (#385)

The page **Projects** (group *Build*) manages projects as workspaces. A project is the project draft of the blueprint editor with its files and the schema drafts it names; publishing builds the project package from it. `/projects` lists the projects (version, blocks, files), creates one (a name; it starts with an empty blueprint) and deletes one with its files.

`/projects/{name}` shows the content as a **tree**:

- `project.json`: the version, the number of fabrics and the plugins that the blocks come from.
- `blueprints/`: the blueprint with the number of blocks and tethers and the link *Open in the editor*.
- `schemas/`: the schema drafts of the project with *Open* (the schema editor) and *Remove* (the draft stays), and a select to *Add* another one.
- `binaries/`: the files of the project as a folder tree with size, *Download* and *Delete* (a folder with everything in it), and an **upload**: a file and its path in the project (default the file name, `web/index.html` makes the folders).

*Publish* builds the package from the tree: the blueprint, the schemas and every file as `binaries/<path>`. Files live below `<management home>/drafts/files/<project>/`. A path is relative, written with `/`, every part is letters, digits, `.`, `-` and `_` (no `..`), at most 8 levels and 200 characters; a file is at most 20 MiB, all files of a project 100 MiB. A viewer sees the tree and downloads, and nothing else. Several blueprints in one project, schemas that belong to the project itself and the published versions in the tree are the next slices.

## Drafts and the schema editor (#212)

A **draft** is work in progress that the ManagementServer keeps: one JSON file `<management home>/drafts/<kind>/<name>.json` (`kind` is `schema`, later also `project`), written atomically, with a version for the package it becomes and a **revision** that every save raises. A save that names the revision it started from is refused if the draft moved on (`changed meanwhile`), so two editors do not silently overwrite each other. Names follow the package name grammar; no name can leave the folder.

| Route | Permission | Notes |
|---|---|---|
| `GET /drafts`, `POST /drafts`, `POST /drafts/{kind}/{name}/delete` | `READ`, `OPERATE` | list, create (empty), delete |
| `POST /drafts/schema/{name}/publish` | `OPERATE` | validates the schema document and the package, then publishes a **plugin package** named like the draft that carries the document (`schemas/<namespace>.json`) and nothing else |
| `GET /schemas/{name}` | `READ` | the editor |
| `POST /schemas/{name}/check`, `POST /schemas/{name}/save` | `READ`, `OPERATE` | `check` shows the document and its problems with their path and saves nothing; `save` stores the draft (also an invalid one, as work in progress) |

The editor is a form: Alpine.js (`x-data`) holds the rows (types, fields with a name, a type from `cringle.std/...` or a type of the document, and one of *one value*, `list`, `map`, `optional`; enums with their values). Every change posts the rows to `check`; the **server** turns them into the schema document (`SchemaForm`) and validates it with the schema parser, so the form has no logic of its own. Limits: one wrapper per field (no list of optional values), and a type of another namespace is accepted by the parser but a draft that uses one cannot be published yet (the editor knows no dependencies). Project drafts are published by the blueprint editor (#213).

## The blueprint editor (#213)

`GET /blueprints/{name}` edits a `project` draft (create one on the drafts page with the kind *project*). On top an **action bar**: version, the fabrics (a folded section: one per line, `instances | roles | labels`, for example `2 | edge, gpu | zone=a`; roles and labels are optional, a draft from before reads its `roles` as one fabric), the schemas of the project (a folded section with a checkbox per schema draft; publishing puts their documents into the project package as `schemas/<namespace>.json`), the **plugins** (a folded section: `plugin = version range`, one per line, for example `cringle-std = ~1.9.0`; the editor offers and checks the blocks of the highest version that satisfies the range, a plugin without a line is used in its highest version, the section lists the versions that exist and the one in use; publishing writes the range as the dependency of the project and refuses one that no version satisfies; a change is saved and the editor is read again), the provided services (a folded section; `service=block.port`, optionally `:TYPE`, one per line), *Undo* and *Redo* (50 steps), the zoom (*−*, *+*, *Fit*), *Autosave* (on by default: two seconds after the last change of the graph or of a field the draft is saved if it differs from the last save; a stale revision is refused as with a hand save), *Save draft*, *Publish* (saves, then publishes the draft as a package; `POST /blueprints/{name}/publish`, `OPERATE`, the answer links to the deployments) and the result of the last save or check. Below it the editor fills the height of the window: left the **palette**, the blocks of the plugins in the repository (each plugin in its highest version), grouped under the plugin and, inside it, in folded groups by the theme before the first dot of the block name (`time`, `file`, `text`, …, with the number of blocks), with a search field that shows the matching blocks, opens the groups that have one and hides the others (without a plugin it says so and links to *Packages*); middle: the graph on **Drawflow**, with a hint while it is empty; right: the properties of the selected block (block id, isolation and a **form for its configuration**: the fields of the configuration type of the block, a text, a number (with the bounds of its type), a **checkbox** for a boolean or a **dropdown** for an enum (for example the method of an endpoint) per field, `*` marks a required one, an empty optional field is left out; a field whose type has constraints (schema `string`/`int`/`double`, `spec/schema.md`) shows what a valid value is and, while it is wrong, the problem at the field (the server finds the same problems when the blueprint is saved and checked); a save never moves the canvas: only a click on a problem goes to its block; a list of texts is a field with **one name per line**, and the slots of a port that these names name (for example the variables of `text.format`) follow the list and carry the names; **tethers are drawn by their kind** (solid: message/push, dashed: request/response, dotted: pull/get, thick: stream; the editor takes the first type that both ports support, so an output that is connected to a port that only asks becomes a pull tether by itself; two ports that share several types (a function block that pushes and can be asked) make a push by default, and the properties of the connection have the select *Kind of tether*; a port that can be asked has a dotted ring); a field of another type (list, map, record, `Any`) is a small text area with JSON; the same configuration is shown as JSON in the folded section *Configuration as JSON*, which can also be edited) or of the selected connection (delivery `DROP`/`BUFFER`, record in the data warehouse, buffer capacity, request timeout and, for `BUFFER`, the retry: attempts, delay, growth, longest delay; an empty field means the default). A block has the select *Isolation* (`SHARED`, `PROCESS`; the engine still refuses `PROCESS` for a plugin that is not `trusted`) and, for every VarArg port, the number of its slots: every port carries its **name and its type as a label directly beside the dot** (`in: String`) (inputs at the left, outputs at the right; the tooltip adds the schema, a remote end is labelled `to remote`/`from remote`/`service`) and the node is as high as its ports need; a **generic** block has a select *Type T* per type parameter in its properties (the schemas of the plugins in the repository; *bound* keeps the bound of the parameter) and its ports show the bound type; **a parameter that is not bound yet is inferred when a connection is made**: the port takes the type of the port at the other end (not `Any`), all ports of the same parameter follow, and a refused connection takes the binding back (the select changes it later); the check of a connection and the save use it; the node draws one port per slot (`in[0]`, `in[1]`, …), a tether to a slot is written with its `index`, and a new number draws the node again and keeps the connections whose slot still exists. Saving keeps the retention of a recording. A saved blueprint without a block is stored but is not called valid.

- A node is a block; its inputs are the `IN` ports and its outputs the `OUT` ports of the block definition, in that order. A connection is a tether; its type is the first one that both ports support.
- **Compatibility check on connect:** when two ports are connected the browser asks `POST /blueprints/{name}/check`; the **server** answers with the verdict of the validators that packaging uses (direction, tether type, schema of the two ports) and the browser removes a connection that is refused and shows the reason. The check is repeated for the whole blueprint on save.
- `POST /blueprints/{name}/save` (`OPERATE`) turns the Drawflow export into the blueprint, validates it completely (config against the config schema of each block, and so on) and stores the draft, also an invalid one as work in progress, with the problems listed. A save names the revision it started from; a stale one is refused. The positions of the nodes are kept in the draft as `ui.positions`; the package never sees them.
- **Problems:** a save lists every problem as a readable line: `Block trig (cringle-std/flow.trigger), config: missing required field 'x'` or `Tether a.out → b.in, type: …` (`BlueprintProblems`; a problem that names no block keeps its path). The blocks of the problems are marked in the canvas (a dashed red outline and a `!` in front of the name, so it does not depend on colour); a save without problems clears the marks; the first faulty block is shown in the middle of the canvas. **A click on a problem selects its block, centres it and shows its properties** (a tether problem marks both blocks and goes to the first). A connection that the server refuses marks its two blocks for four seconds.
- Publishing (`POST /drafts/project/{name}/publish`, `OPERATE`) builds a project package: one blueprint named like the draft, one fabric config (instances 1, the given roles), a dependency `^<version>` on every plugin whose blocks are used; the package is validated against the plugins before it is published. Deploy it on the deployments page.
- Deploying (#405, the develop loop): *Deploy* in the action bar (also `Ctrl+Enter`) and *Deploy* on the drafts list save and publish the draft and deploy it in one step (`POST /blueprints/{name}/deploy`, `POST /drafts/project/{name}/deploy`, `OPERATE` on the project). A version that the repository already has is raised by patch until it is free, and the draft keeps the new version (the editor shows it); the project is deployed as `=<version>` with start and blue/green, so a running older version is replaced. The answer names the version and links the monitor of every running fabric.
- **Other engines and services:** below the palette three buttons add an *external end*: a node with one port that stands for a port on another engine (*Remote end that receives*, *Remote end that sends*) or for a service of another project (*Service*). Connect a local port to it like to any port; the tether type is chosen in its properties (`MESSAGE`, `REQUEST_RESPONSE`, `STREAM`, `BYTE_STREAM`) and the check at connect time tests that the local port supports it. A remote end has the key of the other engine (SHA-256), fabric, block, port, optional address and index; a service has its name. On save the node becomes a tether with `remote` or `service` (`spec/package-format.md`), and the server lists wrong fields (a malformed key, an unknown name). The editor cannot know the remote schema, so it is not checked. A tether whose block or port the plugin no longer has is not drawn but **kept as it is** when the blueprint is saved. Not yet possible in the editor: several blueprints in one project (several fabrics are copies of the one blueprint with other placement).

Manual check (the browser part is not covered by the integration tests, which test the server; it was run in headless Chromium with Playwright for #213): start the ManagementServer with `--web-port`, create a project draft, add `src` and `sink` blocks of a plugin, drag from an output to an input: a compatible pair connects and says the tether type; an incompatible one (other schema or no common tether type) disappears with the reason; save shows `Saved as revision n` and `The blueprint is valid`; reload shows the graph again.


## CLI and WebUI side by side (#214)

Every command of the CLI either has a route of the web layer or is named below with the reason. `WebParityTest` reads the commands from the source of the CLI and fails for a new command that is in neither table, and for a route that does not exist.

| CLI | WebUI route (an entry of a page or an action) |
|---|---|
| `cringle login` | `POST /login` |
| `cringle logout` | `POST /logout` |
| `cringle whoami` | `GET /` |
| `cringle machine add` | `POST /machines` |
| `cringle machine list` | `GET /machines` |
| `cringle machine remove` | `POST /machines/m1/remove` |
| `cringle engine create` | `POST /engines` |
| `cringle engine start` | `POST /engines/m1/e1/start` |
| `cringle engine stop` | `POST /engines/m1/e1/stop` |
| `cringle engine delete` | `POST /engines/m1/e1/delete` |
| `cringle engine list` | `GET /engines` |
| `cringle engine status` | `GET /engines/list` |
| `cringle engine tag` | `POST /engines/m1/e1/tags` |
| `cringle fabric list` | `GET /fabrics` |
| `cringle fabric status` | `GET /fabrics/m1/e1/f1` |
| `cringle fabric start` | `POST /fabrics/m1/e1/f1/start` |
| `cringle fabric stop` | `POST /fabrics/m1/e1/f1/stop` |
| `cringle fabric remove` | `POST /fabrics/m1/e1/f1/remove` |
| `cringle deploy` | `POST /deployments` |
| `cringle rollback` | `POST /deployments/p/rollback` |
| `cringle undeploy` | `POST /deployments/p/undeploy` |
| `cringle user grant` | `POST /users/u1/grant` |
| `cringle user revoke` | `POST /users/u1/revoke` |
| `cringle group grant` | `POST /groups/g1/grant` |
| `cringle group revoke` | `POST /groups/g1/revoke` |
| `cringle bind` | `POST /bindings` |
| `cringle unbind` | `POST /bindings/p/s/unbind` |
| `cringle bindings` | `GET /deployments/list` |
| `cringle logs` | `GET /logs/list` |
| `cringle metrics` | `GET /metrics/list` |
| `cringle dwh list` | `GET /dwh/list` |
| `cringle dwh query` | `GET /dwh/records` |
| `cringle dwh record` | `POST /dwh/recording` |
| `cringle debug break` | `POST /debug/break` |
| `cringle debug state` | `GET /debug/list` |
| `cringle debug resume` | `POST /debug/resume` |
| `cringle dwh retention` | `POST /dwh/retention` |
| `cringle router add` | `POST /trust/routers` |
| `cringle router remove` | `POST /trust/routers/remove` |
| `cringle router list` | `GET /trust` |
| `cringle trust list` | `GET /trust/list` |
| `cringle trust add` | `POST /trust/probe` |
| `cringle trust add-component` | `POST /trust/components` |
| `cringle trust revoke` | `POST /trust/revoke` |
| `cringle repo publish` | `POST /packages/upload` |
| `cringle repo list` | `GET /packages` |
| `cringle repo versions` | `GET /packages/list` |
| `cringle repo trust` | `POST /packages/p/trust` |
| `cringle user create` | `POST /users` |
| `cringle user list` | `GET /users` |
| `cringle user delete` | `POST /users/u1/delete` |
| `cringle group create` | `POST /groups` |
| `cringle group list` | `GET /groups` |
| `cringle token create` | `POST /users/u1/tokens` |
| `cringle token list` | `GET /users/list` |
| `cringle token revoke` | `POST /tokens/t1/revoke` |
| `cringle registry key` | `GET /registries` (the key and fingerprint of this registry) |
| `cringle registry trust` | `POST /registries` |
| `cringle registry list` | `GET /registries/list` |
| `cringle registry untrust` | `POST /registries/r1/delete` |
| `cringle registry grant` | `POST /registries/r1/grant` |
| `cringle registry revoke` | `POST /registries/r1/revoke` |
| `cringle registry issue-token` | `POST /registries/token` |
| `cringle config list` | `GET /config/m1/list` |
| `cringle config get` | `GET /config/m1` |
| `cringle config set` | `POST /config/m1/bind` |
| `cringle config unset` | `POST /config/m1/bind/unset` |

Without a page:

| CLI | Why |
|---|---|
| `cringle engine collect` | switches the log collection of a daemon; an operator task of a machine |
| `cringle cache cleanup` | maintenance of the package cache, run by the ManagementServer on a schedule (--cache-max-unused-days) |
| `cringle recover` | runs at the start of the ManagementServer |
| `cringle repo download` | downloads a file; the browser has no use for it |
| `cringle cert status` | reads the certificate files of the local home; no server involved |
| `cringle cert renew` | renews the certificate files of the local home; no server involved |
| `cringle self-update` | updates the installation of the command line tool |
| `cringle setup` | changes the settings file of the installed services on this machine; no server involved |
| `cringle shell` | the interactive mode of the command line tool; the web interface is the interactive way there |

## How the lists refresh (#242)

The lists (`#list`) refresh every 5 seconds, and an action answers with the new list. Two things keep that from getting in the way:

- **Morphing:** the new HTML is not put in place of the old one but merged into it (`hx-swap="morph:innerHTML"`, the htmx extension `morph`, idiomorph). What did not change stays: the DOM element, the focus, the scroll position, the text selection.
- **Waiting while you work:** the polling is `hx-trigger="every 5s [cringleIdle()]"`. `cringleIdle()` (in `app.js`) is false while a field in the list has the focus, a `<details>` in it is open (the `Tags` panel, an engine on the metrics page, the tokens of a user), or a field differs from what it had when it was rendered (text typed, an option or a checkbox changed, a file chosen). Then the refresh is paused without any message (a banner would shift the page); when the field is empty again, closed or submitted, the refresh goes on.

- **Feedback is not part of the list (the rule that fixed the vanishing token):** what an action tells the person (an error, "published", a new token that is shown once, the confirmation form of the next step) is an out-of-band part of the answer, `<div hx-swap-oob="beforeend:#flash">`, built by `flash(error, done, detail, form)` in `PageSupport.kt`. htmx swaps it into `#flash`, a region of the page frame above the page title, and strips it from what goes into `#list`. The polling answer (`GET .../list`) has no such part, so the refresh neither contains nor clears feedback. An item stays until it is closed (the ×); `app.js` keeps the last three plain messages, a result that is shown once (`data-sticky`, for example the token, with a click-to-copy value) and a confirmation form stay until closed or sent. An action that starts while a refresh is in flight aborts it (`htmx:abort`), and the refresh does not start while a request of the user runs, so a late answer of the poll cannot put the old state back. `WebFlashTest` checks this; `.claude/skills/htmx/SKILL.md` explains the htmx mechanisms.

A page that adds a list follows the same rules: build it with `section(...)`, give every `hx-post` the target `#list` and the swap `morph:innerHTML`, put messages into `flash(...)` and never into the list, and keep state of the user in the DOM (fields, `<details>`), not in Alpine state inside the swapped fragment. `WebUiRefreshTest` checks the attributes of the pages; the behaviour in the browser was checked with Playwright (typing, an open `Tags` panel and an expanded engine survive 7 seconds; an idle list still refreshes; an action updates the list).

## Design system (#246)

**Folding the navigation (#402):** the button at the top of the sidebar folds the navigation to its icons (60 px; every link keeps its name as `title`) and back. The class `nav-collapsed` on `<html>` does it, `app.js` sets it from `localStorage` (`cringle.nav`, per browser; without storage the state is not kept), and the editor and the monitor follow because they use `--sidebar`. Below 900 px, where the sidebar is a bar on top, the button is hidden.

There is **one theme: dark**, minimal and carefully made. `app.css` has no light mode and no `prefers-color-scheme` rule (`color-scheme: dark`, so the browser's own controls are dark too); `ThemeTest` checks both and that every text color has at least 4.5:1 (WCAG AA) on every surface it is used on and every badge color on its tint. Everything is a token in `:root`, the components below only use tokens, so changing the look is one place.

| Tokens | Meaning |
|---|---|
| `--bg`, `--surface`, `--surface-2`, `--surface-3` | the page, a panel, a raised or hovered row, a control |
| `--border`, `--border-strong` | dividers, control borders |
| `--text`, `--text-muted`, `--text-faint` | body, secondary, hints and placeholders |
| `--accent`, `--accent-ink` | the one accent (links, the current entry, the primary button) and the text on it |
| `--success`, `--warning`, `--danger`, `--info` (+ `-tint`) | meaning; the tints are the same colors at 14 % for badges and notices |
| `--text-xs…--text-xl`, `--space-1…--space-6`, `--radius`, `--ring` | type scale, spacing scale, radii, the focus ring (keyboard focus is always visible) |

**The shell** (`Layout`): a sidebar with the brand, the navigation in five groups — *Overview* (Dashboard), *Operate* (Machines, Engines, Fabrics, Deployments), *Observe* (Logs, Metrics, Data warehouse), *Build* (Drafts), *Administer* (Users, Groups, Trust, Packages) — and the user (name, role, *Sign out*). A group or entry the user may not open is not shown; the current page has `aria-current="page"` (the editors belong to *Drafts*). Below 900 px the sidebar becomes a bar on top. A page registers its entry with `web.navigation += NavItem(label, path, permission, group, scopes)` (`scopes` for an entry that needs a framework function, such as `function:users`).

**Components** (class names): `page-header` (`pageHeader(title, subtitle, actions)`), `panel`, `cards`/`card`, `data` tables (every table in a list is styled like one), `btn` (`primary`, `ghost`, `danger`, `small`, `block`), `field` (a label above a control), `badge` (`ok`, `warn`, `bad`, `info`; Kotlin: `badge(text, Tone)` and `stateBadge(state)`, the text always says the state, the color only supports it), `notice` (`error`, `info`), `empty` (an empty list says what to do next), `problem` (a page for a status other than 200), `details` for disclosure. A button with `hx-confirm` is drawn as a destructive button.

**Dashboard** (`DashboardPage`): cards for machines, engines, fabrics and deployed projects, a list *Needs attention* (an unreachable machine, a crashed engine or one that should run and does not, a failed fabric or one that should run and does not, each with a link), and the machines. **Sign-in, 403, 404 and 500** are pages in the same style; the favicon is `/static/favicon.svg`.

## Pages in the design system (#247)

Every page is built from the same parts (`PageSupport.kt`, `Layout.kt`), so that a new page looks and behaves like the others:

- `pageHeader(title, subtitle, actions)` (or `section(title, subtitle, name, list)` for a page with a self-refreshing list): the title, one line of help, the actions on the right.
- `dataTable(headers, rows, empty)`: a table; **no rows means an empty state** that says what to do next, never an empty table. An empty header is the column of the buttons; `actionsCell(...)` is the cell of a row (right aligned; buttons the user may not use are not rendered).
- `formPanel(title, hint, form)`: the place where something is created, below the list; it is not rendered for a user who may not create. `field(label, control, hint)` is a labelled input; `form-row` lays fields out in a row, `check` is a checkbox.
- `stateBadge(state)` / `badge(text, Tone)` for every state (engine, fabric, machine, block, trust, log level, kind), `tag` for roles, labels and groups, `fingerprint(value)` for keys (first and last characters, the whole value as the title, a click copies it).
- Destructive buttons (those with `hx-confirm`) are drawn in red; a row with several actions keeps *Tags*, *Retention* and *Tokens* in a popover (`details.popover`), which the refresh respects (it waits while one is open).

## QR codes (#301)

`cringle.common.qr.QrCode` is a small QR encoder (ISO/IEC 18004, byte mode, versions 1 to 40, levels L/M/Q/H, no dependency). `toSvg(label)` gives an `<svg>` without script and style (black on white, a quiet zone of four modules) that fits the content security policy of the WebUI; `toText()` gives the half-block rendering for a terminal, which the CLI uses. The text never leaves the process: no external service draws the code. A token or an invite link is shared by scanning the code from the screen (#302, #303). `QrCodeTest` decodes its own output (unmasking, error correction syndromes, text); the output was also read by an independent decoder (zxing-cpp) for all levels and sizes up to version 40.

### Sharing a token by QR code (#302)

A new token (Users page) is shown, once and sticky in `#flash`, as the value, as a **login link** `https://<server>/login#token=crt_...` and as the QR code of the link. The token is in the *fragment*: a browser never sends it to a server and it lands in no log. `app.js` reads `#token=` on the login page, removes the fragment from the address bar (`history.replaceState`), puts the token into the form and shows a notice; **the person signs in with the button** (nothing is submitted by the script, so a link that someone else sends cannot sign a person in under that someone's account); a wrong token shows the normal error. The link works only on this server; the QR code and the link are as secret as the token, so treat the screen like a password. The base of the link is `--web-url` or the `Host` header. Checked in headless Chromium with Playwright (before the confirmation was added): `/login#token=<valid>` had no hash in the address bar, `/login#token=crt_wrong` showed `The token is not valid.`; the extra click was not rechecked in a browser.

### Invite links (#303)

*Invites* (Administer) creates the link and its QR code as a sticky `flash`, like a new token; the list holds only the state (open, used by, expired, revoked) and *Revoke*. The public pages `GET|POST /invite/{secret}` need no session (and no CSRF token, which only applies to routes with a permission). Redeeming signs the new user in at once: the answer is a redirect to the start page with the session cookie (a user without the right to read gets a welcome page), no second QR code. The token that the redeeming makes lives 10 minutes and is never shown. Checked in headless Chromium with Playwright (before this change): the link opens the form, and the invite link in `#flash` is still there after the 5 s refresh. See `docs/users.md`.

## Page: remote debugger (#323)

`GET /debug` and `GET /debug/list` show the fabrics that have a breakpoint or hold values: the breakpoints (with *Remove*), the held values (tether, sender output, receiver input, since when, the value as JSON, shortened to 4096 characters) and *Step* (release the oldest, the breakpoint stays so the next value is held) and *Resume all*. `POST /debug/break` (fabric, tether, mode `on|off`) sets or removes a breakpoint, `POST /debug/resume` (fabric, tether optional, `one`) releases values. Every route needs `OPERATE` for the fabric. The list holds only state; messages go into `#flash`. See `debugging.md`.

### Monitor (#399)

`GET /monitor` lists the running fabrics, `GET /monitor/{fabric}` shows one on the canvas of the blueprint editor, read only: the border of a block shows its state, a tether is drawn bold while values cross it, dashed with a breakpoint and red with errors. A click on a block shows its statistics (state, restarts, errors, values and bytes in and out, ports), a click on a port its tethers, a click on a tether the last N values (10, 50 or 100) as a table with *Set breakpoint*, *Step* and *Resume all*; values that are held by a breakpoint are listed too. A breakpoint on a block holds the values of all its inputs, one on a port those of its tethers. *Pause* stops the refresh (every 2 s) so that values can be read; opening a long value pauses too. The data comes from `GET /monitor/{fabric}/graph` (JSON, `READ`) and the fragments `block`, `port` and `tether` (the values need `OPERATE`, like the debugger); `POST /monitor/{fabric}/breakpoint` and `/resume` need `OPERATE`. The engine copies the values only while a monitor looks (lease of 60 s, 100 values per tether); the values of TCP and serial tethers are not copied, only the numbers are shown. The view (#401): the graph is fitted into the free area (*Fit*, key `F`), every line carries the values per second (the total while idle) and every block its rates and totals; with nothing selected the details show an overview of the fabric (blocks running, throughput, errors, held values) with a list that jumps to a block; *Find a block* dims the others (Enter selects the first). The toolbar shows *Live - n s ago*, *Pause* (key `Space`; opening a long value pauses, too) and the refresh interval (1, 2 or 5 s); `Esc` clears the selection. The data dock has the tether, chips for type, values, bytes, errors and last activity, a filter box, a table with fixed columns (new rows are highlighted), values that open formatted on a click and a *Copy* button per value.


## Layout of the forms and of the editors (#357)

- A row of fields (`.form-row`) aligns its fields at the top; the button and the checkbox of the row start below the label line (`--label-h`), so a field with a hint under it no longer pushes the other fields out of line. A form that is not a row (the schema editor) is `form.stack`: namespace and version in a row, one card (`.type-card`) per type with a line per field (name, type, how often, remove), the document of the preview in a card of its own.
- **The blueprint editor** is a full-bleed canvas: `#blueprint-editor` fills everything right of the sidebar and the dialogs float above it (`.float`: translucent, blurred, with a shadow): the title and the palette on the left, the toolbar (version, the dropdowns *Fabrics*, *Schemas of the project* and *Provided services*, undo, redo, zoom, autosave, save, publish) at the top, the properties of the selected block or connection on the right (hidden while nothing is selected) and the result of a check or save at the bottom. The graph starts clear of the palette (`canvas_x`, `canvas_y` in `app.js`; *Fit* goes back to it). Under 900 px the editor is a block of its own height and the properties sit at the bottom.

## Dialogs and the second design pass (#360)

- **One dialog per workflow.** Every function that creates or changes something (create a user, a group, an invite, a draft, a machine, an engine; deploy, bind, record; trust a router, a component, a registry; issue a token; publish a package; set a breakpoint; edit a setting; the tokens, the scoped roles, the tags and the retention of a row) opens a modal `<dialog>` from a button above the list (`formPanel` and `rowDialog` in `PageSupport.kt`); there is no form that is always on the page any more, and no dialog for something that needs none (a refresh, a link). A dialog has a title, one line of help, the fields, *Cancel* and the primary button. A request that fails keeps the dialog open with the error in it and what was typed; one that works closes the dialog and the result goes into `#flash` (`app.js`, the `htmx:beforeSwap` handler). While a dialog is open or a field was changed the list does not refresh (`cringleIdle`).
- **Questions** (`hx-confirm`: delete, revoke, undeploy, untrust, stop, publish a blueprint) are asked in a dialog of our own (`cringleConfirm` in `app.js`), not by the browser.
- **Icons** are inline SVG (`Icons.kt`): the navigation and the cards of the dashboard; they follow the text color and need no file.
- **The schema editor** is a form (`schemaEditor` in `app.js`, markup in `SchemaPages.editor`): a card per type (collapsible; a record has a line per field with name, type and how often, an enum has its values as chips), the dialog *New type* (name checked, kind chosen), the types of the schema itself offered next to the standard types, and the document with its problems on the right while you type.

