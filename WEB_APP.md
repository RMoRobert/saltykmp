# The Salty web app

The web UI at **`/app`**, built with **React 19** and **Fluent UI React v9**, bundled by Vite,
served by the Ktor server and driven entirely by the JSON API the native clients already use.

It replaced an Alpine + Web Awesome app that did the same job. That app is gone; the originals
are still readable in git history (`git log --all -- server/src/main/resources/static/app`), and the
reasons for the change are under *Why this stack* below.

## Try it

```bash
SALTY_ALLOW_DEFAULT_SECRET=true ./gradlew :server:run
```

Then <http://localhost:8080>, sign in (default `admin` / `changeit`). `/` and `/editor` both redirect
here. Gradle builds the bundle on its way to `processResources` (`npmInstall` and `buildWebapp` in
`server/build.gradle.kts`), so there is no separate front-end step to remember; `npm install` warm
is half a second, `vite build` about two, and Gradle skips both when nothing changed.

For front-end work the faster loop is Vite's, with the API proxied to a running Ktor:

```bash
cd server/src/main/webapp && npm run dev
```

That serves <http://localhost:5173/static/app/> with hot reload and forwards `/api`, `/login` and
`/classic` to `localhost:8080`. Sign in on port 8080 first — the session cookie is what the proxied
API calls use.

## What is here

```
server/src/main/webapp/
  src/api.js                      fetch wrapper: CSRF on writes, 401 → /login, server error text, versioned image URLs
  src/theme.js                    Salty's brand ramp, and why it is not simply Salty's blue
  src/model.js                    ids, wire timestamps, ingredient scaling, sorting, display rules
  src/hooks.js                    addressable dialogs, the wake lock, the unload guard, storage, media queries
  src/App.jsx                     shell, data loading, panes, the three-column layout
  src/components/ConfirmDialog.jsx     the app's confirm() and prompt(), as one Fluent dialog
  src/components/NavRail.jsx           the library, as a Fluent NavDrawer; Edit classifiers/Settings/account at the foot
  src/components/RecipeList.jsx        search, the list's ⋯ menu (sort included), rows in three densities
  src/components/RecipeDetail.jsx      the read view, including ingredient scaling and chef mode
  src/components/RecipeInfoDialog.jsx  Get info: a recipe's dates, read only
  src/components/LastMadeDialog.jsx    "Set date…" from the recipe's Last prepared menu
  src/components/RecipeEditor.jsx      the full editor
  src/components/ShoppingListPane.jsx  the lists index and a list's items
  src/components/dialogs.jsx           Settings (with About), Users, Edit classifiers, Import from web
```

`src/model.js` is a straight port of the Alpine app's rules rather than a rewrite — UUIDv7 minted
client-side, the `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` wire format, the fraction table that turns a scaled
`1/2` into `3/4` instead of `0.75`, the sort comparators, and never-made recipes sorting last under
"Last made" in both directions. Those all have to keep agreeing with the Swift and Compose clients,
so none of them were re-derived.

The bundle is served from the classpath at `/static/app/salty.js`, which is why `vite.config.js`
sets `base` to that path and fixes the output filenames: the Mustache shell references them by
name, so it never has to be regenerated, and the server sends no far-future `Cache-Control` for
`/static`, so a reload picks changes up anyway.

## The URL map

| Path | What it is |
| --- | --- |
| `/login` | Sign in. Hand-written CSS using Fluent 2's own values, native `<input>`/`<button>`, no JavaScript — the one page a signed-out visitor can reach depends on nothing loading. |
| `/` | Redirect to `/app` (behind auth, so an anonymous visitor meets `/login` directly). |
| `/app` | The app. Recipes, shopping lists, classifiers, settings, users. |
| `/app#/…` | The open dialog: `#/library`, `#/import`, `#/preferences`, `#/users`. Reload-safe and Back-closes — and closing it any other way pops the entry Back would have, so the button never has to be pressed twice to leave. |
| `/editor` | 301 to `/app`. It was this page's address while it was an experiment. |
| `/classic/…` | The old Pico-styled, mostly view-only pages. **Legacy** — see below. |

Dialogs are addressable and a recipe is not, so a reload lands on the empty state. `react-router`
is what would change that, and it would be a real improvement rather than parity.

## The rail

The library is a Fluent **NavDrawer**: inline at full width, where it is the layout's first column,
and an overlay when compact, where it slides over the one pane there is room for. The same tree
either way — only the drawer's `type` changes. Selection is the drawer's own `selectedValue`; every
destination is one string (`all`, `favorites`, `category:<id>`, `list:<id>`, …), which is what the
drawer trades in, and NavRail turns the chosen string back into the app's own callbacks.

Two things about the shape are deliberate.

- **Categories expand; they never navigate.** That is NavDrawer's rule, and it is why Shopping
  Lists has **All lists** as the first item under it, above the lists themselves: without a
  destination of its own there would be no route to the lists index, and you could only reach a
  list you could already name.
- **Closed is gone, and a Hamburger outside the drawer brings it back.** That is Fluent's own
  NavDrawer pattern, and here the hamburger sits at the head of the list column at every width —
  the same control whether the rail is a closed column or a compact-mode drawer. WinUI's
  NavigationView has a compact icon-rail mode; the web NavDrawer has no such thing, and an icon
  strip built by hand to imitate it (which is what the rail had first) would have been the one
  part of the rail that was not Fluent's.

Along the bottom: **Edit classifiers** and **Settings**, then a divider and the **account menu**
(Manage users… for admins, Classic view, Log out). Edit classifiers sits there rather than as an
"Edit…" hung off each of the three groups above, for the reason the Compose app gives in its own
drawer: editing classifiers is rare and app-level, so it is one row, and the recipe list's toolbar
is for actions on the list. The account's actions are kept apart from the library's because signing
out and administering users are not things you do to a recipe collection.

**Settings** is one page, not three menu items. Chef mode's wake-lock switch, changing your password
and the list of apps authorized to sync are all "settings about me", and splitting them across a
menu meant knowing which item held the thing you were looking for before you looked. They are
sections in one scroll, which is what Microsoft's own
[app-settings guidance](https://learn.microsoft.com/en-us/windows/apps/design/app-settings/guidelines-for-app-settings)
asks for: "present content from top to bottom in a single column, scrollable if necessary". The same
guidance puts **About** at the bottom of the settings page, collapsed — "app information that isn't
accessed very often, such as privacy policy, help, app version, or copyright info" — so About is a
collapsed `Accordion`, v9's equivalent of the `SettingsExpander` it names, at the end of Settings
rather than a top-level rail entry. Managing *other people's* accounts stays its own dialog; that is
administration, not a preference.

## The classic view

Everything server-rendered lives under `/classic` and nothing else does. That is the entire point of
the prefix: retiring it is deleting `route("/classic")` in `web/WebRoutes.kt` plus its templates, not
auditing which flat URL belonged to whom. Its nav says "Classic" and links back to the app.

**Before it can go**, the app needs parity on the freeform shopping-list editor and its conflict
banner (`templates/shoppingListDetail.mustache`), which are the only things `/classic` does that the
app has not been checked against.

## Where a new "create" action goes

One control creates a thing, and it is the **`+` at the top of the list column** — for recipes and
for shopping lists alike. The recipe overflow menu (`⋯`) beside it holds the ways in that are not
that one button: web import, Select mode, and the ordering (see below). Everything the list column
can do to itself is behind that one glyph, because a toolbar of three anonymous icons over a list is
harder to read at a glance than a `+` and a `⋯`.

Both ways of making a recipe open a **draft** — the blank one from `+` and the one the web import
hands back. Nothing is written until Save, so Cancel leaves no trace. It used to create the row
first and open what came back, which made Cancel a lie: the dialog closed and a "New Recipe" stayed
in the library for someone to notice and delete later.

A draft that has never been saved has nothing on the server behind it, so the unsaved-changes guard
treats an **untouched import as dirty** — it has content, and clicking another recipe would lose
it — while an untouched blank draft is not worth a prompt. `RecipeEditor` owns that decision; App
only reads it.

Having nothing behind it also means a draft cannot outlive the pane it was opened in. Choosing a
library filter or the shopping lists **drops** it, rather than leaving it on screen in read mode as
a recipe with working Edit, Delete and favourite buttons — where favouriting it would have been the
thing that finally created it on the server.

**Every** route that replaces what the editor holds goes through the guard, not just the obvious
ones: opening another recipe, choosing a filter, cancelling — and also finishing a web import, and
entering Select mode, which swaps the detail pane for its own placeholder as soon as a second row is
ticked and so unmounts the editor. The editor reports itself clean when it unmounts, so a prompt is
never left armed over edits that no longer exist.

So, for the actions likely to come next:

- **Additive and reversible** (duplicate a recipe, a `.saltyRecipe` file, a third list shape) belongs
  on the `+` and its menu. Recipes' menu holds *New recipe*, *Import from web…* and *Select
  recipes…*; shopping lists' holds *Checklist* and *Markdown list*. A `.saltyRecipe` import is
  another way of making a recipe, so it goes in the recipe menu — not a third top-level button.
- **Less-used** (delete, archive, export-and-remove, *Select recipes…*) belongs in the `⋯` menu.
  Not "destructive": that describes some of what has landed there and none of why. The `⋯` is the
  overflow — what does not earn a button of its own — and delete qualifies because it is rare, not
  because it is dangerous. Reading it as "destructive" would put *Get info* somewhere else, when it
  belongs here for exactly the usual reason: it is not something you do often. It sits at the top of
  the recipe's own `⋯` above a divider, because it reads a recipe rather than acting on the list the
  way everything in the list column's menu does.

The list menu deliberately lists *Checklist* as well as *Markdown list*, even though the main button
already makes a checklist: the menu has to read as the complete set of things the button makes, not
as a list of leftovers.

**Every label the app puts on itself is sentence case** — *New recipe*, *Get info*, *Last prepared*,
*Set to today*, *All recipes*, *Want to make*, *Shopping lists*, *Edit classifiers*, *Date modified*,
*Last made*. This is Fluent's own rule (Microsoft's style guide asks for sentence-style
capitalization in menus, buttons, navigation and headings); title case is the *Apple* convention,
which is why the Swift app reads *Get Info* and *Last Prepared Date* and the Compose app *Made
Today*. It is the one place this client deliberately does **not** copy its siblings' wording
character for character: the names still line up (*Last made* is the same sort *Last Made* is), but
the capitals follow the toolkit the app is actually built in. A borrowed label loses its capitals at
the door.

What that rule does *not* reach is the recipe's own data. The nutrition editor's field names stay
*Total Fat*, *Saturated Fat*, *Added Sugar* because that is how a nutrition panel is printed, and
the difficulty values stay *Somewhat Easy* / *Slightly Difficult* because they are one enum shared
with every client rather than something this app chose to call them.

**Selecting is a mode, not a column.** The recipe list is Fluent's `List`/`ListItem`, and its
`selectionMode` is flipped between `"single"` and `"multiselect"` by a Select mode entered from the
list's overflow menu. Normally the selection *is* the recipe being read and there are no checkboxes;
in Select mode it is the set a bulk delete will act on and every row carries one. That is one prop
rather than a hand-rolled column, and it keeps a permanent checkbox gutter — 28px off every recipe
name, for a gesture almost nobody wants — out of the ordinary case. The shopping-lists index is the
same `List` in single mode, so the two columns share their listbox semantics, roving focus and
keyboard handling rather than one of them rebuilding those by hand.

## Sorting, and what the app opens to

**Nothing is selected when the app opens.** It used to open the first row of a list ordered by
last-modified, which is not the first or last recipe on screen and not the one you had open before —
just whichever was edited most recently. That is an arbitrary recipe with the confident air of a
chosen one, and it was the first thing anyone noticed about opening the app. Deleting a recipe lands
on the same empty state, for the same reason and one more: dropping straight into an unrelated
recipe hides the fact that the delete happened at all. Deleting the open shopping list does the
same.

**Sort is a field plus a direction, chosen separately** — the split the CMP and Swift apps use, so
the same ordering goes by the same name whichever client you opened. Name, Date modified, Date
created and Last made, each ascending or descending. Both halves are ticked in the menu (two
`MenuItemRadio` groups under one `checkedValues`) and both are remembered in `localStorage`: an
order you picked is a preference, not a per-visit decision, and it sits beside the pane width for
the same reason chef mode's wake-lock switch does — it is a fact about this browser, not about the
account.

**It lives in the list's `⋯` menu**, under a `Sort by` group header, rather than behind an
arrows icon of its own. Six radio rows are short enough to show outright, so it is a `MenuGroup` in
the one menu rather than a submenu behind a hover — and an ordering is something you set once and
leave, which does not earn a permanent button in a header three glyphs wide.

Two details are not decoration. The direction items carry a hint (`A → Z`, `Newest first`) because
"Ascending" on a date does not say oldest-first on its own. And **Last made parks never-made
recipes at the end in both directions**, with the row's second line switching to `Made 3 days ago` /
`Never made` while that sort is in force: ascending would otherwise open on every recipe that has no
date at all, and without the date on the row nothing on screen explains the order or the block at
the bottom. The CMP app does both, for the same reasons.

Source, Rating and Difficulty are sort options in the CMP and Swift apps and are not offered here
yet; each is one row in `SORT_OPTIONS` and one comparator in `COMPARATORS` in `model.js`.

## How dense the recipe list is

Three styles, chosen in **Settings → Recipe list**, and **Summary is the default** — an existing
reader's list does not change under them.

| | Row | What it shows |
| --- | --- | --- |
| **Summary** | 56px | 48px thumbnail, name, the line about the recipe, rating and favourite |
| **Small icons** | 40px | the same row at a 32px thumbnail and 2px padding |
| **List** | 24px | one line: a 20px thumbnail, the name, and its marks |

The first two keys and names are the Swift app's own `RecipeListViewStyle` (`summary`,
`smallIcons`), so someone who set this on the Mac meets the same two words here. `List` has no Swift
counterpart; the name is Explorer's, for the view it behaves like. The ladder is 56 → 40 → 24,
which is a third more recipes on screen and then twice as many.

Four things about it are deliberate.

- **It is in Settings, not the list's own menu.** The rule above sorts actions on the list between
  the `+` and the `⋯`; a view density is not an action on the list at all, it is a per-browser
  preference like the sort and the column width. Settings is also where the Swift app puts it. It applies as
  it is chosen rather than on close, so the answer to "which of these is it?" is the list column
  still visible beside the dialog.
- **24px is Fluent's own small row height** — what a `TreeItem` or a `MenuItem` takes at
  `size="small"` — rather than a number picked to look like Explorer. That is what "Fluent-appropriate
  Windows 95" turns out to mean; the design system already had the height.
- **List keeps the photo, shrunk to 20px, rather than a uniform file-type glyph.** At that size it
  is a smear of colour rather than a picture, but a smear of colour is the fastest thing on the row
  to recognise, and one placeholder rule across all three styles beats a special case in the densest
  one. The rating survives too, as Fluent's `RatingDisplay compact` — one star and the number, not
  five stars taking the width the name wanted.
- **`listStyleKey` guards what comes back out of `localStorage`.** That store outlives the build
  that wrote it, so an unknown key is an ordinary thing rather than a bug; without the guard it
  would fall through every branch in the row and render one with no size at all.

Only the recipe list has this. The shopping-lists index has no thumbnails and one line already, so
there is nothing to make denser.

## What a load actually transfers

A cold load used to move about a megabyte, and a *reload* moved the same again. Three things were
true at once, and they were fixed together because each one hid the others.

| | Before | After |
| --- | --- | --- |
| `salty.js`, first load | 870 KB | **249 KB** (gzip) |
| `salty.js`, every reload | 870 KB again | **304, no body** |
| `/api/recipes`, 100 real-sized recipes | 385 KB | **34 KB** raw, gzipped on the wire |
| thumbnails | re-fetched every load | cached for a year |

**Nothing was compressed.** No `Compression` plugin, and the NGINX block in `DEPLOY.md` has no
`gzip` either — so the bundle and every JSON response went raw. It is the Ktor plugin rather than a
line in the proxy config because the other deployment shape in `DEPLOY.md`
(`docker-compose.offline`, port 8080 published directly) has no proxy to put it in, and because the
native clients' sync goes through the same JSON.

Images are excluded by omission — JPEG and PNG are already compressed. `text/html` is excluded
deliberately: those are the only responses carrying the CSRF token, and compressing a secret
alongside anything an attacker can influence is the shape of BREACH. `SameSite=Strict` already means
a cross-site request arrives with no session and gets a 401, so it is belt-and-braces, and it is
free — the pages in question are a couple of KB.

**Nothing carried a cache validator.** `ConditionalHeaders` plus a `no-cache` default turns a repeat
load into 304s. It has to be `no-cache` rather than a far-future `max-age` for the bundle: the
filenames are fixed rather than content-hashed, on purpose, so the shell can name them — a
year-long cache would be an old app that nothing short of a hard reload could replace.

Images opt out of that and into a real long cache, because their URLs can be made honest. An image
keeps its filename when it is replaced (they are named `<recipeId>.<ext>`), so the URL alone cannot
say which bytes it means — which is why nothing could be cached before. `api.js` appends
`?v=<lastModifiedImageDate>`, a stamp bumped when and only when the bytes change, and the server
answers a URL carrying its own current stamp with a year and `immutable`. A stale `?v=` gets the
cautious answer rather than being pinned on a guess, and an unversioned URL — which is what the
native clients send — gets `no-cache` plus an ETag, so it 304s. The ETag is that same stamp: there
is nothing to hash, because the database already records exactly when the bytes last changed.

**`/api/recipes` returned whole recipes to draw a column of names.** `?fields=summary` returns
`ServerRecipeSummary` instead — the name, the dates the sorts order by, the classifier ids the
filters match on, the row's second line, the rating and the image stamp. Ingredients, directions,
notes, variations, times and nutrition are around 88% of a library by bytes and none of it reaches a
row, and the app fetches the recipe it opens in full anyway.

Two things about it are deliberate. It is **opt-in**, and the default shape is untouched: this is
the endpoint the Swift and Compose clients sync against, and a sync that quietly stopped receiving
ingredients would be data loss rather than slowness — `theDefaultListStillCarriesWholeRecipes` is
there to keep it that way. And it is a **separate type** rather than a `ServerRecipe` with the heavy
fields nulled out, so that null ingredients cannot come to mean both "this recipe has none" and "you
did not ask for them". The saving is not only on the wire: naming the columns keeps H2 from reading
the six large `TEXT` blobs at all, and the server no longer parses every ingredient of every recipe
into objects purely to serialize them straight back out.

**What is still on the table** is fetching only the rows on screen. It is deliberately not done:
search, sort and the library filters all run client-side over the whole set in `model.js`, so
windowing would mean either moving that to the server — logic that is deliberately shared with the
native clients — or a search box that only searches what happens to be loaded. At 34 KB for a real
library the row count is not what hurts. Virtualising the *rendering* is the cheaper half of that
idea if a library ever reaches thousands.

**The served markup carried its own source comments.** Vite has always minified `salty.js`, but
the Mustache shells and the classic view's `salty.css` went into the jar exactly as written, so
every visitor downloaded the maintenance notes that were written for whoever edits them next --
including the paragraph in `app.mustache` explaining where the bundle comes from. `MinifyWebResources`
in `server/build.gradle.kts` strips HTML, Mustache and CSS comments plus the indentation on the way
into the jar; the sources under `src/main/resources` are untouched, so they stay as readable as they
were. It is 35.6 KB of templates and stylesheet down to 27.2 KB, `app.mustache` itself from 2,425 to
1,203 bytes.

It is on by default in every build. Pass `-PminifyWeb=false` (or flip `minifyWeb` in
`gradle.properties`) to package the markup verbatim when a served page has to be read as-is.

The minifier is deliberately conservative, because the failure mode of an over-eager one is a
rendering bug nobody connects to the build. Whitespace is never collapsed *within* a line, so
significant space between inline elements survives; `<pre>`, `<textarea>` and `<script>` bodies are
held out of the pass entirely; and CSS keeps its spacing around `:` and the combinators, because
`.a :hover` and `.a:hover` are different selectors.

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

**Sessions expire 15 days after sign-in** (`MAX_SESSION_AGE_SECONDS`), checked in the same place. The
bound is here rather than on the cookie because a cookie's `Max-Age` is client-side state: it tells
the browser when to stop sending the cookie, which is no help against a copied value being replayed.
It also costs nothing, since that function is already reading the user row. Note it is *absolute*,
not idle — Ktor only re-sends `Set-Cookie` when the session is modified, so activity does not slide
the window and a busy tab is still signed out on day 15.

Every password field -- your own, a new user's, an admin's reset for someone else -- is
`PasswordInput`: masked, with a reveal toggle in the input's own `contentAfter` slot. Masked because
a password on screen is a password over a shoulder; the toggle because an admin setting a temporary
password has to read it back to pass it on, and a masked field with no reveal makes a typo invisible
until the other person cannot sign in. The Settings password form is its own component mounted
inside the dialog, so closing Settings without submitting takes the half-typed passwords with it.

## Why this stack

The question the React rewrite existed to answer was "does Fluent feel right for Salty", and the
answer was to use it rather than argue about it. What it told us:

**Fluent covered the app.** Every component the UI needed exists in v9, including the ones
Fluent's *web components* v3 does not have — `Toast`, an interactive `Rating`, `SpinButton`, and
`NavDrawer` for the library rail. Nothing had to be hand-rolled to Fluent's spec, which was the
whole reason for choosing v9 over the web components. The rail was first built on `Tree` with a
hand-styled selected state and a hand-made collapsed strip, which worked and was the wrong
component: NavDrawer is the one Microsoft documents for exactly this, and it carries selection,
categories, the overlay mode, the hamburger and a footer itself.

**Where things go, when the other clients already decided.** Placements were checked against the
Compose app rather than argued from taste — Edit classifiers at the foot of the rail, Settings as a
single scrolling column, tabs only inside the classifier editor. See *The rail* above.

**Branding is a ramp, not an override.** `createLightTheme`/`createDarkTheme` take a sixteen-step
brand ramp and derive every brand token from it, so `theme.js` adds Salty's blue without overriding
a single component. The catch is accessibility: the app's #0097F5 carries white text at only
3.11:1, under AA's 4.5, and Fluent puts brand80 behind primary buttons with white on it. So the
ramp keeps the hue and lands slot 80 on the lightest shade of it that passes (#007BC7, 4.51:1);
Salty's actual blue comes out at slot 90, which is where Fluent uses it for accents and hover. The
one place the brand does real work is the selected row and rail item, through
`colorBrandBackground2` — a stock token, pale tint in light and deep tint in dark. Light and dark
follow the OS (`prefers-color-scheme`, which the Mustache shell also honours before first paint so
there is no flash) — no in-app toggle.

**The type is Fluent's, deliberately.** `fontFamilyBase` is already a per-platform stack: Segoe UI
on Windows, `-apple-system` on macOS, Roboto on Android. Pinning Segoe everywhere would ship a
webfont to make a Mac look like Windows. The icons are `@fluentui/react-icons`, so the sizes,
weights and outlined/filled pairs are the ones Fluent's own components use.

**Two places Fluent has no answer**, worth naming. It has no **destructive button appearance**, so
`ConfirmDialog` leans on wording — every caller passes a verb ("Delete", "Discard", "Sign all out")
rather than accepting an "OK". And its type ramp is in `rem`, so **chef mode** enlarging the
document would have left fixed-size headings smaller than the body they head; the fix is to move up
the ramp (`Subtitle2` → `Title3`) rather than to override a size.

**Griffel is fine but it is not CSS.** Styling lives in `makeStyles` objects beside each component,
which reads well and scopes cleanly, and the old stylesheet's commentary had to move into the
components. Two things needed knowing: `tokens.*` are CSS custom properties, not values, so they
compose in template strings; and Griffel sets no global `box-sizing`, so a `width: 100%` row plus
inline padding overflows its pane.

**The bundle is about 850 KB raw, 245 KB gzipped**, with no code splitting attempted. That is the
honest number for React 19 + Fluent v9 + the icons; splitting the editor and the dialogs out of the
initial chunk is the obvious first move if it matters. The tax of the stack is 400 MB of
`node_modules` and the dependency treadmill, not the build loop.

## Things worth knowing

**The layout's height is `100dvh`, and it cannot be a percentage.** `height: 100%` looks tidier —
the shell gives `html`, `body` and `#root` a height — but `FluentProvider` renders its own `div` in
between and has none, and a percentage against an `auto` parent is indefinite. The grid grew to its
content instead, and the list column's scroller came out exactly as tall as its rows:
`scrollHeight === clientHeight`, nothing to scroll, everything past the fold clipped by body's
`overflow: hidden`. **The list had never scrolled, at any width** — on a desktop that reads as a
library that ends early, and only on a phone, where you run out of rows in seconds, does it read as
the bug it is.

Putting the height on the provider instead fixes the chain and breaks Fluent's portalled popovers:
the Dropdown listbox opens `display: none`. So it goes on an element Fluent does not own. `dvh`
rather than `vh` for the reason the panes used to sit behind iOS Safari's toolbar — `100vh` is the
*large* viewport and stays that tall while the toolbar is showing, while `dvh` tracks what is
actually visible.

`theColumnsScrollInsteadOfTheWindow` passed throughout, because "the window does not scroll" and
"the column does" are different claims and it only made the first.
`theRecipeListScrollsAtEveryWidthAndUnderAFinger` makes the second: it seeds more rows than fit,
asserts `scrollHeight > clientHeight` at a phone width and a desktop one, and then drives a real
touch drag through CDP — a stray `touch-action: none` would satisfy every other assertion and still
leave the list immovable under a finger.

**State updaters must be pure.** `useHashDialog` once called `history.pushState` inside a
`setState` updater. React calls updaters twice under StrictMode in development, so every open pushed
two history entries and Back closed nothing — in dev only, which is the kind of bug that survives.
The history calls now sit outside the updater, reading `dialog` from the render.

**Reset with a `key`, not an effect.** The editor, the read view and the confirm dialog all start
from their props; App mounts each with a `key` (the recipe id, or a per-request serial) so a new
recipe or a new prompt is a new component, rather than the old one watching its props and calling
`setState` to catch up.

**`ConfirmDialog` handles failure, once.** An `onConfirm` may reject; the dialog stays open with the
field intact and reports through `onError`, so callers write no try/catch and a failed request can
be retried. Before this, a failed device rename vanished into an unhandled rejection.

**A save on blur must check that something changed.** Shopping lists save when a field loses focus.
Every save bumps the list's revision, and a revision bumped for nothing is a three-way merge some
other device then has to do for nothing, so blur compares the list to the last server echo first.

**`Tooltip relationship`: `label` for icon-only buttons, `description` for buttons with text.** A
label tooltip *replaces* the trigger's accessible name; on a button that already says "Promote" it
would have thrown the visible text away.

**`100vh` is not the visible viewport on a phone.** iOS Safari's is taller than what shows, and the
compact layout exists precisely for phones, so the shell is `height: 100%` of `#root` with a
`minmax(0, 1fr)` grid row, which is what lets the panes size below their content and scroll inside
themselves.

**Vite Fast Refresh needs a file to export only components.** An object default export
(`{ List, Detail }`) turns every edit to that file into a full reload; named exports keep HMR.

**The confirm dialog's backdrop hides the editor's own Cancel.** An unscoped
`getByRole(BUTTON, hasText("Cancel"))` in a test finds that one and waits thirty seconds for it to
become clickable. Scope dialog interactions to `getByRole(DIALOG)`.

**UUIDv7 is monotonic within a millisecond**, matching `swift-uuidv7` on the Apple clients. The bare
spec only orders ids across milliseconds, so a burst minted in one tick would sort randomly — which
would defeat the reason Salty uses v7. The sequence counter lives in the 12 `rand_a` bits.

## What changed on the server

Mostly one thing: **the JSON API accepts the web session cookie as well as a Bearer device sync
token**, so the browser reuses the endpoints the native clients use instead of growing a parallel
set of form-POST routes that would duplicate the write rules.

The exception, and the highest-risk server code here, is **`shoppinglist/ShoppingListResolve.kt`**
— read that first. It exists because the browser has no local database and therefore no
`syncedSnapshot` to merge against, so it sends the copy it loaded as the base and the server runs
the shared merge on its behalf.

- **`WEB_API_AUTH`** (`auth/Auth.kt`) — a second session provider over the same cookie. It exists
  because `WEB_AUTH` answers an unauthenticated request with a redirect to `/login`, which is right
  for a page navigation and wrong for `fetch`.
- **`ApplicationCall.userId()`** resolved the Bearer principal alone and would have thrown on every
  cookie-authenticated call. It now resolves either principal; all 36 call sites are unchanged.
- **`ApiCsrfGuard`** (`auth/ApiCsrf.kt`) requires `X-CSRF-Token` on cookie-authenticated writes. Bearer
  callers are exempt — a Bearer token is not an ambient credential. It hooks Ktor's `AuthenticationChecked`
  rather than `onCall`, because an `onCall` observer responds *alongside* the handler instead of
  short-circuiting it, which let the write land anyway.
- **`templates/app.mustache`** is a shell: the `#salty-config` data attributes and one
  `<script type="module">`. Config is read from data attributes rather than a script literal because
  Mustache escapes for HTML, which is right for an attribute and wrong inside `<script>`, where a
  backslash in a username would start a bogus `\u` escape and take the whole app down.
- **`server/build.gradle.kts`** gains `npmInstall` and `buildWebapp` (plain `Exec`, because the
  node-gradle plugin's configuration-cache support is unresolved and this build uses it), and
  `processResources` copies `build/webapp` to `static/app`.

## What it does

Account: change your own password, see the apps authorized to sync — signing them all out, or
removing one outright — and (for admins) create, promote, reset and delete users — all as dialogs
over the recipe list, each addressable by URL hash so a reload puts you back where you were.

Shopping lists: both shapes. A **checklist** (item rows, check-offs, importance, section headings,
clear-completed) or a **Markdown list** (a plain-text scratch pad the phone and desktop apps
render). Rename, delete, and the **three-way conflict merge**: a 409 sends `{base, local}` to
`/api/shoppingLists/{id}/resolve`, which runs the same shared `ShoppingListMerge` the native
clients run, so a check-off on one device and an edit on another both survive.

Recipes: list, search, **sort**, open, create, delete — singly or **several at once by checkbox**
— and **import from a web page**. The editor covers the photo, classifiers (with a tag creatable
from inside the editor), rating, difficulty, ingredient and direction rows with headings,
main-ingredient marks and **drag-to-reorder**, times, notes, variations and nutrition. An emptied
nutrition record is removed rather than stored as an object of nulls. Toasts for feedback,
unsaved-changes guards on navigate and unload.

**Get info** is the recipe's `⋯` opening a panel of its three dates — added, modified and last made
— named after the Swift app's command of the same name and spelled, as that platform spells it,
without an ellipsis. It reports; it does not set. **Last prepared** is the item under it, and behind
its chevron the stored day is read back at the head of the submenu — the stored date, or *Not set* —
where the Swift app reads it too. It is a `MenuGroupHeader` rather than a disabled row: Fluent
already draws that muted and a size down, which is the weight a fact wants next to two commands,
and a disabled row would claim to be a command that happens to be unavailable.

Under it are **Set to today** and **Set date…**. Only the second opens anything, a dialog
holding a date field capped at today (a recipe cannot have been made in the future) and a **Clear**
button beside Cancel and Save. Clear is a button rather than a third menu item because in a dialog
that commits on Save, an item that commits on its own would be the one place a mis-click could not
be taken back; emptied, Cancel still undoes it, and Save writes the clear — `dayValueToPrepared`
reads an empty field as "no date". *Set to today* stays on the menu: it is an answer nobody has to
compose, so a dialog around it would be a step for nothing.

That date is why any of this exists: `lastPrepared` was sorted on and shown on the row but could not
be *answered* anywhere in this app, so "Last made" was an ordering over a field the web client could
never write. It was first answered inside Get info, which was the wrong home for it — a panel that
reports facts is no place for the one control that changes one of them — and it is deliberately not
in the editor either, for the reason below.

The field is `<input type="date">` inside a Fluent `Input` rather than Fluent's own `DatePicker`,
which ships in a separate compat package. The platform's picker is the wheel on a phone and the
calendar the browser draws everywhere else, and it can be typed into by anyone who would rather not
hunt through months; the box around it is still the app's.

Two rules about that write are inherited rather than invented. The value stored is **local noon** of
the day picked, because the column is a UTC timestamp every client renders in local time and
midnight would read as the *previous* day for anyone west of UTC; `PreparedDates` in `shared` and
the Swift app's `localNoon(on:)` write the same column the same way. And it travels with a fresh
`lastModifiedPreparedDate` while **`lastModifiedDate` is left alone** — marking a recipe made is not
a body edit, and bumping it would reorder every client's Date modified sort. The server merges the
pair by that stamp rather than the body clock, so a body edit racing a mark-made on another device
cannot clobber it. A collapsed **Sync details** in Get info shows the three clocks a recipe syncs on — itself, its photo, its last-made date — with
the identifier under them.

Drag-and-drop is hand-rolled on the HTML5 drag events rather than pulling in `@dnd-kit`: it is one
handle, one drop indicator and about forty lines, against a dependency whose main draw —
accessible keyboard dragging — the up/down buttons already cover.

**Chef mode** is the reading view with the app taken away: the rail, the recipe list and the detail
bar all go, the recipe gets the whole window, and the document steps up the type ramp. It is reached
from a recipe you are reading and left by the one button it leaves on screen or by Escape. Nothing
of the recipe itself is hidden — "how much butter" is the question you are standing there with —
and it is deliberately neither remembered nor in the URL: it says "I am cooking right now", not
"this is how I like the app".

While it is open it holds a **screen wake lock**, so the screen does not dim four steps into a
recipe. Three things about that are deliberate. It is best effort and silent: the request is refused
when the tab is not visible, when the browser is saving power, and everywhere the API is missing, and
none of those is worth interrupting someone mid-recipe about. It is re-taken on `visibilitychange`,
because the platform drops the lock whenever the page stops being visible and never hands it back —
ducking out to a timer app and returning must not leave the screen dark. And the preference that
governs it lives in `localStorage` beside the pane width rather than on the server, because "keep
this screen on" is a fact about the tablet propped against the toaster, not about the person: the
same account on a laptop wants the opposite. It is on by default and one switch away in Settings.
Screen Wake Lock is secure-context only, so a Salty reached over plain `http` on the LAN does not
have it at all — Settings says so rather than offering a control that silently does nothing.

The line under a recipe's name — course, yield, servings, difficulty — is **built as a list and
joined once**, so a recipe with no yield does not read "Main ·  · Serves 6", and an empty line goes
away rather than leaving a gap. Categories and tags are not drawn over the recipe: they are how you
*find* a recipe, not something to read first in one you have already opened.

The list/recipe divider is **draggable** (pointer events, so a pen or a finger on a wide tablet can
do it too), bounded to 260–620px, and the width is remembered in `localStorage` per browser. Below
the 900px breakpoint the layout is one pane at a time, the rail is an overlay drawer, and the
divider is gone.

Saving spreads the loaded recipe, so fields this screen doesn't edit round-trip untouched. `PUT`
replaces the row, so dropping any would wipe them. The body is saved before the image: the body PUT
carries the recipe's copy of `imageFilename`, so uploading first would let that stale value
overwrite what the upload just set.

## Not done

- **No conflict handling for recipes.** Recipe saves remain last-writer-wins on `lastModifiedDate`,
  by choice. Shopping lists do resolve conflicts (above).
- **No row context menu.** The CMP app's long press offers Edit, Export…, Last Made… and Delete on
  a row without opening it. Here each of those wants the recipe open first, Get info and Last
  prepared included.
- **No reordering for notes, variations or preparation times.** Ingredients and directions can be
  dragged or moved with the buttons; the three secondary lists append and delete only.
- **No keyboard equivalent of the drag itself.** The up/down buttons are the keyboard route.
- **No web app manifest yet.** The icons themselves are real: `static/icon-192.png`,
  `favicon-32.png`, `apple-touch-icon.png` and the root `favicon.ico`, all recomposited from the
  Android launcher icon's adaptive layers by `composeApp/icons/make-web-icons.swift`. An
  installable app ("Add to Home Screen") also wants a 512px icon, and the source art is a 432px
  canvas, so that size has to be upscaled or redrawn before a manifest is worth adding.
- **Chef mode does not follow along.** No current-step highlighting and no step-at-a-time paging —
  it is the same scrolling document, just larger, alone and awake. The Swift app's highlighting is
  the obvious next step.
- **The classic view is not yet removable.** See the parity note above.
- **No `.saltyRecipe` file import.** The menu it belongs in exists; the format handling does not.
- **Web import reads JSON-LD only.** A site that renders its recipe in the browser, or publishes
  microdata rather than JSON-LD, imports as "no recipe data found" — same as the native clients.
- **No Markdown preview.** The text area shows source, as the classic page did.
- **No code splitting.** See the bundle note above.

## Tests

`WebApiAuthTest` — session cookie reads the API; unauthenticated calls get 401 JSON rather than a
login redirect; a cookie write without a CSRF token is rejected *and changes nothing*; with the token
it succeeds; Bearer still works and is exempt; `/app` requires auth; `/editor` still redirects to it;
the page carries the token; a session past `MAX_SESSION_AGE_SECONDS` is rejected while one inside it
is accepted (the second half matters — it is what proves the first is failing on age rather than on
the backdated password stamp the test sets up).

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

`ReactUiSmokeTest` — Playwright drives the app at `/app` in a real browser, signing in through the
real form. The bundle mounts and lists recipes fetched with the session cookie; opening a recipe
renders the read view with sections, numbered steps that skip headings, and times; scaling rewrites
quantities and leaves un-quantified lines alone; the editor opens on the recipe being read and its
save reaches the database; chef mode takes the other panes away and Escape brings them back;
leaving an unsaved edit asks first, and keeping it leaves the typed value intact; a dialog puts
itself in the URL so Back closes it; About sits collapsed at the bottom of Settings; the compact
layout shows one pane at a time; the sort menu drives a fixture whose four orderings are
deliberately all *different*, so a picker wired to the wrong field cannot pass by coincidence;
select mode, bulk delete, the favourite mark, the library manager, both shopping-list shapes, and
deleting the open list landing on the empty state rather than a spinner. Last prepared is checked
for the two rules rather than for the feature: the day the menu writes comes back at **local noon**,
and `lastModifiedDate` is **unchanged** by it. The menu row and Get info are both checked for
reading that day back, and Get info for no longer offering to set it. Most assert **no console
errors**, which matters more here than it did with Alpine: a React component that throws during
render unmounts its subtree and leaves a blank pane, and a blank pane looks exactly like an empty one
in a screenshot. They are **skipped, not passed**, when no browser can be launched.

Run the suite with `./gradlew :server:test`. The count changes often enough that quoting it here
only creates another thing to go stale.
