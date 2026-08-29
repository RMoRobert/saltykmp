# Web recipe editor (branch: `web-editor-vuetify`)

A Vue 3 + Vuetify recipe editor at `/editor`, served by the existing Ktor server and driven entirely
by the JSON API the native clients already use.

## Try it

```bash
SALTY_ALLOW_DEFAULT_SECRET=true PORT=8080 ./gradlew :server:run
```

Then open <http://localhost:8080/editor> and sign in (default `admin` / `changeit`). There is also an
**Editor** link in the nav for signed-in users. Sync and the existing server-rendered pages are
unchanged — this is added alongside them, not in place of them.

## What changed, and why

### 1. The JSON API now accepts the web session as well as a Bearer token

Previously `/api/*` was JWT-only, so a browser UI had two options: mint and store a JWT in the page
(worse than the existing httpOnly cookie), or grow a parallel set of form-POST endpoints beside the
API. The second is how the shopping-list web UI works today, and it means the same write rules get
implemented twice and drift.

Instead the API routes are now `authenticate(JWT_AUTH, WEB_API_AUTH)`. Three supporting changes:

- **`WEB_API_AUTH`** (`auth/Auth.kt`) is a second session provider over the *same* cookie. It exists
  only because `WEB_AUTH` answers an unauthenticated request with a redirect to `/login`, which is
  right for a page navigation and wrong for `fetch` — an expired session would hand JavaScript a 200
  full of HTML instead of a 401 it can act on.
- **`ApplicationCall.userId()`** resolved `principal<JWTPrincipal>()!!`, which would have thrown on
  every cookie-authenticated call. It now resolves either principal. All 36 call sites are unchanged.
- **`ApiCsrfGuard`** (`auth/ApiCsrf.kt`) requires `X-CSRF-Token` on state-changing API calls that were
  authenticated by *cookie*. Bearer callers are skipped: a JWT is not an ambient credential, so there
  is nothing to forge. This is defence in depth — `SALTY_SESSION` is already `SameSite=Strict` — and
  it mirrors the hidden form field the HTML routes use.

  The guard hangs off Ktor's `AuthenticationChecked` hook rather than `onCall`, because an `onCall`
  observer responds *alongside* the route handler instead of short-circuiting it, which let the write
  through. `WebApiAuthTest` covers exactly that: it asserts the rejected write left the row untouched,
  not merely that the response was 403.

### 2. `/editor` serves a shell; the page does the rest

`web/EditorRoutes.kt` renders `templates/editor.mustache`, which carries no recipe data — only the
session's CSRF token. Everything else is fetched and written over the API.

The Vue markup lives in `static/app/editor.html`, deliberately *not* in a `.mustache` file: Mustache
and Vue both use `{{ }}`, so a Vue template inside a Mustache template gets mangled before the browser
sees it. The app fetches it at boot.

### 3. Front-end libraries are vendored, not npm'd

`static/vendor/` holds Vue 3, Vuetify 3.13.2 and a ~7 KB extract of MDI SVG path data (instead of a
~1 MB icon webfont). See the README there. This keeps `./gradlew run` as the only build step and keeps
the server working offline.

**This is the main thing to push back on if you disagree.** Vuetify's docs, examples and tree-shaking
all assume Vite + single-file components. Vendoring works and costs nothing at your deployment size,
but you write in-DOM templates and translate every doc example as you go. If the editor grows beyond
this screen, adding Vite and serving its output from `resources/static` is likely worth it.

## What it does

Loads the recipe list, opens a recipe, edits name/ingredients/directions/details, reorders rows, marks
main ingredients, scales quantities with fraction formatting, creates and deletes recipes. Dialog for
delete confirmation, snackbar for feedback, unsaved-changes guard on navigation and page unload.

New ids are UUIDv7, uppercase, matching what the Swift and KMP clients mint — lowercase would neither
sort nor compare correctly under SQLite's binary collation.

Saving spreads the loaded recipe, so fields this screen doesn't edit — `imageFilename`,
`lastModifiedImageDate`, `lastPrepared`/`lastModifiedPreparedDate`, `categoryIds`, `tagIds`, `notes`,
`variations`, `preparationTimes`, `nutrition` — round-trip untouched. `PUT` replaces the row, so
dropping any of them would silently wipe them.

## What it does not do yet

- **No image upload.** The API endpoints exist (`POST /api/recipes/{id}/image`); the UI doesn't use them.
- **No category/tag/course editing.** `categoryIds`/`tagIds` round-trip but aren't editable here.
- **No notes, variations, preparation times or nutrition editing.** Same: preserved, not exposed.
- **No conflict handling.** A save is last-writer-wins against `lastModifiedDate`. The shopping-list
  routes have proper revision-based optimistic concurrency; recipes don't, and this UI doesn't add any.
- **No drag-to-reorder** — up/down buttons instead, which are keyboard- and touch-accessible without a
  drag library.
- **Not tested in a real browser.** The server flows are covered end to end by tests and by curl
  against a running instance, and the Vue template was compile-checked and mounted under jsdom — but
  nobody has clicked it in Chrome or Safari yet. Do that first.

## Tests

`server/src/test/kotlin/.../WebApiAuthTest.kt` — 7 tests covering: session cookie can read the API;
unauthenticated API calls get 401 JSON rather than a login redirect; a cookie write without a CSRF
token is rejected *and changes nothing*; a cookie write with the token succeeds; Bearer tokens still
work and are exempt from the CSRF guard; `/editor` requires auth; the page carries the CSRF token.

Full server suite: **73 tests, 0 failures.**

## Backing out

Nothing outside `/editor`, `/static/app`, `/static/vendor` and the auth changes was touched. Reverting
the six modified files restores JWT-only API auth; the rest is additive.
