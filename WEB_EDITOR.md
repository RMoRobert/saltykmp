# Web recipe editor (branch: `web-editor`)

A recipe editor at `/editor`, built with **Web Awesome 3** (web components) and **Alpine 3**, served by
the existing Ktor server and driven entirely by the JSON API the native clients already use.

No build step. No npm. `./gradlew run` remains the only toolchain.

## Try it

```bash
SALTY_ALLOW_DEFAULT_SECRET=true ./gradlew :server:run
```

Then <http://localhost:8080/editor>, sign in (default `admin` / `changeit`). There's an **Editor** link
in the nav for signed-in users. Sync and the existing server-rendered pages are untouched.

## Why this stack

Web components rather than a framework's component library: they're themed with ordinary CSS custom
properties, they work from server-rendered HTML with no framework, and they survive a later move to
Tauri or a SPA. Alpine supplies local reactive state — reorder, star toggles, live scaling — which is
what this screen actually needs; the data all comes from the JSON API, so there's nothing for a
fragment-swapping library like htmx to do here.

Web Awesome is MIT for 50+ components. Everything this editor uses is in the free tier; the Pro line
sits almost entirely on marketing and ecommerce page patterns.

## What changed on the server

Only one thing, and it predates this UI: **the JSON API now accepts the web session cookie as well as
a Bearer JWT**, so the browser reuses the endpoints the native clients use instead of growing a
parallel set of form-POST routes that would duplicate the write rules.

- **`WEB_API_AUTH`** (`auth/Auth.kt`) — a second session provider over the same cookie. It exists
  because `WEB_AUTH` answers an unauthenticated request with a redirect to `/login`, which is right
  for a page navigation and wrong for `fetch`.
- **`ApplicationCall.userId()`** resolved `principal<JWTPrincipal>()!!` and would have thrown on every
  cookie-authenticated call. It now resolves either principal; all 36 call sites are unchanged.
- **`ApiCsrfGuard`** (`auth/ApiCsrf.kt`) requires `X-CSRF-Token` on cookie-authenticated writes. Bearer
  callers are exempt — a JWT is not an ambient credential. It hooks Ktor's `AuthenticationChecked`
  rather than `onCall`, because an `onCall` observer responds *alongside* the handler instead of
  short-circuiting it, which let the write land anyway.

## Things worth knowing (each one cost an hour)

**Use `dist-cdn`, not `dist`.** The `dist/` build has bare module specifiers a browser can't resolve
without a bundler; it fails with `Failed to resolve module specifier "@shoelace-style/..."` and no
component ever upgrades. `dist-cdn/` is the bundled build.

**`x-model` works — no workaround needed.** Shoelace 2 fired only `sl-input`/`sl-change`, which broke
Alpine's two-way binding and is what most search results still describe. Web Awesome 3 emits standard
`input` and `change` alongside its `wa-*` events. Verified in both directions.

**Don't write `x-init="init()"` when `x-data` already has an `init()`.** Alpine calls it automatically;
declaring both runs it twice and races two `loadList()` calls.

**`appearance` only accepts `filled | outlined | filled-outlined`.** `appearance="none"` is silently
ignored. Borderless rows come from styling the exposed `::part(base)` instead.

**`label` renders visibly.** For an accessible name without a visible label, use `aria-label`.

**Icons need no Font Awesome kit.** `<wa-icon name="plus">` and the components' internal icons both
resolve from the CDN build.

**UUIDv7 is monotonic within a millisecond**, matching `swift-uuidv7` on the Apple clients. The bare
spec only orders ids across milliseconds, so a burst minted in one tick would sort randomly — which
would defeat the reason Salty uses v7. The sequence counter lives in the 12 `rand_a` bits.

## What it does

List, open, create, delete. Edit name, ingredients, directions and details. Section headings,
main-ingredient stars, **drag-to-reorder** (`@alpinejs/sort`), live fraction-aware scaling, rating,
and a native `<input type="date">` for "last made on" — one field, and the platform picker beats any
library component. Dialog for delete, toasts for feedback, unsaved-changes guards on navigate and
unload.

`lastPrepared` is always written together with `lastModifiedPreparedDate`, because the server merges
that pair by the stamp rather than the body clock.

Saving spreads the loaded recipe, so fields this screen doesn't edit — `imageFilename`,
`lastModifiedImageDate`, `categoryIds`, `tagIds`, `notes`, `variations`, `preparationTimes`,
`nutrition`, `courseId` — round-trip untouched. `PUT` replaces the row, so dropping any would wipe them.

## Not done

- **No Playwright tests.** I intended to add them and ran out of room. This is the biggest gap: the
  server flows are covered by `WebApiAuthTest`, but the UI was verified by driving a real browser by
  hand. Every UI bug found today (invalid `appearance`, visible labels, centred rows, double init) was
  visible on screen and invisible in the code — that's exactly what Playwright would catch.
  `com.microsoft.playwright:playwright` is a Gradle test dependency; it needs no npm.
- **No image upload, category/tag editing, notes/variations/nutrition editing.** Preserved on save,
  not exposed.
- **No conflict handling.** Recipe saves are last-writer-wins on `lastModifiedDate`. Shopping lists
  have revision-based optimistic concurrency; recipes don't, and this UI adds none.
- **CDN, not vendored.** The page needs network for Web Awesome and Alpine. Vendoring is copying files
  into `static/vendor/` and editing three URLs.
- **Light theme only** in practice — WA ships a dark theme, but I haven't checked Salty's overrides
  against it.

## Tests

`WebApiAuthTest` — 7 tests: session cookie reads the API; unauthenticated API calls get 401 JSON not a
login redirect; a cookie write without a CSRF token is rejected *and changes nothing*; with the token
it succeeds; Bearer still works and is exempt; `/editor` requires auth; the page carries the token.

Full server suite: **73 tests, 0 failures.**
