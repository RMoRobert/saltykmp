import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Body1,
  FluentProvider,
  Subtitle1,
  Toast,
  ToastTitle,
  Toaster,
  makeStyles,
  tokens,
  useId,
  useToastController,
} from "@fluentui/react-components";

import { api, deleteImage, uploadImage } from "./api";
import { saltyDarkTheme, saltyLightTheme } from "./theme";
import { listStyleKey, matchesFilter, uuidv7, visibleRecipes, wireNow } from "./model";
import {
  readStored,
  useCompact,
  useHashDialog,
  usePrefersDark,
  useUnloadGuard,
  useWakeLock,
  writeStored,
} from "./hooks";
import ConfirmDialog from "./components/ConfirmDialog";
import NavRail from "./components/NavRail";
import RecipeList from "./components/RecipeList";
import RecipeDetail from "./components/RecipeDetail";
import RecipeEditor from "./components/RecipeEditor";
import LastMadeDialog from "./components/LastMadeDialog";
import RecipeInfoDialog from "./components/RecipeInfoDialog";
import { ShoppingListDetail, ShoppingListsIndex } from "./components/ShoppingListPane";
import {
  ImportDialog,
  ManageLibraryDialog,
  PreferencesDialog,
  UsersDialog,
} from "./components/dialogs";

const useStyles = makeStyles({
  /* FluentProvider renders a plain div between #root and the grid, and it has no height of its own.
     Without this the grid's `height: 100%` has no definite parent to resolve against, falls back to
     `auto`, and grows to its content -- so the list column's scroller ends up exactly as tall as its
     rows, `scrollHeight === clientHeight`, nothing to scroll, and everything past the fold clipped
     by the shell's `overflow: hidden` on body. That was true at every width; a phone is only where
     you meet it first, because a phone runs out of rows immediately. */
  /* 100dvh, and it has to be a viewport unit rather than a percentage.
     
     `height: 100%` looks like the tidier answer -- the shell gives html, body and #root a height --
     but FluentProvider renders its own div in between and has none, and a percentage against an
     `auto` parent is indefinite. So this grew to its content instead, every pane with it, and the
     list column's scroller came out exactly as tall as its rows: nothing to scroll at any width,
     with the overflow clipped by body. Putting `height: 100%` on the provider instead fixes the
     chain and breaks Fluent's portalled popovers -- the Dropdown listbox opens `display: none` --
     so the height belongs here, on an element Fluent does not own.
     
     `dvh` rather than `vh` for the reason the panes used to sit behind iOS Safari's toolbar: 100vh
     is the *large* viewport and stays that tall while the toolbar is showing. `dvh` tracks what is
     actually visible. A browser too old for it drops the declaration and lands back on `auto`,
     which is where this started -- no worse, and nothing shipping is that old.
     
     The rail column is `auto` because the NavDrawer knows its own width and is simply absent when
     closed; the row is minmax(0, 1fr) so the panes can size below their content and scroll inside
     themselves. */
  root: {
    display: "grid",
    gridTemplateColumns: "auto var(--list) 1fr",
    gridTemplateRows: "minmax(0, 1fr)",
    height: "100dvh",
    overflow: "hidden",
    backgroundColor: tokens.colorNeutralBackground2,
    color: tokens.colorNeutralForeground1,
  },
  // Chef mode is the same detail pane with the other two columns taken away, rather than a separate
  // screen: the recipe on show must not reflow or re-fetch just because the panes around it went.
  chef: { gridTemplateColumns: "1fr" },
  /* Below 900px there is room for one pane, not three. Which one is on screen is state, not CSS,
     so the panes that are not showing are unmounted rather than merely hidden. */
  compact: { gridTemplateColumns: "1fr" },
  pane: {
    minWidth: 0,
    minHeight: 0,
    overflow: "hidden",
    display: "flex",
    flexDirection: "column",
  },
  listPane: {
    borderRight: `1px solid ${tokens.colorNeutralStroke2}`,
    backgroundColor: tokens.colorNeutralBackground1,
    position: "relative",
  },
  detailPane: { backgroundColor: tokens.colorNeutralBackground1 },
  bulk: {
    height: "100%",
    display: "grid",
    placeContent: "center",
    justifyItems: "center",
    gap: tokens.spacingVerticalS,
    color: tokens.colorNeutralForeground3,
  },
  /* An 8px grab strip along the pane's inner edge.
     Inside the pane, not straddling its border: the pane clips its overflow, so the half of a
     straddling strip that hung over the neighbour was invisible to hit-testing and the drag landed
     on the detail pane instead. `touch-action: none` is what lets a finger or a pen drag it. */
  gutter: {
    position: "absolute",
    insetBlock: 0,
    insetInlineEnd: 0,
    width: "8px",
    cursor: "col-resize",
    touchAction: "none",
    zIndex: 2,
    ":hover": { backgroundColor: tokens.colorNeutralStroke1 },
  },
});

const LIST_WIDTH_KEY = "salty.listWidth";
const SORT_KEY = "salty.recipeSort";
const SORT_ASC_KEY = "salty.recipeSortAsc";
const LIST_STYLE_KEY = "salty.recipeListStyle";
const WAKE_LOCK_KEY = "salty.chefWakeLock";

const DIALOGS = ["library", "import", "preferences", "users"];

export default function App() {
  const styles = useStyles();
  const dark = usePrefersDark();
  const toasterId = useId("toaster");
  const { dispatchToast } = useToastController(toasterId);

  const notify = useCallback(
    (text, intent = "success") =>
      dispatchToast(
        <Toast>
          <ToastTitle>{text}</ToastTitle>
        </Toast>,
        { intent, timeout: 3200 },
      ),
    [dispatchToast],
  );

  /**
   * Every confirmation and one-field prompt in the app goes through this. See ConfirmDialog.
   * Each request gets a serial, which is the dialog's `key`: a new request is a new dialog with
   * its own field, not the old one holding its predecessor's text.
   */
  const [confirmRequest, setConfirmRequest] = useState(null);
  const confirmSerial = useRef(0);
  const ask = useCallback((request) => {
    confirmSerial.current += 1;
    setConfirmRequest({ ...request, serial: confirmSerial.current });
  }, []);

  /* ------------------------------------------------------------------ data -- */

  const [recipes, setRecipes] = useState([]);
  const [courses, setCourses] = useState([]);
  const [categories, setCategories] = useState([]);
  const [tags, setTags] = useState([]);
  const [shoppingLists, setShoppingLists] = useState([]);
  const [loading, setLoading] = useState(true);

  const reloadClassifiers = useCallback(async () => {
    const [c, k, t] = await Promise.all([
      api.classifiers.list("course"),
      api.classifiers.list("category"),
      api.classifiers.list("tag"),
    ]);
    setCourses(c || []);
    setCategories(k || []);
    setTags(t || []);
  }, []);

  const reloadLists = useCallback(async () => {
    setShoppingLists((await api.shoppingLists.list()) || []);
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [rs, ls] = await Promise.all([api.recipes.list(), api.shoppingLists.list()]);
        if (!alive) return;
        setRecipes(rs || []);
        setShoppingLists(ls || []);
        await reloadClassifiers();
      } catch (e) {
        if (alive) notify(e.message || "Could not load your library", "error");
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [notify, reloadClassifiers]);

  /* ------------------------------------------------------------------ view -- */

  const [section, setSection] = useState("recipes"); // recipes | lists
  const [filter, setFilter] = useState({ kind: "all", id: null, label: "All recipes" });
  const [query, setQuery] = useState("");
  const [sortBy, setSortBy] = useState(() => readStored(SORT_KEY, "name"));
  const [sortAsc, setSortAsc] = useState(() => readStored(SORT_ASC_KEY, "true") === "true");
  const [railOpen, setRailOpen] = useState(true);
  const [listWidth, setListWidth] = useState(() => Number(readStored(LIST_WIDTH_KEY, 360)) || 360);
  /** How dense the recipe rows are. A fact about this browser, like the width and the sort. */
  const [listStyle, setListStyle] = useState(() => listStyleKey(readStored(LIST_STYLE_KEY, "")));

  /**
   * Which detail load is the current one.
   *
   * Every route into the detail pane bumps it, and a fetch applies its result only if it still
   * holds the latest number. Without it, clicking A then B while A was still in flight left A's
   * recipe under B's highlight -- and clicking A then `+` left the draft replaced by A.
   */
  const detailSerial = useRef(0);
  const claimDetail = useCallback(() => (detailSerial.current += 1), []);

  const [selectedId, setSelectedId] = useState(null);
  /** The checkbox set, for bulk actions. Separate from `selectedId`, which is what is being read. */
  const [checkedIds, setCheckedIds] = useState([]);
  /** Checkboxes are a mode, entered from the list's overflow menu, not a permanent column. */
  const [selectMode, setSelectMode] = useState(false);
  const [current, setCurrent] = useState(null);
  const [mode, setMode] = useState("read"); // read | edit
  /** Owned by the editor while it is mounted; see RecipeEditor for what counts. */
  const [dirty, setDirty] = useState(false);
  const [selectedListId, setSelectedListId] = useState(null);

  const compact = useCompact();
  /** Which single pane is on screen when compact. Ignored at full width. */
  const [pane, setPane] = useState("list"); // list | detail
  const [drawerOpen, setDrawerOpen] = useState(false);

  const [chefMode, setChefMode] = useState(false);
  const [wakeLockPref, setWakeLockPref] = useState(() => readStored(WAKE_LOCK_KEY, "1") !== "0");
  useWakeLock(chefMode && wakeLockPref);

  /* Get Info is about the recipe being read, and a recipe is not addressable, so this one is
     plain state rather than a hash dialog: a reload lands on the empty state, and a hash would
     restore a panel with nothing behind it. Rendered only while open, so a fresh open starts
     from what is stored rather than from what the last one was left holding. */
  const [infoOpen, setInfoOpen] = useState(false);
  /* "Set as date…" from the Last made menu, on the same terms: about the open recipe, so not
     addressable, and mounted only while open so it starts from the stored date every time. */
  const [lastMadeOpen, setLastMadeOpen] = useState(false);

  const [dialog, openDialog, closeDialog] = useHashDialog(DIALOGS);
  useUnloadGuard(dirty);

  useEffect(() => writeStored(SORT_KEY, sortBy), [sortBy]);
  useEffect(() => writeStored(SORT_ASC_KEY, sortAsc), [sortAsc]);
  useEffect(() => writeStored(LIST_WIDTH_KEY, listWidth), [listWidth]);
  useEffect(() => writeStored(LIST_STYLE_KEY, listStyle), [listStyle]);
  useEffect(() => writeStored(WAKE_LOCK_KEY, wakeLockPref ? "1" : "0"), [wakeLockPref]);

  const rows = useMemo(
    () => visibleRecipes(recipes, filter, query, sortBy, sortAsc),
    [recipes, filter, query, sortBy, sortAsc],
  );

  /**
   * Anything that would replace what the editor is holding asks first. The browser's own unload
   * prompt cannot see in-app navigation, so this is the half of the guard that catches clicking
   * another recipe, another filter, or Back.
   */
  const guard = useCallback(
    (proceed) => {
      if (!(mode === "edit" && dirty)) {
        proceed();
        return;
      }
      ask({
        title: "Discard unsaved changes?",
        body: "This recipe has edits that have not been saved.",
        confirmLabel: "Discard",
        onConfirm: () => {
          setDirty(false);
          proceed();
        },
      });
    },
    [ask, dirty, mode],
  );

  const applyFilter = useCallback(
    (kind, id, label) =>
      guard(() => {
        setSection("recipes");
        setMode("read");
        setPane("list");
        setCheckedIds([]);
        setSelectMode(false);
        setDrawerOpen(false);
        setFilter({ kind, id, label: label || "All recipes" });
        claimDetail();
        // The detail column follows the list, as in the Swift client where detail is driven by
        // selection *within* the current list. Otherwise the right pane goes on showing a recipe
        // the middle column no longer lists, and the two quietly disagree about where you are.
        setCurrent((c) => {
          if (!c) return null;
          // A draft has nothing behind it in any list. Left here it became a recipe you could
          // read, favourite and delete, and favouriting it CREATED it on the server.
          if (!selectedId) return null;
          return matchesFilter(c, { kind, id }) ? c : null;
        });
        setSelectedId((s) => {
          const c = recipes.find((r) => r.id === s);
          return c && !matchesFilter(c, { kind, id }) ? null : s;
        });
      }),
    [claimDetail, guard, recipes, selectedId],
  );

  const openRecipe = useCallback(
    (id) =>
      guard(async () => {
        const serial = claimDetail();
        setSelectedId(id);
        setMode("read");
        setDirty(false);
        setPane("detail");
        try {
          const loaded = await api.recipes.get(id);
          if (serial === detailSerial.current) setCurrent(loaded);
        } catch (e) {
          if (serial !== detailSerial.current) return;
          notify(e.message || "Could not open that recipe", "error");
          // Compact shows one pane at a time, and the detail pane has nothing to show: staying
          // here would be an empty screen with no way back to the list.
          setPane("list");
        }
      }),
    [claimDetail, guard, notify],
  );

  /* ----------------------------------------------------------------- edits -- */

  /** Opens a draft that exists only in this tab: nothing is written until Save. */
  const openDraft = useCallback(
    (draft) => {
      claimDetail(); // a recipe still loading must not land on top of the draft
      setCurrent(draft);
      setSelectedId(null); // nothing in the list to highlight until it is saved
      setMode("edit");
      setPane("detail");
    },
    [claimDetail],
  );

  const newRecipe = useCallback(
    () =>
      guard(() => {
        const now = wireNow();
        // The id is minted here because ids sort by creation and this is the moment of creation,
        // not whenever the save lands.
        openDraft({
          id: uuidv7(),
          name: "",
          createdDate: now,
          lastModifiedDate: now,
          ingredients: [],
          directions: [],
          notes: [],
          variations: [],
          preparationTimes: [],
          categoryIds: [],
          tagIds: [],
        });
      }),
    [guard, openDraft],
  );

  /**
   * Saves the body first, then the image.
   *
   * Order matters: the body PUT carries the recipe's copy of `imageFilename`, so uploading first
   * would let that stale value overwrite what the upload just set.
   */
  const saveRecipe = useCallback(
    async (draft, { imageFile, imageRemoved } = {}) => {
      try {
        const body = { ...draft, lastModifiedDate: wireNow() };
        let saved = (await api.recipes.save(body)) || body;

        // The photo is a second request, and it can fail on its own. When it does the recipe is
        // still saved, so the failure is reported and the save is not rolled back or forgotten --
        // treating it as a failed save left a recipe on the server that this tab had no record of,
        // and Cancel then orphaned it.
        let imageError = null;
        if (imageFile || imageRemoved) {
          const stamp = wireNow();
          try {
            if (imageFile) {
              const { filename } = await uploadImage(saved.id, imageFile, stamp);
              saved = { ...saved, imageFilename: filename, lastModifiedImageDate: stamp };
            } else {
              await deleteImage(saved.id, stamp);
              saved = { ...saved, imageFilename: null, lastModifiedImageDate: stamp };
            }
          } catch (e) {
            imageError = e;
          }
        }

        claimDetail();
        setRecipes((list) => {
          const i = list.findIndex((r) => r.id === saved.id);
          if (i === -1) return [...list, saved];
          const next = [...list];
          next[i] = { ...next[i], ...saved };
          return next;
        });
        setCurrent(saved);
        setSelectedId(saved.id);
        setMode("read");
        setDirty(false);
        if (imageError) notify(imageError.message || "Saved, but the photo did not", "error");
        else notify("Saved");
        return true;
      } catch (e) {
        notify(e.message || "Could not save", "error");
        return false;
      }
    },
    [claimDetail, notify],
  );

  /**
   * Leaving select mode drops the selection with it: a hidden set is a set nobody can act on.
   *
   * Entering it asks first, because select mode replaces the detail pane with its own placeholder as
   * soon as two rows are ticked -- which unmounted the editor and took the unsaved edits with it.
   */
  const changeSelectMode = useCallback(
    (on) => {
      if (!on) {
        setSelectMode(false);
        setCheckedIds([]);
        return;
      }
      guard(() => {
        setMode("read");
        setSelectMode(true);
      });
    },
    [guard],
  );

  /**
   * The search box, and the checked set it can hide.
   *
   * A row the search has filtered out is a row nobody can see they are about to delete, so the
   * checks follow what is on screen. Otherwise "3 selected" over a single visible row deleted three.
   */
  const changeQuery = useCallback(
    (value) => {
      setQuery(value);
      if (!selectMode) return;
      const visible = new Set(
        visibleRecipes(recipes, filter, value, sortBy, sortAsc).map((r) => r.id),
      );
      setCheckedIds((ids) => ids.filter((id) => visible.has(id)));
    },
    [filter, recipes, selectMode, sortAsc, sortBy],
  );

  const deleteChecked = useCallback(
    (ids) =>
      ask({
        title: ids.length === 1 ? "Delete recipe" : `Delete ${ids.length} recipes`,
        body:
          ids.length === 1
            ? "It will be removed from your library on every device."
            : `All ${ids.length} will be removed from your library on every device.`,
        confirmLabel: "Delete",
        onConfirm: async () => {
          // Sequential rather than parallel: a partial failure should leave the list showing what
          // actually survived, and the server is one small machine.
          const failed = [];
          for (const id of ids) {
            try {
              await api.recipes.remove(id);
            } catch {
              failed.push(id);
            }
          }
          const gone = ids.filter((id) => !failed.includes(id));
          setRecipes((list) => list.filter((r) => !gone.includes(r.id)));
          setCheckedIds(failed);
          if (failed.length === 0) setSelectMode(false);
          if (gone.includes(selectedId)) {
            claimDetail();
            setCurrent(null);
            setSelectedId(null);
            setMode("read");
            setPane("list"); // compact: an empty detail pane has no way back to the list
          }
          if (failed.length) notify(`Deleted ${gone.length}; ${failed.length} could not be`, "error");
          else notify(gone.length === 1 ? "Recipe deleted" : `Deleted ${gone.length} recipes`);
        },
      }),
    [ask, claimDetail, notify, selectedId],
  );

  const deleteRecipe = useCallback(
    (recipe) =>
      ask({
        title: "Delete recipe",
        body: `“${recipe.name || "Untitled"}” will be removed from your library on every device.`,
        confirmLabel: "Delete",
        onConfirm: async () => {
          await api.recipes.remove(recipe.id);
          claimDetail();
          setRecipes((list) => list.filter((r) => r.id !== recipe.id));
          setCurrent(null);
          setSelectedId(null);
          setMode("read");
          setDirty(false);
          setPane("list"); // compact: nothing to show here, and nothing to press either
          notify("Recipe deleted");
        },
      }),
    [ask, claimDetail, notify],
  );

  /**
   * A one-field write: favourite, want-to-make, the last-made date.
   *
   * Optimistic, because the control has to answer the click -- and rolled back when the write
   * fails, which it did not used to be: the heart stayed filled over a recipe the server had never
   * agreed to change.
   *
   * The row is RE-READ rather than sent from what this tab is holding. `current` is fetched when a
   * recipe is opened and never again, so a tab left open on a recipe for an hour and then
   * favourited was uploading the hour-old body over whatever had been edited elsewhere since --
   * with a fresh `lastModifiedDate`, so every other client downloaded the stale copy as the newest
   * one. One extra GET is the price of not doing that.
   */
  const patchRecipe = useCallback(
    async (recipe, patch, failure) => {
      setRecipes((list) => list.map((r) => (r.id === recipe.id ? { ...r, ...patch } : r)));
      setCurrent((c) => (c && c.id === recipe.id ? { ...c, ...patch } : c));
      try {
        const full = await api.recipes.get(recipe.id);
        const saved = await api.recipes.save({ ...full, ...patch });
        // The echo is the server's own row, so "Date modified" and Get info agree with it rather
        // than lagging until the next reload.
        if (saved) {
          setRecipes((list) => list.map((r) => (r.id === saved.id ? { ...r, ...saved } : r)));
          setCurrent((c) => (c && c.id === saved.id ? saved : c));
        }
      } catch (e) {
        const revert = {};
        for (const key of Object.keys(patch)) revert[key] = recipe[key] ?? null;
        setRecipes((list) => list.map((r) => (r.id === recipe.id ? { ...r, ...revert } : r)));
        setCurrent((c) => (c && c.id === recipe.id ? { ...c, ...revert } : c));
        notify(e.message || failure, "error");
      }
    },
    [notify],
  );

  /** Favourite and want-to-make are one-field writes, so they patch rather than round-trip a form. */
  const toggleFlag = useCallback(
    (recipe, field) =>
      patchRecipe(
        recipe,
        { [field]: !recipe[field], lastModifiedDate: wireNow() },
        "Could not update",
      ),
    [patchRecipe],
  );

  /**
   * The "last made on" date, written on its own clock.
   *
   * `lastModifiedDate` is deliberately left alone: marking a recipe made is not a body edit, and
   * bumping it would reorder every client's "Date Modified" sort. The server merges this field by
   * `lastModifiedPreparedDate` instead, so the value and its stamp have to travel together --
   * which is also what makes this safe against another device's edit landing at the same moment.
   */
  const setPrepared = useCallback(
    (recipe, wire) =>
      patchRecipe(
        recipe,
        { lastPrepared: wire, lastModifiedPreparedDate: wireNow() },
        "Could not set that date",
      ),
    [patchRecipe],
  );

  /* -------------------------------------------------------------- chef mode -- */

  /**
   * Only from a recipe that is open and being read: chef mode takes the detail bar away, so there
   * would be no way out of an editor entered underneath it, and nothing to show with no recipe.
   */
  const enterChefMode = useCallback(() => {
    if (current && mode === "read") setChefMode(true);
  }, [current, mode]);

  useEffect(() => {
    if (!chefMode) return undefined;
    const onKey = (e) => e.key === "Escape" && setChefMode(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [chefMode]);

  /* -------------------------------------------------------------- resizing -- */

  /*
   * Dragging is tracked on the window, not on the 8px strip.
   *
   * A pointer leaves a strip that thin almost immediately, and pointer capture on it proved
   * unreliable -- the drag simply did nothing. Listening on the window for the duration is the
   * ordinary way to do this and does not depend on the cursor staying anywhere in particular.
   * Pointer events rather than mouse events, so a pen or a finger on a wide tablet can drag too.
   * The width is measured from the list pane's own left edge, so it does not need to know how wide
   * the rail happens to be.
   */
  const onGutterDown = useCallback((e) => {
    e.preventDefault();
    const left = e.currentTarget.parentElement.getBoundingClientRect().left;
    const onMove = (ev) => setListWidth(Math.max(260, Math.min(620, ev.clientX - left)));
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
  }, []);

  const detail =
    // Past one there is no single thing to show, and showing whichever recipe was last opened
    // would quietly disagree with the selection beside it.
    section === "recipes" && selectMode && checkedIds.length > 1 ? (
      <div className={styles.bulk}>
        <Subtitle1>{checkedIds.length} recipes selected</Subtitle1>
        <Body1>Delete them from the list header, or leave select mode to read one.</Body1>
      </div>
    ) : section === "lists" ? (
      <ShoppingListDetail
        id={selectedListId}
        notify={notify}
        ask={ask}
        onChanged={reloadLists}
        onDeleted={() => {
          setSelectedListId(null);
          setPane("list");
        }}
        onBack={compact ? () => setPane("list") : null}
      />
    ) : mode === "edit" && current ? (
      <RecipeEditor
        key={current.id}
        recipe={current}
        unsaved={!selectedId}
        courses={courses}
        categories={categories}
        tags={tags}
        onDirtyChange={setDirty}
        onCreateTag={(then) =>
          ask({
            title: "New tag",
            prompt: "Tag name",
            confirmLabel: "Create",
            onConfirm: async (name) => {
              const created = await api.classifiers.create("tag", name);
              await reloadClassifiers();
              await then(created);
            },
          })
        }
        onCancel={() =>
          guard(() => {
            setMode("read");
            if (!selectedId) {
              setCurrent(null);
              setPane("list"); // compact: cancelling a new recipe leaves nothing to look at
            }
          })
        }
        onSave={saveRecipe}
        notify={notify}
      />
    ) : (
      <RecipeDetail
        key={current?.id ?? "none"}
        recipe={current}
        courses={courses}
        chefMode={chefMode}
        onBack={compact ? () => setPane("list") : null}
        onEdit={() => setMode("edit")}
        onDelete={deleteRecipe}
        onGetInfo={() => setInfoOpen(true)}
        onSetPrepared={(wire) => setPrepared(current, wire)}
        onPickLastMade={() => setLastMadeOpen(true)}
        onToggleFavorite={(r) => toggleFlag(r, "isFavorite")}
        onToggleWantToMake={(r) => toggleFlag(r, "wantToMake")}
        onEnterChefMode={enterChefMode}
        onExitChefMode={() => setChefMode(false)}
      />
    );

  /** How the list column brings the rail back, when it is not on screen. Null while it is. */
  const showRail = compact
    ? () => setDrawerOpen(true)
    : railOpen
      ? null
      : () => setRailOpen(true);

  const listPane =
    section === "recipes" ? (
      <RecipeList
        title={filter.label}
        rows={rows}
        loading={loading}
        query={query}
        onQuery={changeQuery}
        sortBy={sortBy}
        sortAsc={sortAsc}
        onSort={(by, asc) => {
          setSortBy(by);
          setSortAsc(asc);
        }}
        selectedId={selectedId}
        onSelect={openRecipe}
        selectMode={selectMode}
        onSelectMode={changeSelectMode}
        checkedIds={checkedIds}
        onCheckedChange={setCheckedIds}
        onDeleteChecked={deleteChecked}
        onNew={newRecipe}
        onImport={() => openDialog("import")}
        onShowRail={showRail}
        listStyle={listStyle}
      />
    ) : (
      <ShoppingListsIndex
        lists={shoppingLists}
        selectedId={selectedListId}
        onSelect={(id) =>
          guard(() => {
            setSelectedListId(id);
            setPane("detail");
          })
        }
        onChanged={reloadLists}
        notify={notify}
        ask={ask}
        onShowRail={showRail}
      />
    );

  return (
    <FluentProvider theme={dark ? saltyDarkTheme : saltyLightTheme}>
      <div
        className={`${styles.root} ${chefMode ? styles.chef : ""} ${compact && !chefMode ? styles.compact : ""}`}
        style={chefMode || compact ? undefined : { "--list": `${listWidth}px` }}
      >
        {/* At full width the rail is a column; compact, it is a drawer over the one pane there is
            room for. Same component either way -- only the drawer's type changes. */}
        {chefMode ? null : (
          <NavRail
            type={compact ? "overlay" : "inline"}
            open={compact ? drawerOpen : railOpen}
            onOpenChange={compact ? setDrawerOpen : setRailOpen}
            section={section}
            filter={filter}
            onFilter={applyFilter}
            courses={courses}
            categories={categories}
            tags={tags}
            onShoppingLists={() =>
              guard(() => {
                setSection("lists");
                setSelectedListId(null);
                setPane("list");
                setDrawerOpen(false);
                claimDetail();
                setMode("read");
                if (!selectedId) setCurrent(null); // an unsaved draft has nothing behind it
              })
            }
            onManageLibrary={() => openDialog("library")}
            onPreferences={() => openDialog("preferences")}
            onUsers={() => openDialog("users")}
          />
        )}

        {chefMode || (compact && pane === "detail") ? null : (
          <div className={`${styles.pane} ${styles.listPane}`}>
            {listPane}
            {compact ? null : (
              <div
                className={styles.gutter}
                onPointerDown={onGutterDown}
                role="separator"
                aria-orientation="vertical"
                aria-label="Resize recipe list"
              />
            )}
          </div>
        )}

        {compact && pane === "list" && !chefMode ? null : (
          <div className={`${styles.pane} ${styles.detailPane}`}>{detail}</div>
        )}
      </div>

      <Toaster toasterId={toasterId} position="bottom-end" />

      <ConfirmDialog
        key={confirmRequest?.serial}
        request={confirmRequest}
        onClose={() => setConfirmRequest(null)}
        onError={(e) => notify(e.message || "That did not work", "error")}
      />

      {infoOpen && current ? (
        <RecipeInfoDialog recipe={current} onClose={() => setInfoOpen(false)} />
      ) : null}

      {lastMadeOpen && current ? (
        <LastMadeDialog
          recipe={current}
          onClose={() => setLastMadeOpen(false)}
          onSetPrepared={(wire) => setPrepared(current, wire)}
        />
      ) : null}

      <PreferencesDialog
        open={dialog === "preferences"}
        onClose={closeDialog}
        notify={notify}
        ask={ask}
        wakeLockPref={wakeLockPref}
        onWakeLockPref={setWakeLockPref}
        listStyle={listStyle}
        onListStyle={setListStyle}
      />
      <UsersDialog open={dialog === "users"} onClose={closeDialog} notify={notify} ask={ask} />
      <ManageLibraryDialog
        open={dialog === "library"}
        onClose={closeDialog}
        courses={courses}
        categories={categories}
        tags={tags}
        recipes={recipes}
        onChanged={reloadClassifiers}
        notify={notify}
        ask={ask}
      />
      <ImportDialog
        open={dialog === "import"}
        onClose={closeDialog}
        notify={notify}
        // Through the guard like every other way of replacing what the editor holds. An import
        // used to go straight to openDraft, so an edit in progress vanished without a word.
        onImported={(draft) =>
          guard(() => {
            closeDialog();
            openDraft(draft);
          })
        }
      />
    </FluentProvider>
  );
}
