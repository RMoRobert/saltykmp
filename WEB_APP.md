# The Salty web app

The web UI at **`/app`**, built with **Web Awesome 3** (web components) and **Alpine 3**, served by the
Ktor server and driven entirely by the JSON API the native clients already use.

No build step. No npm. `./gradlew run` remains the only toolchain.

## Try it

```bash
SALTY_ALLOW_DEFAULT_SECRET=true ./gradlew :server:run
```

Then <http://localhost:8080>, sign in (default `admin` / `changeit`). `/` and `/editor` both redirect
here.

## The URL map

| Path | What it is |
| --- | --- |
| `/login` | Sign in. Web Awesome, but built from **native** `<input>`/`<button>` so it still works if the CDN is unreachable — the one page where "unstyled" must not mean "locked out". |
| `/` | Redirect to `/app` (behind auth, so an anonymous visitor meets `/login` directly). |
| `/app` | The app. Recipes, shopping lists, library, account, users, about. |
| `/app#/…` | The open dialog: `#/library`, `#/import`, `#/preferences`, `#/users`, `#/about`. Reload-safe and Back-closes. |
| `/editor` | 301 to `/app`. It was this page's address while it was an experiment. |
| `/classic/…` | The old Pico-styled, mostly view-only pages. **Legacy** — see below. |

## Two menus in the header

Split by what they act on, which is what the single menu got wrong:

- **Gear** — this installation: Manage library…, Classic view, About…
- **Person** — your account: who you are, Preferences…, Manage users… (admins), Log out.

**Preferences** is one page, not three menu items. Chef mode's wake-lock switch, changing your
password and the list of apps authorized to sync are all "settings about me", and splitting them
across a menu meant knowing which item held the thing you were looking for before you looked. They
are sections in one scroll: three short things read faster stacked than behind two clicks, and it
puts the password warning on the same screen as the list of apps it is warning you about — which is
the arrangement that makes the warning mean something. Managing *other people's* accounts stays its
own item; that is administration, not a preference.

## The classic view

Everything server-rendered lives under `/classic` and nothing else does. That is the entire point of
the prefix: retiring it is deleting `route("/classic")` in `web/WebRoutes.kt` plus its templates, not
auditing which flat URL belonged to whom. Its nav says "Classic" and links back to the app.

**Before it can go**, the app needs parity on the freeform shopping-list editor and its conflict
banner (`templates/shoppingListDetail.mustache`), which are the only things `/classic` does that the
app has not been checked against.

## Where a new "create" action goes

One control creates a thing, and it is the **`+` at the top of the list column** — for recipes and
for shopping lists alike. The recipe overflow menu (`⋯`) used to duplicate it; it no longer does, and
what is left there is deliberately only the action that should be a little hard to reach by accident.

So, for the actions likely to come next:

- **Additive and reversible** (duplicate a recipe, a `.saltyRecipe` file, a third list shape) belongs
  on the `+`, which is why both `+` buttons are **split buttons**: main click = the common case,
  caret = the full set. Recipes' menu holds *New recipe* and *Import from web…*; shopping lists'
  holds *Checklist* and *Markdown list*. A `.saltyRecipe` import is another way of making a recipe,
  so it goes in the recipe menu — not a third top-level button.
- **Destructive or rare** (delete, archive, export-and-remove) belongs in the `⋯` menu.

The split button's menu deliberately lists *Checklist* as well as *Markdown list*, even though the
main button already makes a checklist: the menu has to read as the complete set of things the button
makes, not as a list of leftovers.

## Web import

Paste a recipe page's address; the server reads the schema.org JSON-LD most recipe sites publish and
hands back a **draft**, which opens in the editor. Nothing is written until the user saves it, so a
mis-pasted URL costs nothing — the same contract the desktop import has.

The parsing is the shared `SchemaOrgRecipeParser`, untouched, so a page imports identically here and
on the phone. The *fetching* is not shared, and that is the whole design:

**A browser may not read a third-party page cross-origin, so the server has to fetch it — and a
server that fetches a URL a user typed is an SSRF primitive.** The same code that loads a recipe blog
will just as happily load `http://169.254.169.254/` (cloud metadata, and with it the instance's
credentials) or something bound to loopback that is unauthenticated precisely because it is
unreachable. `RecipeWebImporter`'s safety — scheme allowlist, size caps, clamped fields — is right for
a phone, where the request comes from the user's own machine and reaches only what they could reach
anyway. `recipe/RecipeImport.kt` adds what only matters server-side:

- every hostname is resolved before it is fetched and refused unless **every** address it resolves to
  is public — no loopback, link-local, RFC 1918, carrier-grade NAT or IPv6 unique-local;
- **redirects are not followed automatically.** They are walked by hand, five hops at most, with the
  same check on every hop, because a public URL is free to redirect to an internal one and an
  auto-following client would follow it without ever being asked;
- the body is read through a hard byte cap rather than trusted to be small.

The endpoint is session-only (`WEB_API_AUTH` + CSRF). Native clients import locally and have no use
for it, so a sync credential cannot make the server fetch anything.

**Known residual risk:** the address is checked and the host is then connected to *by name*, so a
hostile DNS server answering differently for the two lookups (DNS rebinding) could still land one
request. Closing it means pinning the checked IP into the connection and carrying the hostname in
Host/SNI by hand. Worth revisiting if this server ever hosts accounts its operator doesn't trust.

`AddressPolicy` is injectable purely so the round trip can be tested against a page served on
loopback — which the real policy exists to refuse. Production never passes anything but the real one,
and there is deliberately **no environment variable** that changes it: a deployment that can turn
this off by configuration is one where it will eventually be off.

## Account and user management

Both used to be Pico pages (`/users`, `/devices`) driven by form POSTs. They are JSON now
(`web/AccountRoutes.kt`), because the app has to show the result of a write without a page
navigation, and a redirect carrying `?error=lastadmin` cannot do that. The guards moved across
unchanged: last-admin, self-delete, minimum password length.

**Changing your own password** (`POST /api/account/password`) is new — there was previously no way to
do it at all. Three things about it are deliberate:

1. **It requires the current password.** The session cookie is an ambient credential; without this a
   borrowed browser is a full account takeover rather than a session you can revoke. Failures feed
   the same `AccountLockout` counter `/login` uses.
2. **It signs every device out**, matching what an admin reset already did. The dialog says so
   before you commit, because someone with three synced devices should not learn this afterwards.
   Signing out clears tokens but keeps the device rows and their sync watermarks, so devices resume
   where they left off once they sign back in — unlike removing an app, which deletes the row and
   makes that app's next sync a first sync. The count reported back is devices that actually held a
   token, not rows touched.
3. **It re-mints the caller's own session cookie.** `revalidateSession` rejects any cookie issued
   before the user's `passwordChangedAt` — which, after this call, includes the cookie that
   authenticated the call. Without the re-mint, changing your password logs you out of the tab you
   changed it in. The new `issuedAt` is read back from the stored timestamp rather than `now()`, so a
   same-second write cannot leave the fresh cookie one second stale.

`revalidateSession` also refreshes `isAdmin` from the user row rather than trusting the cookie's copy,
so an admin who demotes themselves loses access on their next request instead of their next sign-in.

## Why this stack

Web components rather than a framework's component library: they're themed with ordinary CSS custom
properties, they work from server-rendered HTML with no framework, and they survive a later move to
Tauri or a SPA. Alpine supplies local reactive state — reorder, star toggles, live scaling — which is
what this screen actually needs; the data all comes from the JSON API, so there's nothing for a
fragment-swapping library like htmx to do here.

Web Awesome is MIT for 50+ components. Everything here is in the free tier; the Pro line sits almost
entirely on marketing and ecommerce page patterns.

## What changed on the server

Mostly one thing: **the JSON API accepts the web session cookie as well as a Bearer device sync token**, so the
browser reuses the endpoints the native clients use instead of growing a parallel set of form-POST
routes that would duplicate the write rules.

The exception, and the highest-risk new server code on the branch, is
**`shoppinglist/ShoppingListResolve.kt`** — read that first. It exists because the browser has no
local database and therefore no `syncedSnapshot` to merge against, so it sends the copy it loaded as
the base and the server runs the shared merge on its behalf.

- **`WEB_API_AUTH`** (`auth/Auth.kt`) — a second session provider over the same cookie. It exists
  because `WEB_AUTH` answers an unauthenticated request with a redirect to `/login`, which is right
  for a page navigation and wrong for `fetch`.
- **`ApplicationCall.userId()`** resolved the Bearer principal alone and would have thrown on every
  cookie-authenticated call. It now resolves either principal; all 36 call sites are unchanged.
- **`ApiCsrfGuard`** (`auth/ApiCsrf.kt`) requires `X-CSRF-Token` on cookie-authenticated writes. Bearer
  callers are exempt — a Bearer token is not an ambient credential. It hooks Ktor's `AuthenticationChecked`
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

**`label` renders visibly** — with `with-label`. Prefer it: it is the only way to get a label that
matches the others, since it is styled from `--wa-form-control-label-*` and nothing else reproduces
that by accident. `wa-rating` is the one control with no visible label of its own (its `label` is for
screen readers), so `.field__label` rebuilds one from the same custom properties. For an accessible
name without a visible label, use `aria-label`.

**The base stylesheet indents `<li>` by 1.125rem** for prose lists, and a class on the `<ul>` setting
`padding: 0` does not undo it. The ingredient and direction rows zero it on the `li` itself.

**Icons need no Font Awesome kit.** `<wa-icon name="plus">` and the components' internal icons both
resolve from the CDN build.

**`wa-dialog` does not reliably close when you flip `open` from outside.** Its close path is
`await animateWithClass(el, "hide")`, and in 3.12.0 the shadow stylesheet defines `show-dialog`,
`show-backdrop` and `pulse` keyframes — and nothing for hide. That await never settles. Closing via a
click happens to leave the dialog off screen anyway; clearing an Alpine flag from a `popstate`
handler leaves it **visible**, with both `show` and `hide` classes on it. So every programmatic close
goes through the component's own `requestClose()` (`dismissDialog()` in `app.js`), which is the call
its close button makes. `wa-hide` fires synchronously from it, so the handler still owns the state
and the URL.

**`wa-hide` fires when a dialog closes for *any* reason, including because a different one opened.**
With one shared `dialog` field, the outgoing dialog's hide event closed its replacement in the same
tick. Each handler names itself — `closeDialog('users')` — and returns unless it is still the open one.

**Alpine's `x-model` does not work on `wa-checkbox`.** Alpine picks its checkbox binding off
`el.type`, which a custom element does not have, so it binds `.value` instead. Use
`:checked` + `@change="… = $event.target.checked"`, as the rest of this page does.

**`wa-page` is a document layout: it scrolls as a whole.** Its docs say independently scrolling
regions are "custom CSS" territory, and this is what that means in practice. `wa-page { height:
100dvh }` is not enough — the shadow grid rows inside it are `auto`, so the main region grows to its
content and the *window* scrolls. `.rail`, `.list__scroll` and `.doc` sat there with `overflow-y:
auto` and nothing to overflow: with twenty recipes, scrolling the list dragged the search box and
the open recipe off screen with it. The fix is four `::part` rules — pin `base`, let `body`/`main`
shrink, and give `main`'s middle row `minmax(0, 1fr)` so it can size below its content. The
`menu` part already sets its own `overflow: auto`, so the sidebar needed nothing.

**`wa-split-panel`'s `start`/`end` slots are `display: contents`.** The assigned elements are the
grid items, not the slots, so the two `<section>`s sharing each slot work only because `x-show` puts
`display: none` on the inactive one — an element that is `display: none` is not a grid item at all.
Un-hide both and four items go into three tracks. It also writes its divider position as an inline
`grid-template-columns: clamp(...)` on the host, which is why the compact override is `!important`.
Its divider arrives as `role="separator"`, `tabindex="0"` and `aria-valuenow`, so arrow-key resizing
is the component's, not ours.

**`native.css` puts `white-space: nowrap` on every `<button>`, and a list row is one.** So a long
recipe name did not wrap, it *overflowed*, and the column scrolled sideways to chase it. Ellipsing
needs `overflow: hidden` + `text-overflow: ellipsis` spelled out on the name; do not lean on the
inherited `nowrap`. The same sheet centres button contents, which a `display: block` row never
reveals and a `display: flex` one does immediately — hence `justify-content: start` on the
shopping-list rows.

**Web Awesome's prose styles give every `<li>` an 18px inline-start margin.** Resetting the `<ul>`
is not enough; the rows sat 26px off the left edge of the scroller against 8px on the right, and the
indent lived on an element with no styling of its own. `.list__rows > li { margin: 0 }`.

**`--menu-width` is the *max* of a `minmax(0, …)` track**, so a plain length still lets the rail size
to its labels and merely caps it — and `fit-content(15rem)`, which says that outright, is invalid
there. An invalid value makes the whole `grid-template-columns` invalid-at-computed-value-time and
the grid falls back to auto-placed implicit columns that happen to look right, so it fails silently.
It is also scoped `wa-page[view="desktop"]`: a custom property set from the outer document beats the
component's own `:host` rules, and unscoped it held the menu track open at 240px in mobile view,
where wa-page collapses that track itself — leaving one 180px pane on a 420px phone.

**UUIDv7 is monotonic within a millisecond**, matching `swift-uuidv7` on the Apple clients. The bare
spec only orders ids across milliseconds, so a burst minted in one tick would sort randomly — which
would defeat the reason Salty uses v7. The sequence counter lives in the 12 `rand_a` bits.

## What it does

Account: change your own password, see the apps authorized to sync — signing them all out, or
removing one outright — and (for admins)
create, promote, reset and delete users — all as dialogs over the recipe list, each addressable by
URL hash so a reload puts you back where you were.

Shopping lists: both shapes. A **checklist** (item rows, check-offs, drag-to-reorder) or a
**Markdown list** (a plain-text scratch pad the phone and desktop apps render). The split button in
the list column makes a checklist on the main click and offers both from its menu.

Recipes: list, open, create, delete, and **import from a web page** — paste a URL, and the recipe
opens in the editor for review. See below; it is the one feature here that needed real server work. Edit name, ingredients, directions and details. Section headings,
main-ingredient stars, **drag-to-reorder** (`@alpinejs/sort`), live fraction-aware scaling, rating,
and `<wa-input type="date">` for "last made on" — it forwards the type to a native input, so the
platform picker comes along and the field still matches the labelled boxes around it. Dialog for delete, toasts for feedback, unsaved-changes guards on navigate and
unload. Follows the OS light/dark setting (`prefers-color-scheme`, applied before first paint to
avoid a flash) — no in-app toggle.

**Chef mode** is the reading view with the app taken away: the header, the library rail, the recipe
list and the detail bar all go, the recipe gets the whole window, and ingredients and directions
come up to 1.5x the theme's body size with the hanging indents and the step-number column scaled to
match. It is reached from a recipe you are reading and left by the one button it leaves on screen or
by Escape. Nothing of the recipe itself is hidden — "how much butter" is the question you are
standing there with — and it is deliberately neither remembered nor in the URL: it says "I am
cooking right now", not "this is how I like the app".

While it is open it holds a **screen wake lock**, so the screen does not dim four steps into a
recipe. Three things about that are deliberate. It is best effort and silent: the request is refused
when the tab is not visible, when the browser is saving power, and everywhere the API is missing, and
none of those is worth interrupting someone mid-recipe about. It is re-taken on `visibilitychange`,
because the platform drops the lock whenever the page stops being visible and never hands it back —
ducking out to a timer app and returning must not leave the screen dark. And the preference that
governs it lives in `localStorage` beside the pane width rather than on the server, because "keep
this screen on" is a fact about the tablet propped against the toaster, not about the person: the
same account on a laptop wants the opposite. It is on by default and one switch away in Preferences.
Screen Wake Lock is secure-context only, so a Salty reached over plain `http` on the LAN does not
have it at all — Preferences says so in the switch's hint rather than offering a control that
silently does nothing.

The Exit button is inset a full `--wa-space-m` from the corner and left at wa-button's medium size:
it is the only control in a mode meant for a screen you prod with a floury knuckle, and a small
button flush against the window edge is the hardest thing on a touchscreen to hit.

Two things make it work and neither is visible in the markup. Hiding wa-page's rail is not enough on
its own: the body grid sizes its menu track as `minmax(0, var(--menu-width))`, and an empty track
with a fixed maximum still takes free space up to it, so the recipe kept a 15rem gutter with nothing
in it until `--menu-width` went to zero as well. And the type scale is *not* an override of
`--wa-font-size-scale`: that token is consumed where the scale is defined, at `:root`, so a copy set
further down the tree changes nothing. The sizes are set on this app's own reading-view elements,
which are plain HTML, so no Web Awesome component is being fought.

The line under a recipe's name — course, yield, servings, rating — is **built as a list and joined
once**. Four templates each carrying their own " · " printed a separator for every field, present or
not, so a recipe with no yield read "Main ·  · Serves 6"; and an empty line now goes away rather than
leaving a gap above the introduction. Categories and tags are no longer drawn as a row of outlined
boxes over the recipe: they are how you *find* a recipe, not something to read first in one you have
already opened.

The list/recipe divider is **draggable**, `primary="start"` so the list keeps its width when the
window changes and the recipe takes the rest, bounded by `--min: 220px` / `--max: min(520px, 50%)`
and snapping back to the old fixed 280px. The width is remembered in `localStorage` per browser.
Below the 900px pane breakpoint the divider is hidden and the single visible pane takes the window.

The rail reads as sections: **Shopping Lists is a heading with "All Lists" under it**, the same
shape Categories, Courses and Tags have. The heading is a `data-group`, so it expands and collapses
and never navigates — the child is the destination. The lists themselves are deliberately not
enumerated in the rail; the middle column is where they live, with their item counts.

Long names are **clipped, with a tooltip on hover and only where there is something to reveal** —
recipe names, their subtitles, shopping-list names and the rail's classifier labels. Clipping is
visual: the full text stays in the DOM, so a screen reader was never missing any of it. The rail is
capped at 15rem so one long classifier name can't take half the window.

The recipe and shopping-list columns are **one tab stop each**, not one per row: the selected row
carries `tabindex="0"` and the arrows (plus Home/End) move between rows. They move focus, not the
selection — selection-follows-focus would fetch a recipe on every keypress — so Enter opens, which is
the row being a real `<button>`. The rows are a real `<ul role="list">`; `list-style: none` drops
list semantics in Safari, and those semantics are what announce "5 of 47". Both scrollers fade at
their cut-off edges, sized to their own padding so a row only dims while it is genuinely half-hidden.

`lastPrepared` is always written together with `lastModifiedPreparedDate`, because the server merges
that pair by the stamp rather than the body clock.

Saving spreads the loaded recipe, so fields this screen doesn't edit — `imageFilename`,
`lastModifiedImageDate`, `categoryIds`, `tagIds`, `notes`, `variations`, `preparationTimes`,
`nutrition`, `courseId` — round-trip untouched. `PUT` replaces the row, so dropping any would wipe them.

## Not done

- **No conflict handling for recipes.** Recipe saves remain last-writer-wins on `lastModifiedDate`,
  by choice. Shopping lists do resolve conflicts: a 409 sends `{base, local}` to
  `/api/shoppingLists/{id}/resolve`, which runs the same shared `ShoppingListMerge` the native
  clients run, so a check-off on one device and an edit on another both survive.
- **No reordering for notes, variations or preparation times.** Ingredients and directions can be
  dragged or moved with the keyboard; the three secondary lists append and delete only.
- **CDN, not vendored.** The page needs network for Web Awesome and Alpine. Vendoring is copying
  files into `static/vendor/` and editing the URLs in `app.mustache` and `login.mustache`. `/login`
  degrades to a working unstyled form; `/app` does not degrade at all.
- **No web app manifest yet.** `static/favicon.svg` is a placeholder — the salt shaker emoji, drawn
  by the viewer's emoji font. An installable app ("Add to Home Screen") needs real 192px and 512px
  PNGs; Chrome's install prompt will not accept an SVG icon alone. That is the one thing waiting on
  a real app icon.
- **Chef mode does not follow along.** No current-step highlighting and no step-at-a-time paging —
  it is the same scrolling document, just larger, alone and awake. The Swift app's highlighting is
  the obvious next step.
- **Categories and tags are not visible in the reading view.** They were a chip row above the
  recipe and are not replaced; the rail and the editor are where they show now. If they turn out to
  be worth reading, quiet text in the recipe's footer is the place for them, not boxes at the top.
- **The classic view is not yet removable.** See the parity note above.
- **No `.saltyRecipe` file import.** The menu it belongs in exists; the format handling does not.
- **Web import reads JSON-LD only.** A site that renders its recipe in the browser, or publishes
  microdata rather than JSON-LD, imports as "no recipe data found" — same as the native clients.
- **No Markdown preview.** The text area shows source, as the classic page did. Rendering it here
  means picking an editor (EasyMDE is the candidate noted in the classic template) and vendoring it.

## Tests

`WebApiAuthTest` — session cookie reads the API; unauthenticated calls get 401 JSON rather than a
login redirect; a cookie write without a CSRF token is rejected *and changes nothing*; with the token
it succeeds; Bearer still works and is exempt; `/app` requires auth; `/editor` still redirects to it;
the page carries the token.

`RecipeImportTest` — the address policy refuses loopback, link-local, RFC 1918 and CGNAT by name and
by number; an internal address is refused **without the request being made at all**; a redirect
pointing inward is refused at the hop; only http/https is accepted; the endpoint needs a session and
a CSRF token; a page with JSON-LD comes back as a draft with a blank id and **nothing is saved**.

`AccountRoutesTest` — the password change needs the current password, a CSRF header and a long enough
new one; a successful change replaces the password, signs every device out, **and leaves the caller
signed in**; non-admins are refused user administration; the last admin can be neither demoted nor
deleted; a demoted admin is locked out on the next request; an admin reset revokes that user's
devices; deleting a user takes their recipes.

`ShoppingListResolveTest` — the merge endpoint: a check-off and an edit to a different item both
survive; additions from both sides are unioned; unmergeable freeform text is preserved as a saved
conflict copy; the no-base two-way degrade; 404; and CSRF.

`EditorUiTest` — Playwright drives the app at `/app` in a real browser. Chef mode is in here for the
gutter above: the assertion that the recipe starts at x=0 and is as wide as the window is the only
thing that catches an empty grid track holding space open. The wake lock is tested against a stub
installed before the page loads, not against the real API — that one is refused in headless Chromium
and absent over plain http, so asserting on it would be asserting on the browser's mood; what is
checked is Salty's half of the contract (take one lock, give it back, and ask for none at all once
the preference is off, across a reload). Includes both list shapes:
the split-button menu creates a Markdown list, and editing one saves its text **without** writing an
empty checklist over a column the server leaves NULL. These earn their keep because every UI defect
found while building this screen was plainly visible on screen and invisible in the code. They are
**skipped, not passed**, when no browser can be launched.

Run the suite with `./gradlew :server:test`. The count changes often enough that quoting it here
only creates another thing to go stale.
