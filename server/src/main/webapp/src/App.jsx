import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  FluentProvider,
  Toaster,
  makeStyles,
  tokens,
  useId,
  useToastController,
  Toast,
  ToastTitle,
  webDarkTheme,
  webLightTheme,
} from "@fluentui/react-components";

import { api } from "./api";
import { matchesFilter, visibleRecipes, uuidv7, wireNow } from "./model";
import NavRail from "./components/NavRail";
import RecipeList from "./components/RecipeList";
import RecipeDetail from "./components/RecipeDetail";
import RecipeEditor from "./components/RecipeEditor";
import ShoppingListPane from "./components/ShoppingListPane";
import { AboutDialog, ManageLibraryDialog, PreferencesDialog, ImportDialog } from "./components/dialogs";

const useStyles = makeStyles({
  root: {
    display: "grid",
    gridTemplateColumns: "var(--rail) var(--list) 1fr",
    height: "100vh",
    overflow: "hidden",
    backgroundColor: tokens.colorNeutralBackground2,
    color: tokens.colorNeutralForeground1,
  },
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
  /* A 6px grab strip straddling the border, so the divider is easy to hit without being visible. */
  gutter: {
    position: "absolute",
    insetBlock: 0,
    insetInlineEnd: "-3px",
    width: "6px",
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

const readStored = (k, fallback) => {
  try {
    const v = localStorage.getItem(k);
    return v === null ? fallback : v;
  } catch {
    return fallback;
  }
};
const writeStored = (k, v) => {
  try {
    localStorage.setItem(k, String(v));
  } catch {
    /* private mode: a remembered width is not worth an exception */
  }
};

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
  const [dialog, setDialog] = useState(null); // about | preferences | library | import
  const [selectedListId, setSelectedListId] = useState(null);

  useEffect(() => writeStored(SORT_KEY, sortBy), [sortBy]);
  useEffect(() => writeStored(SORT_ASC_KEY, sortAsc), [sortAsc]);
  useEffect(() => writeStored(LIST_WIDTH_KEY, listWidth), [listWidth]);

  const rows = useMemo(
    () => visibleRecipes(recipes, filter, query, sortBy, sortAsc),
    [recipes, filter, query, sortBy, sortAsc],
  );

  const applyFilter = useCallback(
    (kind, id, label) => {
      setSection("recipes");
      setFilter({ kind, id, label: label || "All Recipes" });
      // The detail column follows the list, as in the Swift client where detail is driven by
      // selection *within* the current list. Otherwise the right pane goes on showing a recipe the
      // middle column no longer lists, and the two quietly disagree about where you are.
      setCurrent((c) => (c && !matchesFilter(c, { kind, id }) ? null : c));
      setSelectedId((s) => {
        const c = recipes.find((r) => r.id === s);
        return c && !matchesFilter(c, { kind, id }) ? null : s;
      });
    },
    [recipes],
  );

  const openRecipe = useCallback(
    async (id) => {
      setSelectedId(id);
      setMode("read");
      try {
        const full = await api.recipes.get(id);
        setCurrent(full);
      } catch (e) {
        notify(e.message || "Could not open that recipe", "error");
      }
    },
    [notify],
  );

  /* ----------------------------------------------------------------- edits -- */

  const newRecipe = useCallback(() => {
    const now = wireNow();
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
  }, []);

  const saveRecipe = useCallback(
    async (draft) => {
      const body = { ...draft, lastModifiedDate: wireNow() };
      try {
        const saved = (await api.recipes.save(body)) || body;
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
    async (id) => {
      try {
        await api.recipes.remove(id);
        setRecipes((list) => list.filter((r) => r.id !== id));
        setCurrent(null);
        setSelectedId(null);
        setMode("read");
        notify("Recipe deleted");
      } catch (e) {
        notify(e.message || "Could not delete", "error");
      }
    },
    [notify],
  );

  /** Favourite and want-to-make are one-field writes, so they patch rather than round-trip a form. */
  const toggleFlag = useCallback(
    async (recipe, field) => {
      const next = { ...recipe, [field]: !recipe[field], lastModifiedDate: wireNow() };
      setRecipes((list) => list.map((r) => (r.id === next.id ? { ...r, [field]: next[field] } : r)));
      setCurrent((c) => (c && c.id === next.id ? { ...c, [field]: next[field] } : c));
      try {
        // The list rows are summaries; send the full row the server last gave us for this recipe.
        const full = current && current.id === recipe.id ? current : await api.recipes.get(recipe.id);
        await api.recipes.save({ ...full, [field]: next[field], lastModifiedDate: next.lastModifiedDate });
      } catch (e) {
        notify(e.message || "Could not update", "error");
      }
    },
    [current, notify],
  );

  /* -------------------------------------------------------------- resizing -- */

  const dragging = useRef(false);
  const onGutterDown = (e) => {
    dragging.current = true;
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onGutterMove = (e) => {
    if (!dragging.current) return;
    const railWidth = railOpen ? RAIL_OPEN : RAIL_COLLAPSED;
    setListWidth(Math.max(260, Math.min(620, e.clientX - railWidth)));
  };
  const onGutterUp = () => {
    dragging.current = false;
  };

  const rootStyle = {
    "--rail": `${railOpen ? RAIL_OPEN : RAIL_COLLAPSED}px`,
    "--list": `${listWidth}px`,
  };

  return (
    <FluentProvider theme={dark ? webDarkTheme : webLightTheme}>
      <div className={styles.root} style={rootStyle}>
        <NavRail
          open={railOpen}
          onToggle={() => setRailOpen((v) => !v)}
          section={section}
          filter={filter}
          onFilter={applyFilter}
          onShoppingLists={() => setSection("lists")}
          recipes={recipes}
          courses={courses}
          categories={categories}
          tags={tags}
          shoppingLists={shoppingLists}
          selectedListId={selectedListId}
          onSelectList={(id) => {
            setSection("lists");
            setSelectedListId(id);
          }}
          onManageLibrary={() => setDialog("library")}
          onPreferences={() => setDialog("preferences")}
          onAbout={() => setDialog("about")}
        />

        <div className={`${styles.pane} ${styles.listPane}`}>
          {section === "recipes" ? (
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
              onImport={() => setDialog("import")}
              onToggleFavorite={(r) => toggleFlag(r, "isFavorite")}
            />
          ) : (
            <ShoppingListPane.List
              lists={shoppingLists}
              selectedId={selectedListId}
              onSelect={setSelectedListId}
              onChanged={async () => setShoppingLists((await api.shoppingLists.list()) || [])}
              notify={notify}
            />
          )}
          <div
            className={styles.gutter}
            onPointerDown={onGutterDown}
            onPointerMove={onGutterMove}
            onPointerUp={onGutterUp}
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize recipe list"
          />
        </div>

        <div className={`${styles.pane} ${styles.detailPane}`}>
          {section === "lists" ? (
            <ShoppingListPane.Detail id={selectedListId} notify={notify} />
          ) : mode === "edit" && current ? (
            <RecipeEditor
              recipe={current}
              courses={courses}
              categories={categories}
              tags={tags}
              onCancel={() => {
                setMode("read");
                if (!selectedId) setCurrent(null);
              }}
              onSave={saveRecipe}
            />
          ) : (
            <RecipeDetail
              recipe={current}
              courses={courses}
              onEdit={() => setMode("edit")}
              onDelete={deleteRecipe}
              onToggleFavorite={(r) => toggleFlag(r, "isFavorite")}
              onToggleWantToMake={(r) => toggleFlag(r, "wantToMake")}
            />
          )}
        </div>
      </div>

      <Toaster toasterId={toasterId} position="bottom-end" />

      <AboutDialog open={dialog === "about"} onClose={() => setDialog(null)} />
      <PreferencesDialog open={dialog === "preferences"} onClose={() => setDialog(null)} notify={notify} />
      <ManageLibraryDialog
        open={dialog === "library"}
        onClose={() => setDialog(null)}
        courses={courses}
        categories={categories}
        tags={tags}
        recipes={recipes}
        onChanged={reloadClassifiers}
        notify={notify}
      />
      <ImportDialog
        open={dialog === "import"}
        onClose={() => setDialog(null)}
        notify={notify}
        onImported={(draft) => {
          setDialog(null);
          setCurrent(draft);
          setSelectedId(null);
          setMode("edit");
        }}
      />
    </FluentProvider>
  );
}
