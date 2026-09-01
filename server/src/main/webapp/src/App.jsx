import { useCallback, useEffect, useMemo, useState } from "react";
import {
  FluentProvider,
  OverlayDrawer,
  Toast,
  ToastTitle,
  Toaster,
  makeStyles,
  tokens,
  useId,
  useToastController,
  webDarkTheme,
  webLightTheme,
} from "@fluentui/react-components";

import { api, deleteImage, uploadImage } from "./api";
import { matchesFilter, uuidv7, visibleRecipes, wireNow } from "./model";
import {
  readStored,
  useCompact,
  useHashDialog,
  useUnloadGuard,
  useWakeLock,
  writeStored,
} from "./hooks";
import ConfirmDialog from "./components/ConfirmDialog";
import NavRail from "./components/NavRail";
import RecipeList from "./components/RecipeList";
import RecipeDetail from "./components/RecipeDetail";
import RecipeEditor from "./components/RecipeEditor";
import ShoppingListPane from "./components/ShoppingListPane";
import {
  ImportDialog,
  ManageLibraryDialog,
  PreferencesDialog,
  UsersDialog,
} from "./components/dialogs";

const useStyles = makeStyles({
  root: {
    display: "grid",
    gridTemplateColumns: "var(--rail) var(--list) 1fr",
    height: "100vh",
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
    height: "100vh",
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
  /* An 8px grab strip along the pane's inner edge.
     Inside the pane, not straddling its border: the pane clips its overflow, so the half of a
     straddling strip that hung over the neighbour was invisible to hit-testing and the drag landed
     on the detail pane instead. */
  gutter: {
    position: "absolute",
    insetBlock: 0,
    insetInlineEnd: 0,
    width: "8px",
    cursor: "col-resize",
    zIndex: 2,
    ":hover": { backgroundColor: tokens.colorNeutralStroke1 },
  },
});

/* The rail collapses to an icon strip rather than to nothing: at zero width the toggle that
   reopens it would be unreachable. */
const RAIL_OPEN = 248;
const RAIL_COLLAPSED = 48;

const LIST_WIDTH_KEY = "salty.listWidth";
const SORT_KEY = "salty.recipeSort";
const SORT_ASC_KEY = "salty.recipeSortAsc";
const WAKE_LOCK_KEY = "salty.chefWakeLock";

const DIALOGS = ["library", "import", "preferences", "users"];

/** Follows the OS rather than offering a switch, matching what the Mustache shell does pre-paint. */
function usePrefersDark() {
  const [dark, setDark] = useState(
    () => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false,
  );
  useEffect(() => {
    const m = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = (e) => setDark(e.matches);
    m.addEventListener("change", onChange);
    return () => m.removeEventListener("change", onChange);
  }, []);
  return dark;
}

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

  /** Every confirmation and one-field prompt in the app goes through this. See ConfirmDialog. */
  const [confirmRequest, setConfirmRequest] = useState(null);
  const ask = useCallback((request) => setConfirmRequest(request), []);

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
  const [filter, setFilter] = useState({ kind: "all", id: null, label: "All Recipes" });
  const [query, setQuery] = useState("");
  const [sortBy, setSortBy] = useState(() => readStored(SORT_KEY, "name"));
  const [sortAsc, setSortAsc] = useState(() => readStored(SORT_ASC_KEY, "true") === "true");
  const [railOpen, setRailOpen] = useState(true);
  const [listWidth, setListWidth] = useState(() => Number(readStored(LIST_WIDTH_KEY, 360)) || 360);

  const [selectedId, setSelectedId] = useState(null);
  const [current, setCurrent] = useState(null);
  const [mode, setMode] = useState("read"); // read | edit
  const [dirty, setDirty] = useState(false);
  const [selectedListId, setSelectedListId] = useState(null);

  const compact = useCompact();
  /** Which single pane is on screen when compact. Ignored at full width. */
  const [pane, setPane] = useState("list"); // list | detail
  const [drawerOpen, setDrawerOpen] = useState(false);

  const [chefMode, setChefMode] = useState(false);
  const [wakeLockPref, setWakeLockPref] = useState(() => readStored(WAKE_LOCK_KEY, "1") !== "0");
  useWakeLock(chefMode && wakeLockPref);

  const [dialog, openDialog, closeDialog] = useHashDialog(DIALOGS);
  useUnloadGuard(dirty);

  useEffect(() => writeStored(SORT_KEY, sortBy), [sortBy]);
  useEffect(() => writeStored(SORT_ASC_KEY, sortAsc), [sortAsc]);
  useEffect(() => writeStored(LIST_WIDTH_KEY, listWidth), [listWidth]);
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
        setDrawerOpen(false);
        setFilter({ kind, id, label: label || "All Recipes" });
        // The detail column follows the list, as in the Swift client where detail is driven by
        // selection *within* the current list. Otherwise the right pane goes on showing a recipe
        // the middle column no longer lists, and the two quietly disagree about where you are.
        setCurrent((c) => (c && !matchesFilter(c, { kind, id }) ? null : c));
        setSelectedId((s) => {
          const c = recipes.find((r) => r.id === s);
          return c && !matchesFilter(c, { kind, id }) ? null : s;
        });
      }),
    [guard, recipes],
  );

  const openRecipe = useCallback(
    (id) =>
      guard(async () => {
        setSelectedId(id);
        setMode("read");
        setDirty(false);
        setPane("detail");
        try {
          setCurrent(await api.recipes.get(id));
        } catch (e) {
          notify(e.message || "Could not open that recipe", "error");
        }
      }),
    [guard, notify],
  );

  /* ----------------------------------------------------------------- edits -- */

  const newRecipe = useCallback(
    () =>
      guard(() => {
        const now = wireNow();
        // Both ways of making a recipe open a draft that exists only in this tab: nothing is
        // written until Save, so Cancel leaves no trace. The id is minted here because ids sort by
        // creation and this is the moment of creation, not whenever the save lands.
        setCurrent({
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
        setSelectedId(null); // nothing in the list to highlight until it is saved
        setMode("edit");
        setDirty(true);
        setPane("detail");
      }),
    [guard],
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

        if (imageFile || imageRemoved) {
          const stamp = wireNow();
          if (imageFile) {
            const { filename } = await uploadImage(saved.id, imageFile, stamp);
            saved = { ...saved, imageFilename: filename, lastModifiedImageDate: stamp };
          } else {
            await deleteImage(saved.id, stamp);
            saved = { ...saved, imageFilename: null, lastModifiedImageDate: stamp };
          }
        }

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
        notify("Saved");
        return true;
      } catch (e) {
        notify(e.message || "Could not save", "error");
        return false;
      }
    },
    [notify],
  );

  const deleteRecipe = useCallback(
    (recipe) =>
      ask({
        title: "Delete recipe",
        body: `“${recipe.name || "Untitled"}” will be removed from your library on every device.`,
        confirmLabel: "Delete",
        onConfirm: async () => {
          try {
            await api.recipes.remove(recipe.id);
            setRecipes((list) => list.filter((r) => r.id !== recipe.id));
            setCurrent(null);
            setSelectedId(null);
            setMode("read");
            setDirty(false);
            notify("Recipe deleted");
          } catch (e) {
            notify(e.message || "Could not delete", "error");
          }
        },
      }),
    [ask, notify],
  );

  /** Favourite and want-to-make are one-field writes, so they patch rather than round-trip a form. */
  const toggleFlag = useCallback(
    async (recipe, field) => {
      const value = !recipe[field];
      setRecipes((list) => list.map((r) => (r.id === recipe.id ? { ...r, [field]: value } : r)));
      setCurrent((c) => (c && c.id === recipe.id ? { ...c, [field]: value } : c));
      try {
        // List rows are summaries; send the full row rather than writing a partial one back.
        const full =
          current && current.id === recipe.id ? current : await api.recipes.get(recipe.id);
        await api.recipes.save({ ...full, [field]: value, lastModifiedDate: wireNow() });
      } catch (e) {
        notify(e.message || "Could not update", "error");
      }
    },
    [current, notify],
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
   * Dragging is tracked on the window, not on the 6px strip.
   *
   * A pointer leaves a strip that thin almost immediately, and pointer capture on it proved
   * unreliable -- the drag simply did nothing. Listening on the window for the duration is the
   * ordinary way to do this and does not depend on the cursor staying anywhere in particular.
   */
  const onGutterDown = useCallback(
    (e) => {
      e.preventDefault();
      const railWidth = railOpen ? RAIL_OPEN : RAIL_COLLAPSED;
      const onMove = (ev) =>
        setListWidth(Math.max(260, Math.min(620, ev.clientX - railWidth)));
      const onUp = () => {
        window.removeEventListener("mousemove", onMove);
        window.removeEventListener("mouseup", onUp);
      };
      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
    },
    [railOpen],
  );

  const rootStyle = {
    "--rail": `${railOpen ? RAIL_OPEN : RAIL_COLLAPSED}px`,
    "--list": `${listWidth}px`,
  };

  const detail =
    section === "lists" ? (
      <ShoppingListPane.Detail
        id={selectedListId}
        notify={notify}
        ask={ask}
        onChanged={reloadLists}
        onBack={compact ? () => setPane("list") : null}
      />
    ) : mode === "edit" && current ? (
      <RecipeEditor
        recipe={current}
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
              try {
                const created = await api.classifiers.create("tag", name);
                await reloadClassifiers();
                await then(created);
              } catch (e) {
                notify(e.message || "Could not create that tag", "error");
              }
            },
          })
        }
        onCancel={() =>
          guard(() => {
            setMode("read");
            if (!selectedId) setCurrent(null);
          })
        }
        onSave={saveRecipe}
        notify={notify}
      />
    ) : (
      <RecipeDetail
        recipe={current}
        courses={courses}
        chefMode={chefMode}
        onBack={compact ? () => setPane("list") : null}
        onEdit={() => setMode("edit")}
        onDelete={deleteRecipe}
        onToggleFavorite={(r) => toggleFlag(r, "isFavorite")}
        onToggleWantToMake={(r) => toggleFlag(r, "wantToMake")}
        onEnterChefMode={enterChefMode}
        onExitChefMode={() => setChefMode(false)}
      />
    );

  const railProps = {
    section,
    filter,
    onFilter: applyFilter,
    recipes,
    courses,
    categories,
    tags,
    shoppingLists,
    selectedListId,
    onSelectList: (id) =>
      guard(() => {
        setSection("lists");
        setSelectedListId(id);
        setPane("detail");
        setDrawerOpen(false);
      }),
    onShoppingLists: () =>
      guard(() => {
        setSection("lists");
        setSelectedListId(null);
        setPane("list");
        setDrawerOpen(false);
      }),
    onManageLibrary: () => openDialog("library"),
    onPreferences: () => openDialog("preferences"),
    onUsers: () => openDialog("users"),
  };

  const listPane =
    section === "recipes" ? (
      <RecipeList
        title={filter.label}
        rows={rows}
        loading={loading}
        query={query}
        onQuery={setQuery}
        sortBy={sortBy}
        sortAsc={sortAsc}
        onSort={(by, asc) => {
          setSortBy(by);
          setSortAsc(asc);
        }}
        selectedId={selectedId}
        onSelect={openRecipe}
        onNew={newRecipe}
        onImport={() => openDialog("import")}
        onBack={compact ? () => setDrawerOpen(true) : null}
      />
    ) : (
      <ShoppingListPane.List
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
        onBack={compact ? () => setDrawerOpen(true) : null}
      />
    );

  return (
    <FluentProvider theme={dark ? webDarkTheme : webLightTheme}>
      <div
        className={`${styles.root} ${chefMode ? styles.chef : ""} ${compact && !chefMode ? styles.compact : ""}`}
        style={chefMode || compact ? undefined : rootStyle}
      >
        {/* At full width the rail is a column; compact, it is a drawer over the one pane there is
            room for. Same component either way -- only where it is mounted changes. */}
        {compact ? (
          <OverlayDrawer open={drawerOpen} onOpenChange={(_, d) => setDrawerOpen(d.open)}>
            <NavRail open onToggle={() => setDrawerOpen(false)} {...railProps} />
          </OverlayDrawer>
        ) : chefMode ? null : (
          <NavRail open={railOpen} onToggle={() => setRailOpen((v) => !v)} {...railProps} />
        )}

        {chefMode || (compact && pane === "detail") ? null : (
          <div className={`${styles.pane} ${styles.listPane}`}>
            {listPane}
            {compact ? null : (
              <div
                className={styles.gutter}
                onMouseDown={onGutterDown}
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

      <ConfirmDialog request={confirmRequest} onClose={() => setConfirmRequest(null)} />

      <PreferencesDialog
        open={dialog === "preferences"}
        onClose={closeDialog}
        notify={notify}
        ask={ask}
        wakeLockPref={wakeLockPref}
        onWakeLockPref={setWakeLockPref}
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
        onImported={(draft) => {
          closeDialog();
          setCurrent(draft);
          setSelectedId(null);
          setMode("edit");
          setDirty(true);
        }}
      />
    </FluentProvider>
  );
}
