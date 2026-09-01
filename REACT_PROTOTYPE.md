# The `react` branch — /app rebuilt on React 19 + Fluent UI v9

A working prototype, not a merge candidate. It replaces the Alpine + Web Awesome web app with
React 19 and Fluent UI React v9, so the question "does Fluent feel right for Salty, and is the
rewrite worth it" can be answered by using the thing rather than by reading an argument about it.

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
numbered steps that skip headings, times, notes and variations; the full editor including
classifiers, rating, difficulty, ingredient/direction rows with headings and reordering, times,
notes, variations and nutrition; favourite and want-to-make toggles; delete; import from web;
shopping lists with items, completion and importance; Organize library; the password change; light
and dark following the OS; a draggable list/detail divider whose width is remembered.

## What is not here

- **Chef mode.** The wake lock, the larger type, the whole screen state.
- **Image upload and removal.** The read view shows an existing image; there is no way to set one.
- **Drag-and-drop reordering.** Rows move with up/down buttons instead. `@dnd-kit` is the usual
  answer and would replace `@alpinejs/sort`.
- **Client-side routing.** The Alpine app pushes history state so Back works and a reload returns
  to the same recipe. This prototype does not; `react-router` is what would fill that in.
- **The unsaved-changes guard.** No prompt on navigating away mid-edit.
- **Shopping-list conflict merge.** A 409 reloads the list and says so, where the Alpine app runs
  the shared `ShoppingListMerge`.
- **Admin user management.**

## What this told us

**Fluent covered the app.** Every component the UI needed exists in v9 — including the four that
Fluent's *web components* v3 does not have: `Card`, `Toast`, an interactive `Rating` and
`SpinButton`, plus `Tree` for the library rail. Nothing had to be hand-rolled to Fluent's spec,
which was the whole reason for choosing v9 over the web components.

**The bundle is 812 KB raw, 232 KB gzipped**, with no code splitting attempted. That is the honest
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

**Nothing loads Web Awesome any more.** `static/app/app.js`, `app.css` and `icons.js` are dead on
this branch but were left in place: `app.js` holds uncommitted work of yours, and deleting the other
two around it would leave a half-state. Delete all three once that work has landed or been dropped.

## Tests

`ReactUiSmokeTest` — four Playwright tests: the bundle mounts and lists recipes fetched with the
session cookie; opening a recipe renders the read view with sections, numbered steps and times;
scaling rewrites quantities and leaves un-quantified lines alone; the editor opens on the recipe
being read. Each asserts **no console errors**, which matters more here than in the Alpine app: a
React component that throws during render unmounts its subtree and leaves a blank pane, and a blank
pane looks exactly like an empty one in a screenshot.

`EditorUiTest` **fails on this branch, by design.** Its 58 tests drive `wa-*` elements and Alpine
selectors that no longer exist. They are not obsolete — they encode real behaviour, and the list of
what they cover is the best specification of what a finished port would owe. Porting them is the
bulk of the remaining work and the honest reason this is a prototype rather than a candidate.
