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
  recipeHash,
  useCompact,
  useHashRoute,
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

  const reloadRecipes = useCallback(async () => {
    setRecipes((await api.recipes.list()) || []);
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

  /*
   * Get Info holds the RECIPE it is about rather than a boolean, because it is no longer only ever
   * about the recipe being read: a row can be right-clicked in the list without being opened. It is
   * not addressable -- a panel is about a recipe, and restoring one over a list nobody navigated to
   * is a panel with nothing behind it -- and it is mounted only while set, so a fresh open starts
   * from what is stored rather than from what the last one was left holding.
   *
   * `detail` is how much of that recipe is in hand: a list row is a summary, which carries every
   * date the panel leads with but not the last-prepared sync stamp inside Sync details. See openInfo.
   */
  const [info, setInfo] = useState(null); // { recipe, detail: full | loading | unavailable }
  const infoSerial = useRef(0);
  /* "Set as date…" from the Last prepared menu, on the same terms: the recipe it is about, mounted
     only while set so it starts from the stored date every time. A summary is enough for this one --
     the date it seeds from and the name it prints are both on the row. */
  const [lastMadeFor, setLastMadeFor] = useState(null);

  const { route, push: pushRoute, replace: replaceRoute, closeDialog: closeDialogRoute } =
    useHashRoute(DIALOGS);
  const dialog = route.dialog;
  useUnloadGuard(dirty);

  const openDialog = useCallback(
    (name) => {
      if (dialog === name) return;
      // Replace rather than push when one dialog leads to another, so Back closes the pair
      // instead of walking backwards through them one at a time.
      if (dialog) replaceRoute({ dialog: name });
      else pushRoute({ dialog: name });
    },
    [dialog, pushRoute, replaceRoute],
  );

  /* What the dialog is sitting on top of: the recipe the panes still have behind it, which is where
     closing one has to land whether it gets there by popping our entry or by replacing it. */
  const closeDialog = useCallback(
    () => closeDialogRoute({ recipeId: selectedId, editing: mode === "edit" }),
    [closeDialogRoute, mode, selectedId],
  );

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
    (proceed, onCancel) => {
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
        // Back and Forward have already moved the history by the time this asks, so staying put
        // means putting the address back -- see the route effect.
        onCancel,
      });
    },
    [ask, dirty, mode],
  );

  const applyFilter = useCallback(
    (kind, id, label) =>
      guard(() => {
        // Whether the recipe on show survives the new filter, decided before the updaters below run
        // so the address can be settled here rather than from inside one of them.
        const shown = recipes.find((r) => r.id === selectedId);
        if (!shown || !matchesFilter(shown, { kind, id })) replaceRoute({});
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
          // read, favorite and delete, and favouriting it CREATED it on the server.
          if (!selectedId) return null;
          return matchesFilter(c, { kind, id }) ? c : null;
        });
        setSelectedId((s) => {
          const c = recipes.find((r) => r.id === s);
          return c && !matchesFilter(c, { kind, id }) ? null : s;
        });
      }),
    [claimDetail, guard, recipes, replaceRoute, selectedId],
  );

  /**
   * Fetches a recipe into the detail pane.
   *
   * Called by the route effect below and nowhere else: what opens a recipe is its address, and this
   * is what that address costs.
   */
  const loadRecipe = useCallback(
    async (id, editing) => {
      const serial = claimDetail();
      // A recipe's address can arrive while the shopping lists are on screen -- pasted, or Back from
      // a list to the recipe that was open before it. The section follows the address, or the recipe
      // loads into a pane still showing a shopping list.
      setSection("recipes");
      setSelectedId(id);
      setMode("read");
      setDirty(false);
      setPane("detail");
      try {
        const loaded = await api.recipes.get(id);
        if (serial !== detailSerial.current) return;
        setCurrent(loaded);
        // The editor is entered only once there is something to edit: mounted against a null recipe
        // it falls back to the empty placeholder, so `#/recipe/<id>/edit` flashed "Select a recipe."
        // in the pane it was about to fill.
        if (editing) setMode("edit");
      } catch (e) {
        if (serial !== detailSerial.current) return;
        notify(e.message || "Could not open that recipe", "error");
        // A bookmark or a pasted link can name a recipe that has since been deleted, or one that was
        // never this account's. Nothing is open, so nothing is selected and the address says so too.
        setCurrent(null);
        setSelectedId(null);
        setPane("list"); // compact: an empty detail pane has no way back to the list
        replaceRoute({});
      }
    },
    [claimDetail, notify, replaceRoute],
  );

  /**
   * A merge or delete in Edit classifiers changed recipes as well as the list: the summaries carry
   * the classifier ids the filters read, so those reload with the classifiers. The recipe open
   * behind the dialog is re-fetched too, if it was touched -- unless it is in the editor, where the
   * draft is the reader's and cannot be replaced under them. That draft still names the old row, and
   * saving it would write that id back; saying so is the most that can be done for it.
   */
  const onLibraryRecipesTouched = useCallback(
    async ({ touchedRecipeIds }) => {
      await Promise.all([reloadClassifiers(), reloadRecipes()]);
      if (!current || !touchedRecipeIds.includes(current.id)) return;
      if (mode === "edit") {
        notify(`"${current.name}" is open in the editor with its old classifiers; reopen it to see the change.`, "warning");
        return;
      }
      const serial = detailSerial.current;
      try {
        const full = await api.recipes.get(current.id);
        if (serial === detailSerial.current) setCurrent(full);
      } catch {
        /* the list is already fresh; the pane catches up on the next open */
      }
    },
    [current, mode, notify, reloadClassifiers, reloadRecipes],
  );

  /**
   * The address and the panes, kept in step -- with the address as the one that decides.
   *
   * Every way of opening a recipe goes through the URL: a click on a row pushes it, Back and Forward
   * move it, a bookmark or a pasted link arrives with it already set, and a reload starts from it.
   * This effect is the only place that reads it and the only place that loads a recipe, so those
   * four routes into the pane are the same code and cannot drift apart. It no-ops whenever the two
   * already agree, which is most of the time -- a click sets the address, this runs once and finds
   * either the work to do or nothing to do.
   *
   * The guard is here rather than only on the buttons for the same reason: Back is a way of
   * replacing what the editor is holding, and it is not a click on anything. It has already moved
   * the history by the time this runs, so refusing means putting the address back where it was --
   * which leaves that history entry holding the editor's address rather than the one it arrived
   * with. The address always describes the screen, which is the property worth keeping; the entry
   * it is written on is not.
   */
  useEffect(() => {
    // A dialog's address replaces the recipe's while it is open (see useHashRoute), so it says
    // nothing about what is behind it.
    if (route.dialog) return;

    const restore = () => replaceRoute({ recipeId: selectedId, editing: mode === "edit" });

    if (!route.recipeId) {
      // An unsaved draft has no address of its own, so the empty route is where it lives: nothing to
      // reconcile, and closing it would throw away what the reader is typing.
      if (!selectedId) return;
      guard(() => {
        claimDetail();
        setCurrent(null);
        setSelectedId(null);
        setMode("read");
        setPane("list");
      }, restore);
      return;
    }

    if (route.recipeId !== selectedId) {
      guard(() => loadRecipe(route.recipeId, route.editing), restore);
      return;
    }

    // The same recipe, described differently: entering the editor from its own address, or leaving
    // it because Back went to the address the recipe was being read at.
    if (route.editing && mode !== "edit") {
      if (current) setMode("edit");
    } else if (!route.editing && mode === "edit") {
      guard(() => setMode("read"), () => replaceRoute({ recipeId: selectedId, editing: true }));
    }
  }, [claimDetail, current, guard, loadRecipe, mode, replaceRoute, route, selectedId]);

  /**
   * Opening a recipe is a navigation: this sets the address, and the effect above does the rest.
   *
   * A new history entry rather than a replaced one, so Back returns to the recipe the reader came
   * from. That does mean a session of browsing leaves a trail to walk back through -- which is what
   * Back means everywhere else in a browser, and the alternative is a Back that leaves the app from
   * the twentieth recipe as readily as from the first.
   */
  const showRecipe = useCallback(
    (id, editing = false) => guard(() => pushRoute({ recipeId: id, editing })),
    [guard, pushRoute],
  );

  /** Edit: a navigation for a row that is not open, and only a change of mode for the one that is. */
  const editRecipe = useCallback(
    (recipe) => {
      if (recipe.id !== selectedId) showRecipe(recipe.id, true);
      else replaceRoute({ recipeId: recipe.id, editing: true });
    },
    [replaceRoute, selectedId, showRecipe],
  );

  /* ----------------------------------------------------------------- edits -- */

  /** Opens a draft that exists only in this tab: nothing is written until Save. */
  const openDraft = useCallback(
    (draft) => {
      claimDetail(); // a recipe still loading must not land on top of the draft
      // A draft exists only in this tab, so there is no address for it -- and replacing rather than
      // pushing keeps Back from walking into a draft that was already abandoned once.
      replaceRoute({});
      setCurrent(draft);
      setSelectedId(null); // nothing in the list to highlight until it is saved
      setMode("edit");
      setPane("detail");
    },
    [claimDetail, replaceRoute],
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
        // Replaced, not pushed: the editor and the recipe it saved are one place, and a draft's
        // first save is where that place gets an address at all.
        replaceRoute({ recipeId: saved.id });
        if (imageError) notify(imageError.message || "Saved, but the photo did not", "error");
        else notify("Saved");
        return true;
      } catch (e) {
        notify(e.message || "Could not save", "error");
        return false;
      }
    },
    [claimDetail, notify, replaceRoute],
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
            ? "Thiws recipe will be removed from your library (and any device syncing to this library)."
            : `The selected ${ids.length} recipes will be removed from your library (and any device syncing to this library).`,
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
            replaceRoute({});
          }
          if (failed.length) notify(`Deleted ${gone.length}; ${failed.length} could not be`, "error");
          else notify(gone.length === 1 ? "Recipe deleted" : `Deleted ${gone.length} recipes`);
        },
      }),
    [ask, claimDetail, notify, replaceRoute, selectedId],
  );

  const deleteRecipe = useCallback(
    (recipe) =>
      ask({
        title: "Delete recipe",
        body: `“${recipe.name || "Untitled"}” will be removed from your library (and any devices syncing to this library).`,
        confirmLabel: "Delete",
        onConfirm: async () => {
          await api.recipes.remove(recipe.id);
          setRecipes((list) => list.filter((r) => r.id !== recipe.id));
          // Only the recipe being read takes the pane down with it. This is reachable from a row's
          // own menu now, and emptying the pane over a row that merely happened to be right-clicked
          // closed the recipe the reader was looking at.
          if (recipe.id === selectedId) {
            claimDetail();
            setCurrent(null);
            setSelectedId(null);
            setMode("read");
            setDirty(false);
            setPane("list"); // compact: nothing to show here, and nothing to press either
            replaceRoute({});
          }
          notify("Recipe deleted");
        },
      }),
    [ask, claimDetail, notify, replaceRoute, selectedId],
  );

  /**
   * A one-field write: favorite, want-to-make, the last-prepared date.
   *
   * Optimistic, because the control has to answer the click -- and rolled back when the write
   * fails, which it did not used to be: the heart stayed filled over a recipe the server had never
   * agreed to change.
   *
   * The row is RE-READ rather than sent from what this tab is holding. `current` is fetched when a
   * recipe is opened and never again, so a tab left open on a recipe for an hour and then
   * favorited was uploading the hour-old body over whatever had been edited elsewhere since --
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

  /** Favorite and want-to-make are one-field writes, so they patch rather than round-trip a form. */
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
   * The "last prepared" date, written on its own clock.
   *
   * `lastModifiedDate` is deliberately left alone: marking a recipe prepared is not a body edit, and
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

  /**
   * Get info on any row, whether or not it is the recipe being read.
   *
   * A list row is a summary, and a summary carries every date this panel leads with -- added,
   * modified, last prepared, the photo's stamp. The one thing it does not carry is the last-prepared
   * SYNC stamp, which lives inside Sync details, collapsed. So the panel opens on what the row
   * already knows and fills that one line in when the fetch lands, rather than making the reader
   * wait for a panel that is otherwise complete. The recipe being read is already the full row, so
   * it costs nothing at all.
   *
   * `unavailable` rather than a silent fallback when the fetch fails: the field is absent either
   * way, and "Never set" would be a claim about the recipe rather than about what is known of it.
   */
  const openInfo = useCallback(
    async (recipe) => {
      if (recipe.id === current?.id) {
        setInfo({ recipe: current, detail: "full" });
        return;
      }
      const serial = (infoSerial.current += 1);
      setInfo({ recipe, detail: "loading" });
      try {
        const full = await api.recipes.get(recipe.id);
        if (serial === infoSerial.current) setInfo({ recipe: full, detail: "full" });
      } catch {
        if (serial === infoSerial.current) setInfo({ recipe, detail: "unavailable" });
      }
    },
    [current],
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
            replaceRoute(selectedId ? { recipeId: selectedId } : {});
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
        onEdit={() => editRecipe(current)}
        onDelete={deleteRecipe}
        onGetInfo={() => openInfo(current)}
        onSetPrepared={(wire) => setPrepared(current, wire)}
        onPickLastMade={() => setLastMadeFor(current)}
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
        onSelect={showRecipe}
        selectMode={selectMode}
        onSelectMode={changeSelectMode}
        checkedIds={checkedIds}
        onCheckedChange={setCheckedIds}
        onDeleteChecked={deleteChecked}
        onNew={newRecipe}
        onImport={() => openDialog("import")}
        onShowRail={showRail}
        listStyle={listStyle}
        // One object rather than eight props: these are the row menu's actions, they are only ever
        // passed together, and every one of them takes the row it was opened on.
        rowActions={{
          href: recipeHash,
          onEdit: editRecipe,
          onGetInfo: openInfo,
          onSetPrepared: setPrepared,
          onPickLastMade: setLastMadeFor,
          onToggleFavorite: (r) => toggleFlag(r, "isFavorite"),
          onToggleWantToMake: (r) => toggleFlag(r, "wantToMake"),
          onDelete: deleteRecipe,
        }}
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
                replaceRoute({});
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

      {info ? (
        <RecipeInfoDialog
          recipe={info.recipe}
          detail={info.detail}
          onClose={() => setInfo(null)}
        />
      ) : null}

      {lastMadeFor ? (
        <LastMadeDialog
          recipe={lastMadeFor}
          onClose={() => setLastMadeFor(null)}
          onSetPrepared={(wire) => setPrepared(lastMadeFor, wire)}
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
        onRecipesTouched={onLibraryRecipesTouched}
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
