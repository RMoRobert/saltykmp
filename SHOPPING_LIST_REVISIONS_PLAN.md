# Shopping lists: web editing + revision-based conflict detection

Designed and implemented 2026-08-13. Covers the server (salty_kmp), the KMP shared module + web
UI (salty_kmp), and the Swift app (../Salty). Supersedes the "whole-row LWW, no conflict
detection" decision in Salty's FEATURE_PLANS.md §"Shopping Lists".

**Implementation notes (deviations from the sketch below):**
- Uploads that expect NO server row (re-create after a server delete, never-synced rows) send
  `baseRevision: 0` rather than omitting it — an insert still sails through, but a concurrent
  re-creation of the same id 409s into a merge instead of taking the legacy LWW path.
- `syncedLastModifiedDate` was dropped: the snapshot already carries it, so dirtiness is
  `row.lastModifiedDate != snapshot.lastModifiedDate` with no extra column.
- Web checklist editing shipped as semantic per-item POSTs plus a `?edit=<itemId>` inline edit
  form (no JS); only the freeform textarea uses baseRevision + the 409 conflict banner.
- The key test surfaces: `SaltyServerTest` (conditional save / 409 / If-Match / web flows),
  `ShoppingListMergeTest` (merge vectors, mirrored in Swift), `SyncIntegrationTest`
  (dirty×serverChanged matrix, conflict copies, legacy seeding, If-Match delete refusal),
  `DatabaseMigrationTest` (SHARED-V0003).

## Why

`lastModifiedDate` is stamped by the *editing device at edit time*, so the server copy's
timestamp says nothing about *when the server row last changed hands*. A device that edited
offline and syncs late can silently clobber another device's (or the web UI's) newer edit, and
no client can distinguish "server moved ahead of me" (fast-forward) from "we both diverged"
(conflict). Two additional concrete defects today:

- `ShoppingListRepository.upsert` is unconditional — any racing write clobbers.
- Delete-by-absence uses the same edit-time timestamps, so a late-arriving old edit can be
  misread as "unchanged since my last sync" and deleted server-side.

## Design in one paragraph

The server owns a per-row integer `revision`, incremented on every accepted write. Clients
remember, per list, the `syncedRevision` and a `syncedSnapshot` (the wire JSON of the row as
last agreed with the server). **Local dirty** = row differs from snapshot (no clocks). **Server
changed** = server revision ≠ syncedRevision (no clocks). Both true → real conflict: checklists
three-way merge by item id against the snapshot; freeform lists resolve as "server wins +
conflicted-copy list preserving the local text". Uploads carry `baseRevision`; the server
rejects mismatches with **409 + current row**, which is also what makes web editing safe
(stale browser tab, or a sync racing a web save). Legacy clients that don't send
`baseRevision` keep working under today's LWW, but gated by a new server-side
newer-timestamp guard so they can no longer clobber with stale data.

Timestamps stay on the wire and in the schema — they're still the tie-breaker inside merges and
the display value — but no correctness decision between two *machines* rests on comparing two
different clocks anymore.

---

## 1. Wire protocol (shared/src/commonMain/…/api/Dtos.kt + Swift SaltySyncService.swift)

```kotlin
@Serializable
data class ServerShoppingList(
    val id: String,
    val name: String? = null,
    val isFreeform: Boolean? = null,
    @Serializable(with = LenientShoppingListItems::class)
    val contentsForList: List<ShoppingListListContents>? = null,
    val contentsForFreeform: String? = null,
    val lastModifiedDate: String? = null,
    // NEW — both optional so every existing client still round-trips:
    val revision: Long? = null,      // server-owned; present on every GET / save response
    val baseRevision: Long? = null,  // client → server on upload: the revision this edit is based on
)
```

Compatibility: KMP's `apiJson` has `ignoreUnknownKeys = true`; the Swift DTO's hand-written
`init(from:)` reads only its known `CodingKeys`. So old clients of both kinds silently drop the
new fields and take the legacy path. `explicitNulls = false` / Swift `encodeIfPresent` keep the
fields off the wire when unset.

Swift mirror (SaltySyncService.swift:2423): add `var revision: Int64?`, `var baseRevision:
Int64?`, add both to `CodingKeys`, decode with `decodeIfPresent` in the custom `init(from:)`.

## 2. Server (salty_kmp/server)

### Schema — Tables.kt + DatabaseFactory.kt

```kotlin
object ShoppingLists : Table("shopping_list") {
    // ... existing columns ...
    val revision = long("revision").default(1)
}
```

`SchemaUtils.create` only creates missing *tables*, so existing deployments need one idempotent
statement in `DatabaseFactory.init` right after `SchemaUtils.create(...)` (Postgres and H2 both
support it):

```kotlin
exec("ALTER TABLE shopping_list ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1")
```

### Repository — conditional save (replaces `upsert`)

```kotlin
sealed interface SaveResult {
    data class Saved(val list: ServerShoppingList) : SaveResult
    data class Conflict(val current: ServerShoppingList) : SaveResult
}

suspend fun save(userId: String, incoming: ServerShoppingList): SaveResult = dbQuery {
    // FOR UPDATE serializes racing writers on the same row for the rest of this transaction.
    val current = ShoppingLists.selectAll()
        .where { (ShoppingLists.id eq incoming.id) and (ShoppingLists.userId eq userId) }
        .forUpdate().limit(1).singleOrNull()

    val currentRevision = current?.get(ShoppingLists.revision)
    val accepted = when {
        current == null -> true                                    // brand-new row
        incoming.baseRevision != null ->                           // revision-aware client
            incoming.baseRevision == currentRevision
        else -> {                                                  // legacy client: guarded LWW
            val stored = current[ShoppingLists.lastModifiedDate]
            val sent = WireDate.parse(incoming.lastModifiedDate)
            stored == null || (sent != null && !sent.isBefore(stored))
        }
    }
    if (!accepted) {
        val row = current!!.toDto()
        return@dbQuery if (incoming.baseRevision != null) SaveResult.Conflict(row)
        // Legacy stale write: ignore it but answer 200 with the winning row — legacy clients
        // abort the ENTIRE sync on any non-2xx, so a 409 here would brick them.
        else SaveResult.Saved(row)
    }
    val newRevision = (currentRevision ?: 0L) + 1L
    ShoppingLists.upsert { /* existing column writes */ ; it[revision] = newRevision }
    SaveResult.Saved(incoming.copy(revision = newRevision, baseRevision = null))
}
```

Add a `mutate` helper for the web UI's semantic item actions (toggle/add/delete an item), which
never need a baseRevision because they transform the *current* row inside the same FOR UPDATE
transaction:

```kotlin
suspend fun mutate(userId: String, id: String, transform: (ServerShoppingList) -> ServerShoppingList): ServerShoppingList?
// load FOR UPDATE → transform → stamp lastModifiedDate = WireDate.nowUtc() → revision + 1 → return saved
```

### Routes — ShoppingListRoutes.kt

- `POST /api/shoppingLists` and `PUT /api/shoppingLists/{id}`: on `Saved` respond as today
  (201/200) with the row (now carrying the new `revision`); on `Conflict` respond **409 with the
  current server row as the body** — one round trip gives the client everything it needs to merge.
- `DELETE /api/shoppingLists/{id}`: honor an optional `If-Match: <revision>` header. Mismatch →
  409 + current row (the client downloads it instead of deleting — edit beats delete). No header →
  unconditional delete, exactly today's behavior for legacy clients.
- `GET`s are unchanged apart from the extra field.

## 3. Local schema — SHARED-V0003 (both apps, one shared DB file)

The on-device SQLite is opened by both the Swift app (GRDB, by-name) and KMP (SQLDelight,
positional `SELECT *`), so per the ledger convention: append columns at the END, guarded
ALTERs, **identical id `"SHARED-V0003"` in both apps**, and also add the columns to the
fresh-DB CREATEs (KMP `Schema.sq`; the Swift base migrator is frozen at 0001–0004, its fresh
DBs get the columns from the shared migration on first open, same as SHARED-V0002 did).

```sql
ALTER TABLE "shoppingList" ADD COLUMN "syncedRevision" INTEGER;  -- server revision at last agreement; NULL = never synced under v2
ALTER TABLE "shoppingList" ADD COLUMN "syncedSnapshot" TEXT;     -- wire-JSON ServerShoppingList as last agreed with the server
```

- KMP: `SharedMigration("SHARED-V0003")` in Database.kt (guarded by `columnExists`, mirroring
  SHARED-V0002) + the two columns appended in Schema.sq's CREATE TABLE. Keep `syncedSnapshot`
  as plain `TEXT` (no SQLDelight adapter) — the sync layer decodes it with `apiJson`, and the
  snapshot deliberately uses the *wire* encoding so one serializer covers both.
- Swift: `SaltySharedMigration(id: "SHARED-V0003")` in Schema.swift (same guarded-ALTER shape
  as SHARED-V0002, lines ~804–812), and `ShoppingList` gains
  `var syncedRevision: Int64?` / `var syncedSnapshot: String?`.
- `Queries.sq`: `selectAllShoppingLists` is `SELECT *` and picks the columns up automatically;
  `upsertShoppingList` gains the two columns; add
  `markShoppingListSynced: UPDATE shoppingList SET syncedRevision = ?, syncedSnapshot = ? WHERE id = ?;`

Why a snapshot instead of a dirty flag: **none of the five Swift edit paths change.** They
already stamp `lastModifiedDate = Date()`; dirtiness is derived as
`row.lastModifiedDate != snapshot.lastModifiedDate` — the device comparing its own clock to its
own recorded value, which is skew-proof. (Cheap check first, full field compare as fallback if
paranoid.) The snapshot doubles as the *base* for three-way merge, which a flag can't do.
Swift's `coalesceNullShoppingListColumns` stamping NULL timestamps to CURRENT_TIMESTAMP can
only produce a false *dirty*, which is the safe direction (an extra upload/merge, never loss).

Mixed old/new builds on the same DB file: an old KMP build's positional `SELECT *` reads
columns 0–5 and never sees the appended ones; its `INSERT OR REPLACE` writes NULL into them,
which just demotes that row to the legacy-seeding path on the next v2 sync. Harmless.

## 4. Sync algorithm v2 (shared/src/…/sync/SyncService.kt `syncShoppingLists`, mirrored in Swift `syncShoppingListsWithDeletions`)

Fetch the complete server list (rows now carry `revision`). For each id present on **both**
sides, replace the timestamp compare with:

```
dirty         = snapshot == null ? LEGACY-SEED : local.lastModifiedDate != snapshot.lastModifiedDate
serverChanged = server.revision != local.syncedRevision

neither          → nothing
dirty only       → upload(baseRevision = syncedRevision)
                   → Saved(rev): markSynced(rev, snapshot = uploaded row)
                   → Conflict(current): fall through to "both" using `current` as server side
serverChanged    → download; markSynced(server.revision, snapshot = server row)
both (CONFLICT)  → resolve = ShoppingListMerge.resolve(base = snapshot, local, server)
                   upload(resolve.merged, baseRevision = server.revision)
                   → Saved(rev): upsert merged locally; markSynced(rev, merged)
                   → Conflict: one refetch + re-merge; still conflicting → leave for next sync
                   resolve.conflictCopy? → insert locally as NEW list + POST to server
```

LEGACY-SEED (first v2 sync of a pre-existing row, snapshot == null): do exactly today's
timestamp compare once to pick a direction, then record `syncedRevision` + `syncedSnapshot` so
every later sync is revision-based. One sync's worth of old semantics, no migration backfill.

Absence branches (row on only one side) keep today's watermark logic *except*:
`toDeleteOnServer` sends `If-Match: <revision just fetched>` — a 409 means the row changed
under us, so download the 409 body instead of deleting. (True tombstones for lists are a
possible later phase — FEATURE_PLANS.md explicitly accepted resurrect-on-remote-edit, and
revisions don't force reopening that.)

`SyncResult` gains `conflictsMerged` / `conflictCopies` counts, shown by `summary()` — that's
the only thing the CMP app needs (it has no list UI; `AutoSyncManager` already surfaces the
summary string).

### ShoppingListMerge (new, pure, in shared commonMain; line-for-line port in Swift)

```kotlin
object ShoppingListMerge {
    data class Resolution(val merged: ServerShoppingList, val conflictCopy: ServerShoppingList?)
    fun resolve(base: ServerShoppingList?, local: ServerShoppingList, server: ServerShoppingList): Resolution
}
```

- **name / scalar fields**: the side that differs from base wins; both differ → newer
  `lastModifiedDate` wins (clocks demoted to tie-breaker).
- **checklist items** (three-way by item `id` — they already have stable ids):
  - in both branches: unchanged vs base → keep; one side changed → take it; both changed →
    field-level (text: newer row's; `isCompleted`/`isImportant`: OR — checked/flagged wins).
  - in base + one branch (other deleted it): deleted, *unless* the survivor was edited since
    base (edit beats delete).
  - new in a branch: keep. Order: server's order as the spine, local-only additions inserted
    after their nearest surviving local predecessor (else appended).
- **freeform** (markdown blob): both changed → no merge; `merged` = server text, `conflictCopy`
  = local text as a new list named `"<name> (conflicted copy 2026-08-13)"` with a fresh id.
  Nothing is ever silently discarded.

## 5. Web UI (server/src/…/web/WebRoutes.kt + templates)

Editing splits by mechanism so checklist edits can never lose an update:

- **Checklist = semantic per-item POSTs**, no baseRevision needed (each one is a `mutate` on
  the current row, keyed by item id):
  `POST /shoppingLists/{id}/items/toggle|add|delete|edit`, `POST /shoppingLists/{id}/rename`,
  `POST /shoppingLists` (create), `POST /shoppingLists/{id}/delete`. Plain form posts +
  redirect back to the detail page; works without JS, matches the Mustache setup.
- **Freeform = whole-document textarea**, hidden `baseRevision` field.
  `POST /shoppingLists/{id}/freeform` → on 409 re-render the form with a "changed while you
  were editing" banner showing the current server text alongside the user's submitted text
  (their text stays in the textarea — nothing lost).

Every web write goes through `ShoppingListRepository.mutate`/`save`, so it stamps
`lastModifiedDate = WireDate.nowUtc()` and bumps `revision` exactly like an API write; clients
pick it up on their next sync as an ordinary `serverChanged`.

Templates: extend `shoppingListDetail.mustache` (checkboxes as one-input forms, add-item row,
per-item delete; freeform textarea variant), keep the read-only rendering for the browse page.

## 6. Swift app changes (../Salty) — summary

| Where | Change |
|---|---|
| `Salty/Models/Schema.swift` | `ShoppingList` + `syncedRevision`/`syncedSnapshot`; `SaltySharedMigration("SHARED-V0003")` guarded ALTERs |
| `Salty/Helpers/SaltySyncService.swift` | DTO fields + CodingKeys; rewrite `syncShoppingListsWithDeletions` per §4 (drop `RecipeSyncReconciler` for lists only); handle 409 on PUT/POST/DELETE |
| `Salty/Helpers/ShoppingListMerge.swift` (new) | Port of the merge, same test vectors as the KMP version |
| Edit/create/rename view-models | **No changes** (snapshot-based dirty detection) |
| `SaltyTests` | Merge tests; sync tests for the four dirty×serverChanged cells + 409 retry + legacy seed; `SaltySharedMigrationTests` case for V0003 |
| `FEATURE_PLANS.md` | Record that revisions supersede the whole-row-LWW/§"no conflict detection" decision |

## 7. Rollout order (each step safe with everything older)

1. **Server**: column + conditional save + 409-for-baseRevision + If-Match delete. Old clients
   never send `baseRevision`, so they only ever see today's status codes; they just stop being
   able to clobber with stale data.
2. **salty_kmp**: shared module (schema V0003, DTO, merge, SyncService) + web editing.
3. **Swift app**: mirrored migration + sync rewrite. Until it ships, Swift keeps legacy LWW —
   its writes still bump `revision` server-side, so revision-aware clients see them correctly.

## 8. Tests

- **Server** (`SaltyServerTest`): baseRevision match → saved+incremented; mismatch → 409 with
  current row; legacy stale write → 200 + winner, row untouched; legacy newer write → saved;
  DELETE If-Match mismatch → 409; two racing saves → exactly one wins (FOR UPDATE).
- **Shared** (`ShoppingListMergeTest`, pure): item add/add, edit/delete, both-edit-same-item,
  check-race (OR), ordering, freeform conflicted copy, name-only vs items-only cross-edits
  (must merge cleanly with NO conflict copy).
- **Shared** (`SyncIntegrationTest`): the four-cell matrix; upload-409-then-merge retry;
  legacy seeding; old-build row (NULL synced columns) reseeds without loss.

## Explicitly out of scope (deliberate)

- Recipes and vocab tables stay on the existing scheme — the pattern generalizes if recipe
  web-editing happens later.
- No shopping-list tombstones (documented FEATURE_PLANS decision stands; If-Match narrows the
  dangerous window).
- No per-item server-side identity/normalization — merge works on the JSON blob via item ids.
