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
