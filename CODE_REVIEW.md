# Web editor review — `salty_kmp-webeditor`, `707f26a..HEAD` (5fa86e8)

Line numbers verified against the current working tree. Note HEAD has advanced one commit (`5fa86e8`) since the verification pass, so a few references shifted by ±1–15 lines from the raw findings; everything below is re-checked.

Findings are ordered by true severity. Because this server is the sync authority for three client families, anything that silently drops or reverts a write outranks everything else.

---

## HIGH

### H1 — Library classifier writes omit `lastModifiedDate`, so native sync reverts or deletes them
`server/src/main/resources/static/app/editor.js:580`, `:601`, `:619`

`createTagInline()`, `addClassifier()` and `renameClassifier()` POST/PUT bodies of `{id, name}` with no `lastModifiedDate`, unlike every other write in the file (`wireNow()` at :417, :515, :786, :908, :1040).

Failure — two flavours, both silent and cross-device:
- **Rename reverted.** Course "Breads" exists everywhere with stamp 2026-08-01. Web rename to "Quick Breads" → `LibraryRepository.upsertCourse` (`LibraryRepository.kt:29`) stores `WireDate.parse(null)` = NULL. Next phone sync: `LocalStore.parseOrPast(null)` = `Instant.DISTANT_PAST` for the server side, so `SyncReconciler.kt:43` `l.lastModified > s.lastModified` → `toUpload`, and the phone re-uploads "Breads". Rename gone everywhere, no error.
- **New classifier deleted.** Tag created in the editor and attached to a recipe. Any device that has never seen it syncs (not first sync): `SyncReconciler.kt:63` `s.lastModified > lastSyncDate` is false for DISTANT_PAST → `toDeleteOnServer`. `SaltyApiClient` sends no `If-Match` (it's null), and `LibraryRoutes`' DELETE ignores `If-Match` anyway, so the tag is deleted from the server and every device.

Fix: add `lastModifiedDate: wireNow()` to all three bodies. Separately worth hardening: have `LibraryRepository` fall back to `WireDate.nowUtc()` when the incoming stamp is null (the recipe repository already does this), so no client can create a DISTANT_PAST row.

Confidence: high. Every link verified in source; this is the most damaging finding on the branch.

---

### H2 — Shopping-list autosave discards edits made while a save is in flight, and reports "All changes saved"
`server/src/main/resources/static/app/editor.js:415` and `:453-457`

`saveList()` early-returns on `listSaving` with no re-queue, and `applySavedList()` unconditionally does `this.currentList = saved; this.listBase = structuredClone(saved); this.listDirty = false`. `api()` `JSON.stringify`s the body synchronously at dispatch, so an edit made after that point is provably not in the payload — and is then thrown away by the response.

Failure: tick "Milk", pause; the 700 ms debounce PUTs. Tick "Eggs" while the request is on the wire (the item objects are mutated in place by `editor.mustache:471`, `:476`, `:438`). Response lands → `currentList` is replaced by the server echo, the Eggs checkbox visibly un-ticks, `listDirty` goes false, header reads "All changes saved" (`editor.mustache:409-410`), and the `beforeunload` guard (`editor.js:291`, which reads `listDirty`) will no longer warn. The re-armed timer then re-PUTs the reverted copy. Slow-link variant: the timer fires while `listSaving` is true, hits the `:415` guard, schedules nothing, and the response clears the dirty flag anyway. Either way the tick never reaches the server or any device.

Fix: capture a generation counter before the `await`; in `applySavedList` skip the `currentList` replacement and the `listDirty = false` if the generation changed, and re-arm the debounce. Also gate the timer callback on `listDirty`, and make the `listSaving` early return set a `pendingSave` flag that the `finally` re-drives.

Confidence: high. Not covered by tests — `EditorUiTest.kt` waits for `!listDirty` before asserting in both autosave tests, which is exactly outside the window.

---

### H3 — `/resolve` sends a stale `lastModifiedDate`, so the browser loses every both-changed tie-break
`server/src/main/resources/static/app/editor.js:417` vs `:437`

`saveList()` stamps `wireNow()` onto a spread copy (`const body = { ...this.currentList, lastModifiedDate: wireNow(), ... }`) and never onto `this.currentList`. `resolveList()` then POSTs `local: this.currentList`, whose date is still the time of the last load/save. Nothing in `touchList`/`addItem`/`removeItem`/`clearCompleted` stamps it, and `ShoppingListResolve.kt:72-78` passes `request.local` through unchanged.

Failure: browser loads at 10:00 (rev 4). Phone renames item "Eggs" → "Free-range eggs" at 10:05 (rev 5). At 10:10 the browser user types "Duck eggs" and renames the list. PUT 409s → resolve POSTs `local` dated 10:00. `ShoppingListMerge.kt:51` computes `localIsNewer = false`, so `pick()`'s `else -> serverValue` branch wins both the item text and the list name. The user's typing is discarded, the UI is overwritten with the merged row, and `notify()` (`editor.js:445`) says "Merged changes made elsewhere". Self-perpetuating: the merged row adopts the server's date, so the next conflict loses the same way.

Scope: loss is confined to `name`, item `text` and `isHeading` — freeform text and `isFreeform` are preserved as conflict copies, and flags OR together.

Fix: one line — stamp `this.currentList.lastModifiedDate = wireNow()` in `touchList()` (or immediately before the resolve POST) so `local` reflects when the edit actually happened.

Confidence: high. `ShoppingListResolveTest` models a well-behaved client that *does* stamp, and `EditorUiTest`'s merge test edits different items, so the clock is never consulted.

---

### H4 — `flushListSave()` doesn't join an in-flight save, so switching lists loses an edit and can mis-target the resolve
`server/src/main/resources/static/app/editor.js:409-411`, `:415`, `:453`

`flushListSave()` awaits `saveList()`, which returns instantly on the `listSaving` guard. No in-flight promise is stored anywhere, and `applySavedList()` never checks `saved.id` against `selectedListId`.

Failure: tick an item in list A; the debounce PUTs. Click list B. `openList(B)` (`:383`) awaits a no-op flush, GETs B, sets `currentList`/`listBase`/`selectedListId` to B and `listDirty = false`. Tick an item on B. A's PUT then resolves and `applySavedList(savedA)` reassigns `currentList`/`listBase` to A and clears `listDirty` — B's tick is silently dropped and the next timer PUTs to A's id, while the sidebar highlights B and the detail pane renders A. Worse on the 409 branch: `resolveList()` reads `this.currentList.id` and `this.listBase` at response time, so after the switch it POSTs `{base: B, local: B}` to `/api/shoppingLists/B/resolve` — a no-op merge, and A's conflicted edit is discarded with no error.

Fix: keep a handle on the in-flight promise and have `flushListSave()` await it; guard `applySavedList()` with `if (saved.id !== this.currentList?.id) return;`; capture the list id at the top of `saveList()` and use it in the 409/resolve path.

Confidence: high. No test switches lists during a save.

---

## MEDIUM

### M1 — `deleteList()` doesn't cancel an in-flight save, resurrecting the deleted list
`server/src/main/resources/static/app/editor.js:525-529` (clear timer + `listDirty`, no `listSaving` check, no `AbortController`), `:453` (`applySavedList` assigns unconditionally), `ShoppingListRepository.kt:76` (`current == null -> true`)

If the save response arrives after the DELETE response, `applySavedList(savedA)` restores `currentList` to the just-deleted list. The pane re-renders it reading "All changes saved" while the sidebar correctly omits it (`findIndex` returns −1). One tick on that ghost pane PUTs it, the repository's `current == null` branch re-inserts the row at revision 1, and the deleted list reappears on every synced client. `ShoppingListRoutes`' DELETE sends no `If-Match`, so nothing detects the racing write.

Fix: the same generation/id guard as H2/H4 in `applySavedList`, plus have `deleteList()` await or invalidate any in-flight save.

Confidence: medium-high. Real and deterministic once the responses arrive in that order; the ordering itself is uncommon (the PUT is issued first), so likelihood is low, blast radius high.

---

### M2 — `WEB_API_AUTH` skips the account-status revalidation the JWT path enforces
`server/src/main/kotlin/com/enuvro/saltykmp/Application.kt:227-232`

`session<UserSession>(WEB_API_AUTH) { validate { it } }` — an identity validator — is now attached alongside `JWT_AUTH` on `RecipeRoutes.kt:49`, `LibraryRoutes.kt:24` and `ShoppingListRoutes.kt:33`. The JWT provider (`Auth.kt:75-91`) deliberately does `UserRepository.findById(uid) ?: return@validate null` plus a `passwordChangedAt` comparison, with a comment stating that deleting a user or resetting a password should take effect immediately.

Failure: an admin deletes user U (or resets U's password after a suspected compromise). U's open browser tab still holds a valid MAC-signed `SALTY_SESSION` cookie and the CSRF token the `/editor` page handed it. `PUT /api/recipes/{id}` succeeds — the JWT provider is skipped (no `Authorization` header), the cookie is accepted unconditionally, and `userId()` returns the deleted id. Because `user_id` has no FK, the write lands as orphaned rows. In the password-reset variant, native clients are locked out on their next request but the browser session is not, and the write propagates to every device still syncing.

Honest framing: the unvalidated-session weakness predates this branch (`WEB_AUTH` already covered shopping-list and admin POSTs). What this diff adds is the expansion of that stale credential across recipe create/update/delete, image upload, library writes and the shopping-list JSON API. Bounded by the cookie's 7-day default `Max-Age`.

Fix: give the session validator the same `findById` + `passwordChangedAt` check (needs an `issuedAt` in `UserSession`), which closes the pre-existing `WEB_AUTH` hole too. Drop the "Both are equally trusted" comment at `Auth.kt:101-102`.

---

### M3 — Merge fallback timestamp uses the JVM's local zone but is labelled `Z`
`server/src/main/kotlin/com/enuvro/saltykmp/shoppinglist/ShoppingListResolve.kt:85`

`lastModifiedDate = resolution.merged.lastModifiedDate ?: WireDate.format(java.time.LocalDateTime.now())`. `WireDate.FORMAT` appends a literal `'Z'` with no conversion, and `WireDate.nowUtc()` (`Serialization.kt:32`) exists for exactly this. It's the only bare `now()` on the server besides the cosmetic `LocalDate.now()` at `:68`.

Failure: a shopping-list row with a null `lastModifiedDate` (the column is nullable, `write()` stores `WireDate.parse(...)` with no fallback, and the browser's `resolveList` sends `local` un-stamped — see H3) makes `merged.lastModifiedDate` null, so the fallback fires. On a UTC+10 host the row is stored ten hours in the future. `ShoppingListRepository.save`'s legacy branch (`:78-88`) then computes `!sent.isBefore(stored)` = false for a revision-less client's genuine write, and returns `SaveResult.Saved(stored)` — 200 OK — so the client believes its edit was accepted and discards it. Every such write is swallowed for ten hours. West of UTC, the mirror lets a genuinely stale write win.

Fix: `WireDate.nowUtc()` (and `LocalDate.now(ZoneOffset.UTC)` at `:68`).

Confidence: the code defect is certain; the harm needs three conditions at once (non-UTC JVM zone — the shipped Docker image is UTC — plus a null-dated row plus a 409). One-token fix, so worth doing regardless.

---

### M4 — Session username is HTML-escaped into a JavaScript string literal
`server/src/main/resources/templates/editor.mustache:47`

`username: "{{username}}"` sits inside a `<script>` block. mustache.java's `HtmlEscaper` escapes only `& < > " ' = \`` and chars ≤13 — backslash passes through untouched, and HTML entities aren't decoded in script data.

Failure: usernames are unvalidated (`UserRepository.normalize` is just `trim().lowercase()`; `WebRoutes.kt` checks only non-empty/unique), so an admin can create `dom\user`. The page emits `"dom\user"`, `\u` starts a malformed Unicode escape, the whole inline script throws `SyntaxError`, `window.SALTY` is never defined, `editor.js:17` falls back to `{ csrfToken: "" }`, and `ApiCsrf.kt:44` rejects every write with 403. The editor silently becomes read-only with no diagnosable cause. Milder and always-present: `o'brien` renders as `o&#39;brien` in the "Signed in as" menu.

Not an XSS hole — `<` *is* escaped, so a `</script>` breakout is blocked. This is availability/correctness.

Fix: serialize the values as JSON server-side, or put them on a `data-` attribute where HTML escaping is the correct escaper.

---

### M5 — A live H2 database with the admin bcrypt hash is committed, and the ignore rules don't match
`server/salty-db/salty.mv.db`, `server/salty-db/salty.lock.db`

Both are tracked (`git ls-files server/salty-db/`), and `git check-ignore -v` exits 1 for both. The root `.gitignore` patterns `salty-db/salty.mv.db` and `/salty-db` each contain a non-trailing slash, so git anchors them to the repo root — they never match `server/salty-db/`, which is where `Application.kt:84`'s `jdbc:h2:file:./salty-db/salty` actually writes (Gradle's `run` working dir is `server/`). `salty.lock.db` matches no pattern at all. Added in `f493ecb` and re-committed in four later commits; both files are *currently* dirty in the working tree, so every server or Playwright run produces a binary diff.

Contents: the `USERS` schema, the `admin` row (`cf199816-4d4b-428e-b2d7-d1f03a8c42be`), one bcrypt cost-12 hash, real dev recipes and shopping-list rows, and the developer's `hostName`/`server=localhost:52197`.

Two concrete harms beyond the credential: (a) a routine `git checkout`/`stash`/`pull` overwrites a running developer's database with the committed snapshot; (b) `UserRepository.seedIfEmpty` only seeds when `Users` is empty, so a second developer cloning and running with cwd=`server/` inherits the committer's admin row and their own `SALTY_DEFAULT_PASSWORD` is silently ignored.

Both `README.md:89` and the comment at `Application.kt:80` assert this directory is "gitignored" — false for the path the server uses.

Fix: `git rm --cached -r server/salty-db/`, change the ignore rule to unanchored `salty-db/` (plus `*.lock.db`), rotate that admin password, and rewrite history if the branch hasn't been widely shared.

Confidence: facts certain. Severity is medium rather than high because it's a bcrypt hash rather than a plaintext secret and there's no evidence of production reuse; it becomes high the moment the repo is published or forked.

---

### M6 — All 33 Playwright tests report PASS, not SKIP, when no browser launches
`server/src/test/kotlin/com/enuvro/saltykmp/EditorUiTest.kt:87`

`browserOrNull()` is `runCatching { Playwright.create(); ...launch(...) }.getOrNull()`, and all 33 `@Test` methods open with `val b = browserOrNull() ?: return`. The runner is JUnit 4 (`libs.kotlin.testJunit`, no `useJUnitPlatform()`), where a bare `return` is an unconditional pass. `grep -rn "Assume" server/src/test/` returns nothing. The class KDoc at `:53-55` explicitly claims the opposite ("The whole class is skipped rather than failed").

Failure: on any machine or image that can't download/launch Chromium, `./gradlew :server:test` reports 33/33 passed, 0 skipped — byte-identical in shape to a real run. `setUp()` still runs (H2 + Netty), so the tests even burn plausible time. The sole browser coverage of ~2000 lines of `editor.js`/`editor.mustache` evaporates with no signal.

Fix: `org.junit.Assume.assumeTrue("no browser available", b != null)`.

Caveat: no CI job currently runs `:server:test` (the only workflow is `windows-installer.yml`), so this is a latent false-green rather than an actively-fooled pipeline.

---

## LOW (real, but minor)

### L1 — `revert()` closes the edit dialog instead of restoring in place
`editor.js:1058-1060` → `open()` at `:971`, which sets `mode = "read"` at `:997`; the dialog is bound `:open="mode === 'edit'"` (`editor.mustache:521`). Clicking Revert (`editor.mustache:829`) reloads the recipe *and* drops the editing session, tearing down the `x-if` subtree (expanded sections, scroll position). Revert and Done end in the same place, so "undo and keep editing" doesn't exist. That `onEditDialogHide` explicitly sets `mode = "read"` after calling `revert()` (`editor.js:1023-1025`) suggests the author believed `revert()` didn't change mode. The only Revert test (`revertingDropsAStagedImage`) asserts nothing about dialog state. Fix: give `open()` a `keepMode` parameter, or have `revert()` reload into `this.current` without going through `open()`.

### L2 — A 409 from `/resolve` itself is never retried
`editor.js:449`. `ShoppingListResolver.Result.Raced` is documented as "the caller should retry with fresh input" and `ShoppingListRoutes.kt:80` returns 409 with the current row, but the catch shows a danger toast and discards `e.data` — no `if (e.status === 409)` branch, unlike `saveList` at `:425`. Reachable only when a third write commits between the resolver's read (`ShoppingListResolve.kt:70`) and its save (`:89`), i.e. a millisecond window. Recovery is manual but automatic-ish: `listDirty` stays true and `listBase` is still correct, so the next edit re-drives the whole path. No `Raced` test exists. Fix: retry once with `e.data` as the fresh server side; add a test.

### L3 — A failed image upload reports "Save failed" after the body already saved
`editor.js:1045`. `applyImageChanges()` throws on `!resp.ok`, skipping `dirty = false` and the `this.list.splice(...)` at `:1049`. The middle column keeps the pre-save name and date while the detail pane and server show the new one, and the toast claims nothing was saved. Self-healing — `dirty` stays true and the staged file survives, so pressing Save again reconciles. Fix: move the list splice up beside `this.current = saved`, and distinguish "recipe saved, image failed" in the message.

### L4 — Decimal servings makes the whole recipe save fail with a generic 500
`editor.mustache:583` (`type="number"` with no `step`/`min`, `x-model.number`) → `Dtos.kt:47` `val servings: Int?`. Typing "2.5" produces `"servings":2.5`; `call.receive<ServerRecipe>()` throws, and the catch-all `exception<Throwable>` handler (`Application.kt:201-207`) answers 500 `{"error":"Internal error"}`. The editor toasts "Save failed: Internal error" with no hint which field is at fault. No data is lost — the write is rejected wholesale and the edits stay in the dialog. Fix: `step="1" min="0"`, round in `save()`, and add a `BadRequestException` branch to StatusPages so the real cause reaches the client and the log.

### L5 — No test asserts that ingredients and directions survive a save
`EditorUiTest.kt`. `grep -rn "stored?.ingredients"` across the test tree returns nothing. Those two are the only payload entries that do *not* come from the `...this.current` spread — they're rebuilt from separate row state in `open()` and folded back at `editor.js:1037-1038`. Break that mapping and every test still passes while a browser save blanks ingredients and directions on every device. (The fields the spread *does* carry — notes, nutrition, categories, tags, variations, prep times — are each covered by a passing test, so the original "no coverage at all" framing was too broad.) Fix: one test that edits an ingredient row, saves, and asserts both the edited rows and the untouched fixture fields (rating, yield, servings, courseId).

### L6 — `favoriteAndWantToMakeCanBeSetWhileEditing` never touches `wantToMake`
`EditorUiTest.kt:774` drives only `.fieldrow--flags wa-checkbox").first()` and `:786` asserts only `stored?.isFavorite`. `grep -rn "wantToMake" server/src/test/` returns zero hits repo-wide. The two adjacent near-identical `wa-checkbox` blocks (`editor.mustache:619-621`) invite exactly the copy-paste slip this test's name promises to catch. No live bug — the bindings are correct today. Fix: also drive `.nth(1)` and assert `stored?.wantToMake`.

### L7 — The category filter assertion can't detect a filter that stops filtering
`EditorUiTest.kt:354-358`. One recipe is seeded, and it's in the only category, so `hasCount(1)` is identical before and after the click. Changing `matchesFilter`'s category branch to `return true` leaves the test fully green while a user with 200 recipes sees all of them under a "Baking" heading. (A wrong-*field* mutation would be caught, since the fixture recipe has no tags — so the assertion isn't entirely vacuous.) The course and tag branches have no test at all. Fix: seed a second recipe outside "Baking" with its own course and tag so the counts differ, and add course/tag clicks.

### L8 — `theNutritionEditorCoversEveryFieldInTheModel` never reads the Kotlin model
`EditorUiTest.kt:940` asserts `hasCount(18)` against inputs rendered from `NUTRITION_GROUPS` — both sides derive from `editor.js`. Adding a 19th field to `NutritionInformation` without touching the JS list leaves the test green while the field is uneditable and invisible in the web editor. Fix: assert against `NutritionInformation.serializer().descriptor.elementsCount - 1` (the shared module is already on the test classpath, and no reflection dependency is needed).

### L9 — Three nested `<main>` landmarks
`editor.mustache:133` (`<main class="panes">`, unconditional, closes at `:494`) wraps `:197` and `:388`, both `<main class="detail">`. `x-show` hides one inner pane via `display:none`, leaving two exposed `main` landmarks at all times, one a descendant of the other — invalid per the `main` content model, and two unlabelled entries in the rotor. The sibling containers at `:134` and `:172` are correctly `<section aria-label=...>`. Fix: make the outer a `div`, or the inner panes `<section aria-label="Recipe">` / `aria-label="Shopping list">`.

### L10 — Ingredients and directions can't be reordered by keyboard
`editor.mustache:667`/`671` and `:706`/`709`. Reordering is bound exclusively to `x-sort` with `handle: '.grip'`, and `.grip` is an `aria-hidden` non-focusable span. `reorder()` (`editor.js:942`) has no other caller; the only keydown handlers in the template are the two Enter shortcuts. `@alpinejs/sort` has no keyboard mode. WCAG 2.1.1 / 2.5.7 failure on new UI; the workaround (retyping row text in place) is clumsy but non-destructive, and recipes are last-edit-wins so no merge identity is at stake. Fix: add move-up/move-down buttons to `.row__tools`.

### L11 — The image file input is in the tab order with no accessible name
`editor.mustache:547-548`. `<input type="file" id="imgfile" class="visually-hidden">` is deliberately off-screen rather than `display:none` "so the real file input keeps its place in the tab order" (`editor.css:351`), but has no `aria-label` and no associated `<label for="imgfile">` anywhere in the repo. The comment at `:532-534` claims "the wa-button acting as its label", which a scripted `.click()` does not achieve. Result: one invisible, unnamed tab stop before the properly-named "Choose Image…" button. Fix: one attribute, `aria-label="Recipe image file"`.

### L12 — `WEB_EDITOR.md` contradicts the code on every major claim, and one new file's KDoc is stale
`WEB_EDITOR.md` was last touched at `c37f066`, the 2nd of 18 commits in range. `:86` calls "No Playwright tests" the biggest gap (33 tests exist); `:91` lists image upload and category/tag/notes/nutrition editing as not done (all shipped); `:97` says light theme only (dark mode added); `:30` says only one thing changed on the server (`ShoppingListResolve.kt` is 103 new lines — the highest-risk new server code on the branch, and the doc steers a reviewer away from it); `:106` quotes 73 tests. `server/src/main/kotlin/com/enuvro/saltykmp/web/EditorRoutes.kt:11` still reads "The Vue/Vuetify recipe editor" in a file this diff added, though the page was rebuilt on Web Awesome + Alpine.

---

## Dropped after verification

- **"`librarySelectionFiltersTheRecipeList` cannot fail"** — partially refuted and downgraded to L7 above; the assertion does catch wrong-field/over-strict mutations, only not over-permissive ones.
- **"No test proves a save round-trips untouched fields"** — the broad claim is false (7+ tests each assert one spread-carried field post-save); the genuine, narrower gap is kept as L5.

## Notes on scope

I did not review `shared/commonMain`'s `ShoppingListMerge` itself, per the brief. The three findings that touch it (H3, M3, L2) are all about how the new server and browser code *calls* it — a stale `local` stamp, a bad fallback stamp, and an unhandled `Raced` result.

The three highest findings (H1, H2, H4) share a shape worth naming: async responses are applied to live state with no check that the state they were computed from still exists. A single generation token threaded through `saveList`/`applySavedList`/`openList`/`deleteList` would close H2, H4 and M1 together.
