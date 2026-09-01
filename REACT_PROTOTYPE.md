# The `react` branch — /app rebuilt on React 19 + Fluent UI v9

Replaces the Alpine + Web Awesome web app with React 19 and Fluent UI React v9. It now does
everything the Alpine app did, so the question "does Fluent feel right for Salty" can be answered
by using it rather than by reading an argument about it.

The Alpine app is gone from this branch — `app.js`, `app.css`, `icons.js` and `EditorUiTest` are
deleted. `main` still has all of it, and the in-progress `openDraft` work that was uncommitted is
preserved on the `alpine-wip` branch.

`EditorUiTest`'s behaviours have since been **ported** — `ReactUiSmokeTest` is 27 tests, and the
whole server suite is 172 and green. What did not come across was anything testing Web Awesome
itself (component upgrade, the `wa-page` toggle, Alpine's `$data`), because those tested the library
rather than Salty. The originals are still readable at
`git show main:server/src/test/kotlin/com/enuvro/saltykmp/EditorUiTest.kt`.

Notes are kept here rather than in `WEB_APP.md` because that file has your own in-progress edits;
merging the two is a decision for whoever keeps this branch.

## Try it

```bash
./gradlew :server:run
```

Then <http://localhost:8080/app>. Gradle builds the bundle on its way to `processResources`, so
there is no separate front-end step to remember.

For front-end work, the faster loop is Vite's, with the API proxied to a running Ktor:

```bash
cd server/src/main/webapp && npm run dev
```

That serves <http://localhost:5173> with hot reload and forwards `/api`, `/login` and `/classic` to
`localhost:8080`. Sign in on port 8080 first — the session cookie is what the proxied API calls use.

## What is here

```
server/src/main/webapp/
  src/api.js                      fetch wrapper: CSRF on writes, 401 → /login, server error text
  src/model.js                    ids, wire timestamps, ingredient scaling, sorting, display rules
  src/App.jsx                     shell, data loading, panes, the three-column layout
  src/hooks.js                    addressable dialogs, the wake lock, the unload guard, storage
  src/components/ConfirmDialog.jsx  the app's confirm() and prompt(), as one Fluent dialog
  src/components/NavRail.jsx      the library tree, Organize/Settings/About along the bottom
  src/components/RecipeList.jsx   search, sort menu, rows with rating and favourite
  src/components/RecipeDetail.jsx the read view, including ingredient scaling
  src/components/RecipeEditor.jsx the full editor
  src/components/ShoppingListPane.jsx  lists and their items
  src/components/dialogs.jsx      About, Settings, Organize library, Import from web
```

`src/model.js` is a straight port of the Alpine app's rules rather than a rewrite — UUIDv7 minted
client-side, the `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` wire format, the fraction table that turns a scaled
`1/2` into `3/4` instead of `0.75`, the sort comparators, and never-made recipes sorting last under
"Last Made" in both directions. Those all have to keep agreeing with the Swift and Compose clients,
so none of them were re-derived.

**Working:** the library rail with counts and live filtering; search; the sort menu (field and
direction, remembered per browser); the recipe list; the read view with scaling, sections, correctly
numbered steps that skip headings, times, notes and variations; **chef mode** with the screen wake
lock; the full editor including the **photo**, classifiers, rating, difficulty, ingredient and
direction rows with headings and **drag-to-reorder**, times, notes, variations and nutrition;
favourite and want-to-make toggles; delete; import from web; shopping lists in both shapes
(checklist and markdown) with headings, importance, clear-completed, rename, delete and the
**three-way conflict merge**; Organize library; **user administration** and **device management**;
the password change; **addressable dialogs** so Back closes one; an **unsaved-changes guard** on
both closing the tab and navigating inside the app; light and dark following the OS; a draggable
list/detail divider whose width is remembered.

## What is not here

Parity with the Alpine app is reached. What is left is smaller than it was:

- **`.saltyRecipe` file import.** The Alpine app does not have this either — the menu entry exists
  there, the format handling does not.
- **Markdown preview** for markdown shopping lists. The text area shows source, as before.
- **Recipe deep links.** Dialogs are addressable; a recipe is not, so a reload lands on the empty
  state. The Alpine app is the same. `react-router` is what would change that, and it would be a
  real improvement rather than parity.
- **Keyboard reordering by drag.** Rows drag with the mouse and move with the up/down buttons, so
  both routes exist, but there is no keyboard equivalent of the drag itself.

Porting the tests found four things the app was missing outright, all now fixed: there was **no
compact layout** at all (three fixed columns on a phone), the list did not swap its subtitle for the
**last-made date** under that ordering, an emptied **nutrition** record was stored as an object of
nulls instead of being removed, and a **tag could not be created from inside the editor**. It also
found two real bugs: creating any classifier **400'd**, because the route receives a whole
`ServerCategory` and its `id` is non-null while the app sent only a name; and the **divider could
not be dragged**, because the pane clips its overflow and the strip straddled the border, so half of
it — including the half being aimed at — was invisible to hit-testing.

Drag-and-drop is hand-rolled on the HTML5 drag events rather than pulling in `@dnd-kit`: it is one
handle, one drop indicator and about forty lines, against a dependency whose main draw — accessible
keyboard dragging — the up/down buttons already cover.

## What this told us

**Fluent covered the app.** Every component the UI needed exists in v9 — including the four that
Fluent's *web components* v3 does not have: `Card`, `Toast`, an interactive `Rating` and
`SpinButton`, plus `Tree` for the library rail. Nothing had to be hand-rolled to Fluent's spec,
which was the whole reason for choosing v9 over the web components.

**Where things go, when the other clients already decided.** Two placements were checked against
the Compose app rather than argued from taste. "Edit Classifiers" sits at the bottom of the rail
beside Settings because that is where the Compose drawer puts it, for a reason its own comment
gives: editing classifiers is rare and app-level, so it is one row rather than an "Edit…" hung off
each of the three groups, and the recipe list's toolbar is for actions on the list. The Uno app
agrees. Settings is a single scrolling column rather than tabs, which is what the same Microsoft
guidance asks for: "present content from top to bottom in a single column, scrollable if necessary",
with related settings grouped under section headers. Tabs appear once, inside the classifier editor,
which is also what the Compose app does.

**About belongs in Settings, not in a menu.** Microsoft's
[app-settings guidance](https://learn.microsoft.com/en-us/windows/apps/design/app-settings/guidelines-for-app-settings)
is explicit: "app information that isn't accessed very often, such as privacy policy, help, app
version, or copyright info" goes in the settings page, and "we recommend placing an About section at
the bottom of your settings page using a `SettingsExpander`". So About is a collapsed `Accordion` —
v9's equivalent of that expander — at the end of Settings, rather than a top-level rail entry or an
item under the account menu. The same guidance is why Settings sits pinned at the bottom of the rail.

The two places Fluent has no answer are worth naming. It has no **destructive button appearance**,
so `ConfirmDialog` leans on wording — every caller passes a verb ("Delete", "Discard", "Sign all
out") rather than accepting an "OK". And its type ramp is in `rem`, so **chef mode** enlarging the
document would have left fixed-size headings smaller than the body they head; the fix is to move up
the ramp (`Subtitle2` → `Title3`) rather than to override a size.

**The bundle is 843 KB raw, 241 KB gzipped**, with no code splitting attempted. That is the honest
number for React 19 + Fluent v9 + 31 icons; splitting the editor and the dialogs out of the initial
chunk is the obvious first move if it matters.

**Griffel is fine but it is not CSS.** Styling lives in `makeStyles` objects beside each component,
which reads well and scopes cleanly, but `app.css`'s 766 lines of commentary do not survive the
translation — the reasoning has to move into the components. Two things needed knowing:
`tokens.*` are CSS custom properties, not values, so they compose in template strings; and Griffel
sets no global `box-sizing`, so a `width: 100%` row plus inline padding overflows its pane. That
one clipped the favourite hearts until it was fixed.

**Build cost is not a real cost.** `npm install` warm is 0.5s, `vite build` is 1.5s, and Gradle
skips both when nothing changed. The tax is 409 MB of `node_modules` and the dependency treadmill,
not the loop.

## Server-side changes

- `templates/app.mustache` is now a shell: the `#salty-config` data attributes and one
  `<script type="module" src="/static/app/salty.js">`. Nothing else.
- `templates/login.mustache` no longer loads Web Awesome. It is hand-written CSS using Fluent 2's
  own values — the neutral ramp, brand `#0f6cbd`, 4px radii, the Segoe-first stack — copied rather
  than imported so the one page a signed-out visitor can reach depends on nothing loading. Still
  native `<input>` and `<button>`, still no JavaScript.
- `server/build.gradle.kts` gains `npmInstall` and `buildWebapp` (plain `Exec`, because the
  node-gradle plugin's configuration-cache support is unresolved and this build uses it), and
  `processResources` copies `build/webapp` to `static/app`.

**Nothing loads Web Awesome any more, and nothing is left of it.** The three `static/app` assets are
deleted. `/classic` is untouched and does not care: it loads Pico and `static/salty.css`, and never
used Alpine or Web Awesome.

## Tests

`ReactUiSmokeTest` — 27 Playwright tests: the bundle mounts and lists recipes fetched with the
session cookie; opening a recipe renders the read view with sections, numbered steps and times;
scaling rewrites quantities and leaves un-quantified lines alone; the editor opens on the recipe
being read and its save reaches the database; chef mode takes the other panes away and Escape brings
them back; leaving an unsaved edit asks first, and keeping it leaves the typed value intact; and a
dialog puts itself in the URL so Back closes it; and About sits collapsed at the bottom of Settings
rather than anywhere more prominent. Most assert **no console errors**, which matters
more here than in the Alpine app: a React component that throws during render unmounts its subtree
and leaves a blank pane, and a blank pane looks exactly like an empty one in a screenshot.

The whole server suite is **172 tests in about 100 seconds**. It was 45 minutes while `EditorUiTest`
was still here, because each of its 58 tests waited out Playwright's 30-second default on a selector
the React app never renders.

One thing worth knowing when adding to these: the editor has a Cancel button of its own, sitting
behind the confirm dialog's backdrop where it can never be clicked. An unscoped
`getByRole(BUTTON, hasText("Cancel"))` finds that one and waits thirty seconds for it to become
clickable. Scope dialog interactions to `getByRole(DIALOG)`.

`EditorUiTest` **fails on this branch, by design.** Its 58 tests drive `wa-*` elements and Alpine
selectors that no longer exist. They are not obsolete — they encode real behaviour, and the list of
what they cover is the best specification of what a finished port would owe. Porting them is the
bulk of the remaining work and the honest reason this is a prototype rather than a candidate.
