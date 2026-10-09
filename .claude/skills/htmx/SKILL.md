---
name: htmx
description: How htmx 2.x works and how the Cringle WebUI uses it. Use it before you write or change anything in kotlin/management-server/.../web (pages, fragments, hx-* attributes, app.js), and whenever a page loses what the user typed or was shown after a refresh, a swap does the wrong thing, or you add polling, forms, buttons, out-of-band updates, events or extensions.
---

# htmx in Cringle

Vendored version: **htmx 2.0.4** (`kotlin/management-server/src/main/resources/web/vendor/htmx.min.js`), with the idiomorph extension (`hx-ext="morph"` on `<body>`), Alpine.js 3 for client state, no build step. Source of this skill: the htmx documentation of v2.0.4 (`docs.md`, `reference.md`, `events.md`, `attributes/*`; `htmx.org` is not reachable from the cloud sandbox, but `https://raw.githubusercontent.com/bigskysoftware/htmx/v2.0.4/www/content/<file>` is). The server renders HTML; htmx only moves it. Read `docs/webui.md` for the pages.

## The three rules of this project (they exist because we broke them)

1. **A region that polls holds only state, never events.** `#list` refreshes itself every 5 s and the swap *replaces everything in it*. Whatever the server told a person after an action (error, "saved", a new token that is shown once, the confirm form of a next step) must not be in `#list`: it is gone with the next poll. Such feedback goes into `#flash`, in the page frame outside `#list`, as an **out-of-band** part of the answer: use `flash(error, done, detail, form)` from `PageSupport.kt`. The poll passes no message, so its answer has no out-of-band part and htmx leaves `#flash` alone.
2. **A user action beats a refresh.** `app.js` aborts the refresh in flight when a non-GET request starts (`htmx:abort`), and `cringleIdle()` is false while a request runs, a field has focus, a `<details>` is open or a field was edited. Do not add a second polling element with its own rules; use `section(...)`.
3. **Never put a secret or a one-time value into an attribute or a place that is re-fetched.** One-time values are `flash(detail = ...)` (sticky until closed). A GET that can be repeated must not contain them.

If you add a page with a list: `section(title, subtitle, name, initial)` + `GET /<name>/list` returning `fragment(list(...))`; a POST handler returns `fragment(html(list(...)))` with `flash(...)` inside it, targeted at `#list` with `hx-swap="morph:innerHTML"`. `WebFlashTest` fails if a `.../list` answer holds a `notice`, an alert or an out-of-band part, and if a page does not have exactly one `#flash` before `#list`.

## Core model (htmx 2.x)

An element with an `hx-<verb>` attribute (`hx-get`, `hx-post`, `hx-put`, `hx-patch`, `hx-delete`) issues an AJAX request when its **trigger** fires, and swaps the HTML response into its **target** with a **swap** style.

- **Trigger** `hx-trigger` (defaults: `click`; `change` for input/select/textarea; `submit` for form). Syntax: `event [filter] modifier modifier`, comma separated list. Modifiers: `once`, `changed`, `delay:1s` (debounce), `throttle:1s`, `from:<selector>` (`document`, `window`, `closest x`, `find x`, `next`, `previous`), `target:<selector>`, `consume`, `queue:first|last|all|none`. Filter: `click[ctrlKey]`, JavaScript expression in brackets (needs eval; our CSP allows it). Special: `load`, `revealed`, `intersect`, polling `every 5s` with the filter **after** it: `every 5s [cringleIdle()]`. A custom event sent with the `HX-Trigger` header needs `from:body`.
- **Target** `hx-target`: CSS selector, or `this`, `closest <sel>`, `find <sel>`, `next`, `previous`, `body`. Inherited. Default: the element itself.
- **Swap** `hx-swap` (default `innerHTML`): `innerHTML`, `outerHTML`, `beforebegin`, `afterbegin`, `beforeend`, `afterend`, `delete`, `none`; modifiers `swap:100ms`, `settle:100ms`, `scroll:top`, `show:top`, `transition:true`, `focus-scroll:true`, `ignoreTitle:true`. With the extension: `morph:innerHTML` / `morph:outerHTML` (idiomorph keeps focus, scroll, open `<details>`, input values of nodes that match; it is what makes our refresh gentle, it does not make it safe: see rule 1).
- **Values**: a form sends its fields; a non-form element sends the enclosing form's fields only for non-GET; add with `hx-include="<sel>"`, `hx-vals='{"a":1}'` (JSON, or `js:` expression), `hx-params`. Headers: `hx-headers` (our CSRF token sits on `<body hx-headers=...>` and is inherited by every request). File upload: `hx-encoding="multipart/form-data"`.
- **Confirm** `hx-confirm="Sure?"` (a browser dialog). **Indicator** `hx-indicator` (class `htmx-request` on the element, or the indicator), our CSP forbids the injected style: `includeIndicatorStyles=false` is set in the page.
- **Inheritance**: most attributes are inherited by children; stop with `hx-disinherit`, `hx-inherit`. `hx-target`, `hx-swap`, `hx-trigger`'s modifiers, `hx-headers`, `hx-sync` are inherited. `hx-on`, `hx-preserve`, `hx-swap-oob` are not.

## Out-of-band swaps (`hx-swap-oob`) — the tool for feedback

Top-level elements of a response with `hx-swap-oob` are swapped somewhere else than the target and are removed from what goes into the target.

- `hx-swap-oob="true"` (= `outerHTML`): replace the element that has the same `id`.
- `hx-swap-oob="<swap>"` or `"<swap>:<css selector>"`: e.g. `beforeend:#flash` appends the **content** of the element (its tags are stripped, except for `outerHTML`) to every match of the selector. We use `<div hx-swap-oob="beforeend:#flash">…items…</div>`.
- Table parts (`<tr>`, `<td>`, …) and `<li>` cannot stand alone in HTML: wrap them in `<template>…</template>` (or a `<table>`/`<tbody>`/`<ul>` for `beforeend:`). SVG needs `<template><svg>`.
- By default an OOB element nested anywhere in the response is processed (`htmx.config.allowNestedOobSwaps`); do not nest a fragment that is also an OOB target inside a larger fragment that is swapped as main content.
- `hx-select-oob="#a,#b:afterbegin"` picks parts of a response for OOB swaps. Events: `htmx:oobBeforeSwap`, `htmx:oobAfterSwap` (target element, used by `app.js` to trim old flash items), `htmx:oobErrorNoTarget`.
- The target of an OOB swap must exist at the time of the response. `#flash` is in the frame (`Layout.page`), so every page that has the frame has it. A full page response (not an htmx request) does **not** process OOB: never return a `flash(...)` as part of a full page; use an inline message there.

## Keeping state across swaps

- `hx-preserve` (needs a stable `id`; the element is kept as it is while an ancestor is swapped; not inherited). Does not work well for text inputs (focus and caret are lost) or iframes; **morph** is the better tool for inputs.
- Morph (idiomorph) with matching nodes keeps most state; give elements stable `id`s so they match.
- Do not swap what the user is working in: gate the poll (`cringleIdle`). Never use `hx-swap="none"` for a response that contains `hx-preserve` elements.

## Synchronising requests

- `hx-sync="<selector>:<strategy>"` (inherited): `drop` (ignore this request if one is in flight; default), `abort` (like drop, and abort this one if another starts), `replace` (abort the one in flight), `queue first|last|all`. Example: `hx-sync="closest form:abort"` on an input that validates.
- Programmatic: `htmx.trigger(el, 'htmx:abort')` aborts the request of `el` (used in `app.js` to let a user action win over a refresh).
- Debounce a text input: `hx-trigger="input changed delay:400ms"` + `hx-sync="this:replace"`.

## Responses, status codes and headers

- 2xx and 3xx are swapped; `204` does nothing; 4xx/5xx are **not** swapped and fire `htmx:responseError` (our `app.js` shows a short message). To show a validation error with a 4xx, answer 200 (our pages do) or configure `htmx.config.responseHandling`.
- Response headers: `HX-Trigger` (client event, JSON for details; listen with `from:body`), `HX-Trigger-After-Swap|Settle`, `HX-Retarget` (CSS selector), `HX-Reswap`, `HX-Reselect`, `HX-Redirect`, `HX-Location`, `HX-Refresh: true`, `HX-Push-Url`, `HX-Replace-Url`. A 3xx redirect hides these headers from htmx (the browser follows it): use 200 + `HX-Redirect`.
- Request headers: `HX-Request: true`, `HX-Target`, `HX-Trigger`, `HX-Trigger-Name`, `HX-Current-URL`, `HX-Boosted`, `HX-Prompt`. A server can tell a fragment request from a page request by `HX-Request`.
- No Post/Redirect/Get needed: answer a POST with the new fragment.

## Events (all bubble; listen on `document`/`body`)

`htmx:configRequest` (change parameters/headers), `htmx:beforeRequest` (cancel with `preventDefault`; `detail.requestConfig.verb`), `htmx:afterRequest` (`detail.successful`, `detail.elt`), `htmx:beforeSwap` (change `detail.shouldSwap`, `detail.target`), `htmx:afterSwap`, `htmx:afterSettle`, `htmx:load` (new content ready; init 3rd-party code here), `htmx:responseError`, `htmx:sendError`, `htmx:abort` (**send** it), `htmx:confirm`. Event names with a colon cannot be used in `hx-on:` attributes in the dashed form: use `hx-on::after-request` (shorthand for `htmx:`) or `hx-on:htmx:after-request`.

## Other attributes worth knowing

`hx-boost` (turn links and forms into AJAX), `hx-push-url`, `hx-select` (pick part of the response), `hx-ext`, `hx-disable` (htmx ignores the subtree: use it for user-supplied HTML), `hx-disabled-elt`, `hx-history="false"`, `hx-request='{"timeout":3000}'`, `hx-validate`, `hx-on:<event>`. Alpine (`x-data`, `@click`) is initialised on swapped-in content by its own mutation observer.

## Security

Escape every user value (the `h("...{}...", x)` template escapes; `raw()` does not). CSP of the WebUI: `script-src 'self' 'unsafe-eval'` (Alpine and htmx filters evaluate expressions), no inline scripts, no inline styles: handlers are `@click`, `hx-on:`, or code in `app.js`. A CSRF token is sent as `X-CSRF-Token` on every non-GET request (`hx-headers` on `<body>`). Same-origin only; `htmx.config.selfRequestsOnly` is the default.

## How to check a change

1. `./gradlew :management-server:integrationTest --tests '*Web*'` (see the gradle skill). `WebFlashTest` is the guard of rule 1.
2. A refresh problem only shows in a browser. Use Playwright (`/opt/pw-browsers/chromium`, module in `/opt/node-tools/node_modules/playwright`) against a `WebServer` started by a test or by `./gradlew run`, create the feedback, wait 6 s, and assert that `#flash` still holds it. `docs/webui.md` describes how the editors were checked.
3. When unsure what htmx does, read the vendored source `htmx.min.js` or fetch the doc page named above; do not guess an attribute.
