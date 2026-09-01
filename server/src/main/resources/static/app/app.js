/*
 * The Salty web app — Alpine + Web Awesome.
 *
 * Talks to the same JSON API the native clients use (/api/recipes), authenticated by the web session
 * cookie rather than a Bearer token. Every state-changing call echoes the session's CSRF token; see
 * ApiCsrfGuard on the server.
 *
 * No build step: Alpine and Web Awesome come from a CDN (see app.mustache).
 *
 * Note there is no x-model workaround here. Shoelace 2 fired only sl-input/sl-change, which broke
 * Alpine's two-way binding; Web Awesome 3 emits standard `input` and `change` alongside its wa-*
 * events, so x-model works in both directions. Verified, not assumed.
 */
function saltyApp() {
  "use strict";

  // Read from data attributes rather than a generated script literal; see the note in the template.
  const SALTY = (() => {
    const el = document.getElementById("salty-config");
    const d = el ? el.dataset : {};
    return {
      csrfToken: d.csrf || "",
      username: d.username || "",
      isAdmin: d.isAdmin === "true",
      version: d.version || "",
      buildTime: d.buildTime || "",
      // Server-supplied so the form can state the rule up front rather than discovering it in a 400.
      minPasswordLength: Number(d.minPasswordLength) || 8,
    };
  })();

  const SCALES = [0.5, 1, 1.5, 2, 3, 4];

  // Mirrors the server: ImageStore identifies the format from the BYTES and stores only these
  // three, and MAX_IMAGE_UPLOAD_BYTES caps the request. Checking here too turns a 25 MB round trip
  // that ends in 415 into an immediate, specific message.
  const IMAGE_TYPES = ["image/jpeg", "image/png", "image/gif"];
  const MAX_IMAGE_BYTES = 25 * 1024 * 1024;

  /**
   * Nutrition fields in the order, wording and grouping the Swift editor uses (NutritionEditView).
   * One list drives both the editor and the reading view so the two can't drift, and so a field
   * added to the model shows up in both places by being named once.
   */
  const NUTRITION_GROUPS = [
    { group: "General", fields: [
      { key: "servingSize", label: "Serving Size", text: true, placeholder: "e.g., 1 cup, 2 slices" },
      { key: "calories", label: "Calories" },
    ] },
    { group: "Macronutrients", fields: [
      { key: "protein", label: "Protein", unit: "g" },
      { key: "carbohydrates", label: "Carbohydrates", unit: "g" },
      { key: "fat", label: "Total Fat", unit: "g" },
      { key: "saturatedFat", label: "Saturated Fat", unit: "g" },
      { key: "transFat", label: "Trans Fat", unit: "g" },
      { key: "fiber", label: "Fiber", unit: "g" },
      { key: "sugar", label: "Sugar", unit: "g" },
      { key: "addedSugar", label: "Added Sugar", unit: "g" },
    ] },
    { group: "Other Nutrients", fields: [
      { key: "sodium", label: "Sodium", unit: "mg" },
      { key: "cholesterol", label: "Cholesterol", unit: "mg" },
    ] },
    { group: "Vitamins and Minerals", fields: [
      { key: "vitaminD", label: "Vitamin D", unit: "\u03bcg" },
      { key: "calcium", label: "Calcium", unit: "mg" },
      { key: "iron", label: "Iron", unit: "mg" },
      { key: "potassium", label: "Potassium", unit: "mg" },
      { key: "vitaminA", label: "Vitamin A", unit: "\u03bcg" },
      { key: "vitaminC", label: "Vitamin C", unit: "mg" },
    ] },
  ];
  const NUTRITION_FIELDS = NUTRITION_GROUPS.flatMap(g => g.fields);
  const FRACTIONS = [[1, 8, "1/8"], [1, 4, "1/4"], [1, 3, "1/3"], [3, 8, "3/8"], [1, 2, "1/2"],
                     [5, 8, "5/8"], [2, 3, "2/3"], [3, 4, "3/4"], [7, 8, "7/8"]];

  /* ------------------------------------------------------------------ api -- */

  async function api(method, path, body) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && method !== "HEAD") headers["X-CSRF-Token"] = SALTY.csrfToken;

    const resp = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    // Session gone: send them to log in rather than failing with an opaque error.
    if (resp.status === 401) { window.location.href = "/login"; throw new Error("Not signed in"); }
    if (resp.status === 204) return null;

    const text = await resp.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch { data = null; } }

    if (!resp.ok) {
      const err = new Error((data && (data.error || data.message)) || `${resp.status} ${resp.statusText}`);
      err.status = resp.status;
      err.data = data;          // a 409 answers with the server's current row; conflict handling needs it
      throw err;
    }
    return data;
  }

  /* -------------------------------------------------------------- helpers -- */

  /**
   * UUIDv7: 48-bit big-endian millisecond timestamp, version 7, variant 10, random remainder.
   * Uppercase, matching what the Swift and KMP clients mint — SQLite compares ids with a binary
   * collation, so a lowercase id would neither sort nor match correctly.
   */
  let _lastMs = 0;
  let _seq = 0;

  function uuidv7() {
    let ms = Date.now();
    // Monotonic within a millisecond, matching swift-uuidv7 on the Apple clients. The bare spec
    // only orders ids ACROSS milliseconds, so a burst minted in the same tick would sort randomly
    // -- which defeats the reason Salty uses v7 at all.
    if (ms === _lastMs) {
      _seq += 1;
      if (_seq > 0x0fff) { _seq = 0; while (Date.now() === _lastMs) { /* spin to next ms */ } ms = Date.now(); }
    } else {
      _lastMs = ms;
      _seq = 0;
    }
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    b[0] = Math.floor(ms / 2 ** 40) & 0xff;
    b[1] = Math.floor(ms / 2 ** 32) & 0xff;
    b[2] = (ms >>> 24) & 0xff;
    b[3] = (ms >>> 16) & 0xff;
    b[4] = (ms >>> 8) & 0xff;
    b[5] = ms & 0xff;
    // rand_a (12 bits) carries the sequence counter so same-millisecond ids stay ordered.
    b[6] = 0x70 | ((_seq >> 8) & 0x0f);
    b[7] = _seq & 0xff;
    b[8] = 0x80 | (b[8] & 0x3f);
    const h = [...b].map(x => x.toString(16).padStart(2, "0")).join("").toUpperCase();
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  }

  /** The wire format the server expects: yyyy-MM-dd'T'HH:mm:ss.SSS'Z' — exactly toISOString(). */
  const wireNow = () => new Date().toISOString();

  function formatAmount(v) {
    if (!isFinite(v) || v <= 0) return null;
    const whole = Math.floor(v + 1e-9);
    const frac = v - whole;
    if (frac < 0.02) return String(whole);
    let best = null, bestErr = 1;
    for (const [n, d, label] of FRACTIONS) {
      const err = Math.abs(frac - n / d);
      if (err < bestErr) { bestErr = err; best = label; }
    }
    if (bestErr > 0.04) return String(Math.round(v * 100) / 100);
    return whole > 0 ? `${whole} ${best}` : best;
  }

  function parseLeadingAmount(text) {
    const m = String(text).match(/^\s*(\d+\s+\d+\/\d+|\d+\/\d+|\d*\.\d+|\d+)/);
    if (!m) return null;
    const tok = m[1].trim();
    let val;
    if (tok.includes(" ")) {
      const [w, f] = tok.split(/\s+/);
      const [a, b] = f.split("/");
      val = parseFloat(w) + Number(a) / Number(b);
    } else if (tok.includes("/")) {
      const [a, b] = tok.split("/");
      val = Number(a) / Number(b);
    } else {
      val = parseFloat(tok);
    }
    return { value: val, rest: text.slice(m[0].length) };
  }

  const esc = s => String(s).replace(/[&<>"]/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  const newRow = (text, opts = {}) => ({
    id: uuidv7(), text, isHeading: !!opts.isHeading, isMain: !!opts.isMain,
  });

  /* ------------------------------------------------------- alpine component -- */

  return {
    // data
    query: "",
    list: [],
    listLoading: true,
    loadError: "",
    current: null,
    ingredients: [],
    directions: [],
    selectedId: null,
    username: SALTY.username || "",
    isAdmin: SALTY.isAdmin,
    build: { version: SALTY.version, buildTime: SALTY.buildTime },
    minPasswordLength: SALTY.minPasswordLength,

    /**
     * Which modal is open, mirrored in the URL hash: "library" | "password" | "devices" | "users" |
     * "about", or null. One field rather than a boolean each, because these are mutually exclusive
     * and separate flags let two of them open at once the moment a menu item forgot to close its
     * sibling. Confirmations below are separate: they stack ON TOP of one of these.
     */
    dialog: null,
    pw: { current: "", next: "", confirm: "", error: "", saving: false },
    devices: { loading: false, rows: [], error: "" },
    users: { loading: false, rows: [], error: "", newName: "", newPassword: "", newAdmin: false, creating: false },
    pendingDeviceRemove: null,
    confirmRevokeAll: false,
    pendingUserDelete: null,
    pendingUserPassword: null,
    // Read is the default, matching the Swift and CMP apps: Edit is an action you take, not a tab.
    mode: "read",
    pane: "list",          // compact-screen pane: rail | list | detail
    /**
     * Chef mode: the open recipe alone, big enough to read from a step back with your hands full.
     *
     * A view state and nothing more. It is deliberately NOT remembered between visits and not in
     * the URL: it answers "I am cooking right now", not "this is how I like the app", and coming
     * back tomorrow to a chromeless window you didn't ask for is the failure mode of every kiosk
     * toggle that got persisted.
     */
    chefMode: false,
    /**
     * The screen wake lock chef mode holds, and the preference that governs it.
     *
     * Per browser rather than per account, and so stored beside the pane width rather than on the
     * server: "keep this screen on" is a fact about the tablet propped against the toaster, not
     * about the person — the same account on a laptop wants the opposite. On by default, because a
     * screen that goes dark four steps into a recipe is most of why chef mode exists; off is one
     * switch away in Preferences for anyone who would rather have the battery.
     */
    wakeLockPref: true,
    _wakeLock: null,
    courses: [],
    categories: [],
    tags: [],
    filter: { kind: "all", id: null, label: "All Recipes" },
    /*
     * How the middle column is ordered: a field key from `recipeSortFields`, plus a direction
     * chosen separately -- the same split the CMP and Swift apps present, so "Date Modified" and
     * "newest first" stay two short decisions instead of eight combined menu items. Both are
     * remembered per browser: an order you picked is a preference, not a per-visit decision.
     */
    sortBy: "name",
    sortAsc: true,
    section: "recipes",        // which thing the middle and right panes are showing
    shoppingLists: [],
    shoppingListsLoading: false,
    currentList: null,
    /** The copy of the open list as it was loaded. The server keeps no syncedSnapshot -- that
     *  column lives on the native clients -- so this browser has to hold the merge base itself. */
    listBase: null,
    selectedListId: null,
    listDirty: false,
    listSaving: false,
    confirmDeleteList: false,
    newItemText: "",
    _listSaveTimer: null,
    _listSaveInFlight: null,
    _listSavePending: false,
    /** Bumped whenever the open list changes identity (opened, switched, deleted). A response that
     *  was computed under an older generation is stale and must not be applied. */
    _listGeneration: 0,
    scaleIdx: 1,
    dirty: false,
    saving: false,
    loadingRecipe: false,
    confirmDelete: false,
    pendingClassifierDelete: null,
    newClassifier: { category: "", course: "", tag: "" },
    newTagOpen: false,
    newTagName: "",
    newTagCreating: false,
    // Image changes are staged, not applied on the spot. The editor is a modal with Save, Revert
    // and Done, and a control that wrote through immediately would sit outside that contract --
    // Revert would silently fail to undo it. Applied by applyImageChanges() during save().
    pendingImageFile: null,
    pendingImageUrl: null,
    pendingImageRemoval: false,
    /**
     * True while `current` is a recipe that has never been written to the server — today only an
     * import. It matters because "discard my changes" means two different things: for a saved
     * recipe, reload the stored copy; for a draft there is nothing to reload, and asking the server
     * for one would 404. See cancelEdit/revert.
     */
    isDraft: false,
    importUrl: "",
    importing: false,
    importError: "",

    // computed
    get scaleLabel() { return `${SCALES[this.scaleIdx]}×`; },
    get libraryGroups() {
      return [
        // `singular` is explicit rather than derived: stripping a trailing "s" turns Categories
        // into "Categorie".
        // These three used to carry an `outline` flag, because Font Awesome Free ships a regular
        // cut for only some of its icons and asking for variant="regular" on utensils or tag
        // silently rendered the solid glyph -- so the flag marked which rows could be outlined at
        // all. Material Symbols has both cuts for every icon, so all three are simply outlined and
        // the flag is gone; the template no longer picks a variant per group.
        // `category` is a Material name written directly rather than a Font Awesome one the alias
        // table translates: it is the icon the Compose app uses for this row
        // (Icons.Outlined.Category), and no Font Awesome name means the same thing. Courses and
        // Tags need no such treatment -- utensils and tag already alias to restaurant and sell,
        // which is what the Compose app draws.
        { kind: "category", label: "Categories", singular: "category", icon: "category",
          items: this.categories },
        { kind: "course", label: "Courses", singular: "course", icon: "utensils",
          items: this.courses },
        { kind: "tag", label: "Tags", singular: "tag", icon: "tag",
          items: this.tags },
      ];
    },

    /** Nutrition arrives as one object of mostly-null numbers; only show what's actually filled. */
    get hasNutrition() { return this.nutritionRows.length > 0; },

    get nutritionRows() {
      const n = this.current && this.current.nutrition;
      if (!n) return [];
      return NUTRITION_FIELDS
        .filter(f => n[f.key] !== null && n[f.key] !== undefined && n[f.key] !== "")
        .map(f => ({
          label: f.label,
          value: f.text ? String(n[f.key]) : `${Number(n[f.key]).toLocaleString()}${f.unit || ""}`,
        }));
    },

    /**
     * What the editor and the reading view should show right now: a staged file if one was picked,
     * nothing if removal is staged, otherwise whatever the recipe already has.
     */
    get imageUrl() {
      if (this.pendingImageUrl) return this.pendingImageUrl;
      if (this.pendingImageRemoval) return null;
      const fn = this.current && this.current.imageFilename;
      return fn ? `/api/recipes/images/${encodeURIComponent(fn)}` : null;
    },

    get favoriteCount() { return this.list.filter(r => r.isFavorite).length; },
    get wantToMakeCount() { return this.list.filter(r => r.wantToMake).length; },

    /**
     * The sort menu, in menu order. Labels are the CMP and Swift apps' own, so the same ordering
     * goes by the same name whichever client you opened; `asc`/`desc` say what the direction means
     * for that particular field, because "Ascending" on a date is not self-evidently oldest-first.
     *
     * This list and `recipeSortFields` are the same set seen twice -- a key here must have a
     * comparator there, which `setSortField` enforces rather than trusting.
     */
    recipeSortOptions: [
      { key: "name",     label: "Name",          asc: "A → Z",        desc: "Z → A" },
      { key: "modified", label: "Date Modified", asc: "Oldest first", desc: "Newest first" },
      { key: "created",  label: "Date Created",  asc: "Oldest first", desc: "Newest first" },
      { key: "prepared", label: "Last Made",     asc: "Oldest first", desc: "Newest first" },
    ],

    /**
     * How the middle column is ordered, as a table of comparators rather than one hard-coded
     * comparison, so a new sort is a row here plus a row in `recipeSortOptions`.
     *
     * Every comparator is ASCENDING and falls back to the name: direction is applied once, in
     * `visibleRecipes`, by negating the result -- the same thing the CMP app does by reversing the
     * sorted list. The name fallback is what keeps recipes that share a date (or share no date at
     * all) in a stable, readable order instead of whatever order the server sent them in.
     *
     * localeCompare, not `<`: "Éclair" and "eclair" have to sort where a reader expects, and
     * `numeric` keeps "Chili 2" after "Chili 10" from being the other way round. The dates are
     * ISO-8601 strings, so comparing them as text is chronological -- the same assumption the
     * Swift and CMP clients make.
     */
    get recipeSortFields() {
      const byName = (a, b) =>
        (a.name || "").localeCompare(b.name || "", undefined, { numeric: true, sensitivity: "base" });
      const byDate = key => (a, b) =>
        String(a[key] || "").localeCompare(String(b[key] || "")) || byName(a, b);
      return {
        name: byName,
        modified: byDate("lastModifiedDate"),
        created: byDate("createdDate"),
        prepared: byDate("lastPrepared"),
      };
    },

    /** Whether a recipe has ever been made -- the one field that is routinely empty. */
    everMade(r) { return !!String(r.lastPrepared || "").trim(); },

    /** What the two directions mean for the field in force, for the menu's details slot. */
    sortHint(asc) {
      const opt = this.recipeSortOptions.find(o => o.key === this.sortBy);
      return opt ? (asc ? opt.asc : opt.desc) : "";
    },

    /** The whole sort state in one phrase, so the trigger can say it without being opened. */
    get sortLabel() {
      const opt = this.recipeSortOptions.find(o => o.key === this.sortBy);
      return opt ? `Sort: ${opt.label} (${this.sortAsc ? opt.asc : opt.desc})` : "Sort";
    },

    /**
     * Applies a sort chosen from the menu, field or direction.
     *
     * Bound to the dropdown's `wa-select` rather than to a click on each item, because the menu is
     * operable from the keyboard and wa-dropdown activates an item from Enter/Space by calling its
     * own selection path directly -- no DOM click is dispatched. Click handlers would leave the
     * arrows-and-Enter route ticking checkboxes without ever reordering the list, which is the
     * quietest kind of broken. Both routes emit `wa-select`.
     */
    applySort(item, dropdown) {
      const field = item && item.dataset.sortField;
      const dir = item && item.dataset.sortDir;
      // An unknown field would sort by nothing and leave the menu showing one that isn't applied.
      if (field && this.recipeSortFields[field]) {
        this.sortBy = field;
        this.writeStored(this.sortKey, field);
      } else if (dir) {
        this.sortAsc = dir === "asc";
        this.writeStored(this.sortAscKey, this.sortAsc ? "1" : "0");
      }
      this.syncSortChecks(dropdown);
    },

    /**
     * Puts the checkmarks back after a selection.
     *
     * wa-dropdown flips a checkbox item's `checked` itself whenever it is chosen, which is right
     * for a menu that owns its own state and wrong here, where the state is `sortBy`/`sortAsc`.
     * Choosing the sort ALREADY in force is the case that breaks: nothing changes, so the binding
     * has nothing to re-render, and the component's flip is left standing -- a menu insisting the
     * list is unsorted while it is sorted exactly as asked. Re-asserting from the state covers
     * both that and the item being switched away from.
     */
    syncSortChecks(dropdown) {
      dropdown.querySelectorAll("[data-sort-field]").forEach(el => {
        el.checked = el.dataset.sortField === this.sortBy;
      });
      dropdown.querySelectorAll("[data-sort-dir]").forEach(el => {
        el.checked = (el.dataset.sortDir === "asc") === this.sortAsc;
      });
    },

    /** The middle pane: library filter first, then the search box on top of it, then the sort. */
    get visibleRecipes() {
      // .filter always returns a new array, so sorting here cannot disturb `list` itself.
      let rows = this.list.filter(r => this.matchesFilter(r));
      const q = this.query.trim().toLowerCase();
      if (q) rows = rows.filter(r => (r.name || "").toLowerCase().includes(q));
      const cmp = this.recipeSortFields[this.sortBy] || this.recipeSortFields.name;
      rows.sort(this.sortAsc ? cmp : (a, b) => -cmp(a, b));
      // Never-made recipes go LAST in both directions, as in the CMP and Swift apps: ascending
      // would otherwise open with every recipe that has no date at all -- noise, for a sort that
      // exists to answer "what have I cooked lately".
      if (this.sortBy === "prepared") {
        return rows.filter(r => this.everMade(r)).concat(rows.filter(r => !this.everMade(r)));
      }
      return rows;
    },

    /*
     * A tooltip for a name that had to be cut, and for no other.
     *
     * Truncation here is purely visual -- the full text is in the DOM, so a screen reader has never
     * been missing anything and this is not an accessibility fix. It is for the sighted reader
     * looking at "Veggie Burger (Grind Burger Kitch...".
     *
     * Set on mouseenter rather than rendered into every row: `title` on all of them would pop a
     * tooltip that repeats text already fully visible, which is noise. mouseenter lands long before
     * the browser's own tooltip delay, and re-measuring per hover is what keeps it honest when the
     * divider moves and the same name stops being clipped.
     */
    titleIfClipped(el) {
      if (el.scrollWidth > el.clientWidth) el.title = el.textContent.trim();
      else el.removeAttribute("title");
    },

    /*
     * The list column's width, remembered per browser.
     *
     * The attribute, not the property: a custom element that has not upgraded yet turns an assigned
     * property into an own property that shadows the accessor, and the autoloader defines
     * <wa-split-panel> whenever it gets to it. An attribute is read on upgrade either way.
     *
     * Pixels rather than the percentage, to match primary="start": the list is a fixed-size
     * thumbnail and a name, and it should keep the width it was given when the window changes.
     */
    splitKey: "salty.listWidth",
    wakeLockKey: "salty.chefWakeLock",
    sortKey: "salty.recipeSort",
    sortAscKey: "salty.recipeSortAsc",

    restoreSplit(el) {
      // localStorage throws outright in a locked-down browser; a remembered pane width is not worth
      // taking the whole app down for.
      const saved = Number(this.readStored(this.splitKey));
      if (saved > 0) el.setAttribute("position-in-pixels", String(Math.round(saved)));
    },

    rememberSplit(el) {
      // wa-reposition fires per pointer move, hence the debounce on the listener: only the resting
      // width is worth writing.
      const px = Math.round(el.positionInPixels || 0);
      if (px > 0) this.writeStored(this.splitKey, String(px));
    },

    readStored(key) {
      try { return localStorage.getItem(key); } catch { return null; }
    },

    writeStored(key, value) {
      try { localStorage.setItem(key, value); } catch { /* private mode, quota, disabled */ }
    },

    /*
     * Roving tabindex: the list column is one tab stop, not one per recipe.
     *
     * Tabbing into a 200-recipe list and having to leave it the same way is the thing that makes a
     * long list of buttons unusable with a keyboard, so exactly one row is tabbable and the arrow
     * keys move between them (see rowKeys). The tab stop is the selected row -- come back to the
     * list and you land where you left -- falling back to the first row when nothing is selected or
     * when the selection is filtered out by the search box, which would otherwise leave the column
     * with no way in at all.
     */
    get recipeTabId() {
      const rows = this.visibleRecipes;
      if (!rows.length) return null;
      return rows.some(r => r.id === this.selectedId) ? this.selectedId : rows[0].id;
    },

    get shoppingTabId() {
      const rows = this.shoppingLists;
      if (!rows.length) return null;
      return rows.some(l => l.id === this.selectedListId) ? this.selectedListId : rows[0].id;
    },

    /*
     * Arrow-key navigation within a list column, bound on the <ul>.
     *
     * Focus moves; it does not select. Selection-follows-focus would read a recipe off the server
     * on every keypress, so holding Down through the list would fire a request per row -- Enter or
     * Space opens the focused one, which is what the row being a real <button> already does.
     *
     * The rows come from the DOM rather than from `visibleRecipes` so that one handler serves both
     * columns, and the moved tab stop is set on the elements directly: the `:tabindex` binding is
     * the resting state and recomputes from the selection, while this carries the roving stop until
     * then. No wrap-around at the ends -- in a scrolling list, jumping from the last row back to
     * the first loses your place more than it saves a keystroke.
     */
    rowKeys(e) {
      const step = { ArrowDown: 1, ArrowUp: -1 }[e.key];
      if (step === undefined && e.key !== "Home" && e.key !== "End") return;
      const rows = [...e.currentTarget.querySelectorAll(".rrow")];
      if (!rows.length) return;

      const from = rows.indexOf(e.target.closest(".rrow"));
      let to;
      if (e.key === "Home") to = 0;
      else if (e.key === "End") to = rows.length - 1;
      else if (from < 0) to = 0;
      else to = Math.min(rows.length - 1, Math.max(0, from + step));

      e.preventDefault();
      rows.forEach(el => { el.tabIndex = -1; });
      rows[to].tabIndex = 0;
      rows[to].focus();
    },

    async init() {
      window.addEventListener("beforeunload", e => {
        if (this.dirty || this.listDirty) { e.preventDefault(); e.returnValue = ""; }
      });
      // Stored as "0"/"1". Anything else — never set, or storage that throws — means the default.
      this.wakeLockPref = this.readStored(this.wakeLockKey) !== "0";
      // A remembered field that no longer exists (an option dropped between releases) falls back to
      // the default rather than leaving the list sorted by a comparator that isn't there.
      const storedSort = this.readStored(this.sortKey);
      if (storedSort && this.recipeSortFields[storedSort]) this.sortBy = storedSort;
      this.sortAsc = this.readStored(this.sortAscKey) !== "0";
      // The platform drops a wake lock whenever the page stops being visible and does not hand it
      // back on its own. Ducking out to a timer app and returning must not leave the screen dark,
      // so it is re-taken here. Nothing to do on the way out: it is already gone.
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") this.acquireWakeLock();
      });
      // Dialogs are addressable. Both events are needed: popstate covers the Back button after
      // openDialog()'s pushState, hashchange covers a URL typed or pasted into the address bar.
      window.addEventListener("popstate", () => this.applyHash());
      window.addEventListener("hashchange", () => this.applyHash());
      this.applyHash();
      await Promise.all([this.loadList(), this.loadLibrary()]);
    },

    /**
     * Courses, categories and tags drive the rail. Loaded once alongside the recipe list; counts
     * are computed client-side from ids the list endpoint already returns, so filtering costs no
     * extra requests.
     */
    async loadLibrary() {
      try {
        const [courses, categories, tags] = await Promise.all([
          api("GET", "/api/courses"),
          api("GET", "/api/categories"),
          api("GET", "/api/tags"),
        ]);
        const byName = (a, b) => String(a.name || "").localeCompare(String(b.name || ""));
        this.courses = (courses || []).sort(byName);
        this.categories = (categories || []).sort(byName);
        this.tags = (tags || []).sort(byName);
      } catch (e) {
        this.notify(`Couldn't load library: ${e.message}`, "danger");
      }
    },

    /**
     * One control, two behaviours: below wa-page's breakpoint the navigation is a drawer it owns,
     * so ask it to toggle; above, collapse our own rail. wa-page reflects which mode it is in via
     * its `view` attribute.
     */
    /**
     * Opens wa-page's navigation drawer, for the "Library" button on a compact screen.
     *
     * This asks the component rather than setting state of our own: the drawer is wa-page's, and
     * the previous version set pane = "rail", a value no CSS rule matched, so the library simply
     * could not be reached on a narrow screen.
     */
    showLibrary() {
      const page = document.getElementById("app");
      if (page && typeof page.showNavigation === "function") page.showNavigation();
    },

    /**
     * wa-tree reports selection as elements, so map the chosen node back to a filter. Group nodes
     * (Categories, Courses, ...) are containers, not filters -- selecting one only expands it.
     */
    onTreeSelect(event) {
      const el = (event.detail && event.detail.selection || [])[0];
      if (!el) return;
      if (el.hasAttribute("data-href")) { window.location.href = el.getAttribute("data-href"); return; }

      // Group rows can't be selected at all now (the tree is in leaf mode), so nothing to guard.
      if (el.hasAttribute("data-group")) return;

      const kind = el.getAttribute("data-kind");
      if (!kind) return;
      if (kind === "shopping") { this.showShoppingLists(); return; }
      const id = el.getAttribute("data-id");
      const labels = { all: "All Recipes", favorites: "Favorites", wantToMake: "Want to Make" };
      const label = labels[kind] || (el.textContent || "").trim().replace(/\s+\d+$/, "");
      this.setFilter(kind, id, label);
    },

    /* ------------------------------------------------------ shopping lists -- */

    showShoppingLists() {
      this.section = "shopping";
      this.pane = "list";
      if (!this.shoppingLists.length) this.loadShoppingLists();
    },

    async loadShoppingLists() {
      this.shoppingListsLoading = true;
      try {
        const rows = await api("GET", "/api/shoppingLists");
        this.shoppingLists = (rows || []).sort(
          (a, b) => String(a.name || "").localeCompare(String(b.name || "")));
      } catch (e) {
        this.notify(`Couldn't load lists: ${e.message}`, "danger");
      } finally {
        this.shoppingListsLoading = false;
      }
    },

    /** Rows arrive without their contents field when a list has never had any; normalise so the
     *  template can iterate and x-model can bind without null checks everywhere. */
    normaliseList(l) {
      // Only the side this list actually uses. A freeform row's contentsForList is NULL on the
      // server and has to stay NULL: writing [] back turns "this list has no checklist" into "this
      // list has an empty checklist", a different row to every other client -- and since a save
      // PUTs the whole object, it would happen on the first keystroke in the text area.
      if (l.isFreeform) l.contentsForFreeform = l.contentsForFreeform || "";
      else l.contentsForList = l.contentsForList || [];
      return l;
    },

    async openList(id) {
      await this.flushListSave();          // don't abandon a pending edit to the list we're leaving
      this.pane = "detail";
      try {
        const l = this.normaliseList(await api("GET", `/api/shoppingLists/${encodeURIComponent(id)}`));
        this._listGeneration++;
        this.currentList = l;
        this.listBase = structuredClone(l);
        this.selectedListId = id;
        this.listDirty = false;
      } catch (e) {
        this.notify(`Couldn't open list: ${e.message}`, "danger");
      }
    },

    /**
     * Shopping lists autosave: they're driven by check-offs, and a Save button on every tick would
     * be noise. The debounce is what makes concurrent edits likely enough to matter, which is why
     * the conflict path below exists rather than being theoretical.
     *
     * The stamp goes on `currentList` itself, not just on the outgoing body. `resolveList` sends
     * `currentList` as its `local` side, and the shared merge breaks both-changed ties by
     * comparing dates -- so a stale stamp here made the browser lose every one of those ties.
     */
    touchList() {
      this.listDirty = true;
      if (this.currentList) this.currentList.lastModifiedDate = wireNow();
      clearTimeout(this._listSaveTimer);
      this._listSaveTimer = setTimeout(() => { if (this.listDirty) this.saveList(); }, 700);
    },

    /**
     * Runs any pending save to completion, including one already on the wire. Awaiting saveList()
     * alone was not enough: it returns immediately when a save is in flight, so navigating away
     * mid-save silently abandoned the newest edits.
     */
    async flushListSave() {
      clearTimeout(this._listSaveTimer);
      if (this._listSaveInFlight) await this._listSaveInFlight.catch(() => {});
      if (this.listDirty) await this.saveList();
    },

    /**
     * Writes the open list, then applies the server's echo -- but only if it is still the answer to
     * a question we are asking. Every guard here exists because a response is applied to state that
     * may have moved on while it was on the wire:
     *
     *  - the list may have been switched, deleted or reloaded (checked via `_listGeneration`),
     *  - and edits made after the body was serialised are NOT in that body, so accepting the echo
     *    would visibly undo them and then report "All changes saved".
     *
     * When either happens the echo is dropped and the save is re-driven, so the newest state wins
     * instead of the newest *response*.
     */
    async saveList() {
      if (!this.currentList) return;
      if (this._listSaveInFlight) { this._listSavePending = true; return; }

      const generation = this._listGeneration;
      const listId = this.currentList.id;
      const body = { ...this.currentList, baseRevision: this.currentList.revision ?? null };
      this.listSaving = true;
      this.listDirty = false;              // anything typed from here on re-dirties and re-saves

      const run = (async () => {
        try {
          const saved = await api("PUT", `/api/shoppingLists/${encodeURIComponent(listId)}`, body);
          if (this._listGeneration !== generation) return;   // switched/deleted while in flight
          if (this.listDirty) {
            // Edits landed after the body was serialised, so they are NOT in this echo. Applying it
            // wholesale would visibly undo them and then report "All changes saved". Take only what
            // the server owns -- the revision, and the base a later merge is measured from -- and
            // leave the newer local contents; the finally block re-drives the save.
            this.adoptServerRevision(saved);
          } else {
            this.applySavedList(saved);
          }
        } catch (e) {
          if (this._listGeneration !== generation) return;
          // 409 means someone else wrote since we loaded. Rather than picking a winner, hand both
          // sides plus the base we started from to the server, which runs the same merge the native
          // clients run -- so a check-off here and an edit there both survive.
          if (e.status === 409) { await this.resolveList(listId, generation); }
          else {
            this.listDirty = true;         // nothing was stored; don't claim it was
            this.notify(`Couldn't save list: ${e.message}`, "danger");
          }
        }
      })();

      this._listSaveInFlight = run;
      try {
        await run;
      } finally {
        this._listSaveInFlight = null;
        this.listSaving = false;
        // Edits that landed mid-flight, or a save requested while one was running.
        if ((this._listSavePending || this.listDirty) && this._listGeneration === generation) {
          this._listSavePending = false;
          await this.saveList();
        }
        this._listSavePending = false;
      }
    },

    /**
     * Hands the server the two sides the browser has plus the base it started from, and takes back
     * whatever the shared merge produced. Scoped to the list and generation the save was issued
     * for, so a response arriving after the user switched lists can't be applied to the new one --
     * previously this read `currentList` at response time and could merge a list against itself.
     */
    async resolveList(listId, generation, retried = false) {
      try {
        const res = await api("POST", `/api/shoppingLists/${encodeURIComponent(listId)}/resolve`,
                              { base: this.listBase, local: this.currentList });
        if (this._listGeneration !== generation) return;
        this.applySavedList(res.merged);
        if (res.conflictCopy) {
          // Freeform text can't be merged line by line, so the other version was kept whole rather
          // than thrown away. Say so plainly -- silently dropping it would be the real failure.
          await this.loadShoppingLists();
          this.notify(`This list changed elsewhere. Both versions were kept — see "${res.conflictCopy.name}".`,
                      "warning");
        } else {
          this.notify("Merged changes made elsewhere");
        }
      } catch (e) {
        if (this._listGeneration !== generation) return;
        // The resolve itself can 409 when a third write lands between the server's read and its
        // save. The body is the current row, so re-basing on it and trying once more is exactly
        // what the endpoint asks for.
        if (e.status === 409 && !retried && e.data) {
          this.listBase = structuredClone(this.normaliseList(e.data));
          await this.resolveList(listId, generation, true);
          return;
        }
        this.listDirty = true;
        this.notify(`Couldn't merge changes: ${e.message}`, "danger");
      }
    },

    /**
     * Takes the server-owned parts of an echo without touching contents the user has since changed.
     * The echo still defines the agreed base: it is what the server holds now, so a later three-way
     * merge must measure against it rather than against the copy originally loaded.
     */
    adoptServerRevision(saved) {
      if (!this.currentList || saved.id !== this.currentList.id) return;
      this.normaliseList(saved);
      this.currentList.revision = saved.revision;
      this.listBase = structuredClone(saved);
      const i = this.shoppingLists.findIndex(l => l.id === saved.id);
      if (i >= 0) this.shoppingLists[i] = { ...saved };
    },

    applySavedList(saved) {
      // Belt and braces alongside the generation check: never let one list's echo overwrite another.
      if (!this.currentList || saved.id !== this.currentList.id) return;
      this.normaliseList(saved);
      this.currentList = saved;
      this.listBase = structuredClone(saved);
      this.listDirty = false;
      const i = this.shoppingLists.findIndex(l => l.id === saved.id);
      if (i >= 0) this.shoppingLists[i] = { ...saved };
    },

    /* --- list contents --- */

    addItem() {
      const text = (this.newItemText || "").trim();
      if (!text || !this.currentList) return;
      this.currentList.contentsForList.push({
        id: uuidv7(), text, isCompleted: false, isImportant: false, isHeading: false,
      });
      this.newItemText = "";
      this.touchList();
    },

    addListHeading() {
      if (!this.currentList) return;
      this.currentList.contentsForList.push({
        id: uuidv7(), text: "Section", isCompleted: false, isImportant: false, isHeading: true,
      });
      this.touchList();
    },

    removeItem(item) {
      if (!this.currentList) return;
      this.currentList.contentsForList =
        this.currentList.contentsForList.filter(i => i.id !== item.id);
      this.touchList();
    },

    /** Completed items sink to the bottom, headings hold their place. */
    get sortedItems() {
      if (!this.currentList) return [];
      const rows = this.currentList.contentsForList;
      return [...rows].sort((a, b) => (a.isCompleted ? 1 : 0) - (b.isCompleted ? 1 : 0));
    },

    get openItemCount() {
      if (!this.currentList) return 0;
      return this.currentList.contentsForList.filter(i => !i.isHeading && !i.isCompleted).length;
    },

    clearCompleted() {
      if (!this.currentList) return;
      this.currentList.contentsForList =
        this.currentList.contentsForList.filter(i => !i.isCompleted);
      this.touchList();
    },

    /* --- whole lists --- */

    /**
     * A new list of either shape.
     *
     * The two shapes are separate columns on the row, not two renderings of one thing, so this
     * choice is real and is made once: only the matching column is ever populated. Sending just the
     * one that applies leaves the other NULL, which is what every other client expects to find.
     */
    async createList({ freeform = false } = {}) {
      const id = uuidv7();
      try {
        const saved = await api("POST", "/api/shoppingLists", {
          id,
          name: freeform ? "New Markdown List" : "New List",
          isFreeform: freeform,
          ...(freeform ? { contentsForFreeform: "" } : { contentsForList: [] }),
          lastModifiedDate: wireNow(),
        });
        this.shoppingLists.push(saved);
        this.shoppingLists.sort((a, b) => String(a.name || "").localeCompare(String(b.name || "")));
        await this.openList(saved.id);
      } catch (e) {
        this.notify(`Couldn't create list: ${e.message}`, "danger");
      }
    },

    async deleteList() {
      if (!this.currentList) return;
      const id = this.currentList.id;
      clearTimeout(this._listSaveTimer);
      this.listDirty = false;
      // A save already on the wire would otherwise land after the delete and re-create the row --
      // the repository treats a write with no current row as an insert. Let it finish, then take
      // the generation past it so its echo is ignored.
      if (this._listSaveInFlight) await this._listSaveInFlight.catch(() => {});
      this._listGeneration++;
      try {
        await api("DELETE", `/api/shoppingLists/${encodeURIComponent(id)}`);
        this.shoppingLists = this.shoppingLists.filter(l => l.id !== id);
        this.currentList = null;
        this.selectedListId = null;
        this.confirmDeleteList = false;
        this.notify("List deleted");
      } catch (e) {
        this.notify(`Couldn't delete list: ${e.message}`, "danger");
      }
    },

    /** "3 items · 1 left" — enough to pick a list out without opening it. */
    listMeta(l) {
      if (l.isFreeform) return "Markdown";
      const rows = (l.contentsForList || []).filter(i => !i.isHeading);
      if (!rows.length) return "Empty";
      const left = rows.filter(i => !i.isCompleted).length;
      return `${rows.length} ${rows.length === 1 ? "item" : "items"} · ${left} left`;
    },

    /* ---------------------------------------------------- library manager -- */

    /** REST path for a classifier kind. The API pluralises; our filter kinds are singular. */
    endpointFor(kind) {
      return { category: "/api/categories", course: "/api/courses", tag: "/api/tags" }[kind];
    },

    collectionFor(kind) {
      return { category: "categories", course: "courses", tag: "tags" }[kind];
    },

    /* ---------------------------------------------------------- web import -- */

    /**
     * Import a recipe from a URL.
     *
     * The fetch is the server's, not ours: a browser may not read a third-party page cross-origin,
     * so /api/recipes/import loads and parses it (behind an address policy — see RecipeImport.kt)
     * and answers with a draft. What comes back is untrusted content that merely looks like a
     * recipe, so it lands in the editor unsaved and becomes a recipe only if the user saves it.
     */
    async runImport() {
      const url = this.importUrl.trim();
      if (!url || this.importing) return;
      // Asked before the request, not after: spending fifteen seconds on a fetch and only then
      // finding out the answer is "don't discard my edits" would throw the import away.
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;

      this.importing = true;
      this.importError = "";
      try {
        const result = await api("POST", "/api/recipes/import", { url });
        this.importUrl = "";
        this.dismissDialog();
        this.openImportedDraft(result);
        this.notify("Imported — review it and save.");
      } catch (e) {
        this.importError = e.message;
      } finally {
        this.importing = false;
      }
    },

    /**
     * Put an imported draft in the editor, in the same shape open() leaves a loaded recipe.
     *
     * The id is minted here rather than by the server: ids are UUIDv7 so they sort by creation, and
     * the one moment a recipe is created is this one. `dirty` starts true because the draft exists
     * only in this tab — the unload guard should fight for it exactly as it would for typed edits.
     */
    openImportedDraft(result) {
      const r = result.recipe || {};
      const now = wireNow();
      r.id = uuidv7();
      r.createdDate = now;
      r.lastModifiedDate = now;
      r.categoryIds = [];
      r.tagIds = [];
      r.notes = [];
      r.variations = [];
      r.preparationTimes = r.preparationTimes || [];

      this.resetImageStaging();
      this.current = r;
      this.selectedId = null;        // nothing in the list to highlight until it is saved
      this.ingredients = (r.ingredients || []).map(x => ({
        id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: !!x.isMain,
      }));
      this.directions = (r.directions || []).map(x => ({
        id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: false,
      }));

      if (result.imageBase64) this.stageImportedImage(result);

      this.isDraft = true;
      this.dirty = true;
      this.section = "recipes";
      this.pane = "detail";
      this.mode = "edit";            // it arrived to be reviewed, so open it in the form
    },

    /**
     * Stage the imported photo as if the user had picked the file themselves, so it travels through
     * the editor's existing save path and gets its thumbnail generated like every other upload.
     * A photo is a nicety: anything wrong with it drops the photo, never the recipe.
     */
    stageImportedImage(result) {
      const type = result.imageContentType || "";
      if (!IMAGE_TYPES.includes(type)) return;
      try {
        const binary = atob(result.imageBase64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        if (bytes.length > MAX_IMAGE_BYTES) return;
        const file = new File([bytes], "imported-image", { type });
        this.pendingImageFile = file;
        this.pendingImageUrl = URL.createObjectURL(file);
        this.pendingImageRemoval = false;
      } catch {
        // A malformed image is not a reason to lose the recipe that came with it.
      }
    },

    /* ------------------------------------------------ dialogs, account, admin -- */

    /**
     * The modals that are addressable. The recipe editor and the confirmations are deliberately not
     * in here: an editor with unsaved work must not be reopened by a URL it never wrote, and a
     * confirmation is a step inside another dialog rather than a place.
     */
    get routedDialogs() { return ["library", "import", "preferences", "users", "about"]; },

    _dialogFromHash() {
      const m = /^#\/([a-z]+)$/.exec(window.location.hash || "");
      return m && this.routedDialogs.includes(m[1]) ? m[1] : null;
    },

    /**
     * Hash is the source of truth; this pulls state into line with it (Back, or a pasted URL).
     *
     * The outgoing dialog is closed by calling the component's own requestClose(), NOT by letting
     * the `:open` binding go false. Web Awesome 3.12's wa-dialog does eventually close either way
     * — handleOpenChange() catches `open` going false under it and reroutes to requestClose() —
     * but only after setting `open` back to true first, so the attribute bounces and the modal
     * stays up for a few hundred ms. See WEB_APP.md for the measurement. Every close therefore
     * takes the same route the close button does.
     *
     * State is assigned BEFORE the close, so the wa-hide handler's own guard short-circuits rather
     * than racing this method for the URL.
     */
    applyHash() {
      const next = this._dialogFromHash();
      if (next === this.dialog) return;
      const previous = this.dialog;
      this.dialog = next;
      if (previous) this._dismissDialogElement(previous);
      if (next) this.onDialogOpened(next);
    },

    _dismissDialogElement(name) {
      const el = document.querySelector(`wa-dialog[data-dialog="${name}"]`);
      if (el && el.open && typeof el.requestClose === "function") el.requestClose();
    },

    /**
     * Opens a dialog and puts it in the URL, so a reload reopens it and Back closes it.
     *
     * pushState rather than assigning location.hash: assigning fires `hashchange`, which would run
     * applyHash() and re-enter the load below. Pushing changes the URL silently and lets the
     * popstate listener handle only the direction that matters — going back.
     */
    openDialog(name) {
      if (this.dialog === name) return;
      // Replace rather than push when swapping one dialog straight for another: two modals are not
      // two places, and stacking them would make Back walk you through the ones you passed through.
      const entry = { saltyDialog: name };
      if (this.dialog) history.replaceState(entry, "", "#/" + name);
      else history.pushState(entry, "", "#/" + name);
      this.dialog = name;
      this.onDialogOpened(name);
    },

    /**
     * Asks the open dialog to close — what every Cancel/Done button and every "saved, now get out
     * of the way" path calls.
     *
     * It goes through the component rather than clearing `dialog` and letting the `:open` binding
     * do it, for the reason spelled out on applyHash(): flipping `open` from outside takes a
     * detour that leaves the modal up for a few hundred ms. requestClose() dispatches wa-hide
     * synchronously, so closeDialog() below still runs and still owns the state and the URL.
     */
    dismissDialog() {
      if (this.dialog) this._dismissDialogElement(this.dialog);
    },

    /**
     * The same close for the one dialog that is not routed but is still closed from script: the
     * New tag sheet, which createTagInline() dismisses once the tag is attached.
     *
     * It carries a `data-dialog` name of its own purely so this lookup has something to find; the
     * name is not a route, and `routedDialogs` deliberately does not list it.
     */
    dismissNewTag() {
      this._dismissDialogElement("new-tag");
    },

    /**
     * The wa-hide handler: the dialog is going away, so drop the state and take it out of the URL.
     * Never call this to close something — call dismissDialog().
     *
     * history.back() only when this app pushed the entry. Someone who opened /app#/users directly
     * has no Salty entry behind them, and going back would leave the app entirely — so that case
     * rewrites the URL in place instead.
     */
    closeDialog(name) {
      // `name` is the dialog asking to close, and it must be the one that is actually open.
      //
      // wa-hide fires whenever a dialog closes, INCLUDING when it closes because `dialog` already
      // moved to a different one — going straight from #/users to #/password, say. Without this
      // guard the outgoing dialog's hide event ran closeDialog() and shut its replacement in the
      // same tick, leaving both half-open and the hash cleared.
      if (name && this.dialog !== name) return;
      if (!this.dialog) return;
      const pushedByUs = history.state && history.state.saltyDialog;
      this.dialog = null;
      if (pushedByUs) history.back();
      else history.replaceState(null, "", window.location.pathname + window.location.search);
    },

    /** Dialog contents load when opened, not on page load: most sessions open none of them. */
    onDialogOpened(name) {
      if (name === "preferences") {
        this.pw = { current: "", next: "", confirm: "", error: "", saving: false };
        this.loadDevices();
      }
      if (name === "users") this.loadUsers();
      if (name === "import") this.importError = "";
    },

    openLibraryManager() { this.openDialog("library"); },

    /* ---- your own password ---- */

    get passwordFormReady() {
      return !this.pw.saving &&
        this.pw.current.length > 0 &&
        this.pw.next.length >= this.minPasswordLength &&
        this.pw.confirm.length > 0;
    },

    /**
     * The confirm field and the length rule are checked here as well as on the server. Not because
     * the client's check is trusted — the server rejects both regardless — but because a typo in
     * the confirm field is not something to learn from a round trip, and the server has no confirm
     * field to compare against anyway.
     */
    async changePassword() {
      this.pw.error = "";
      if (this.pw.next !== this.pw.confirm) {
        this.pw.error = "The new passwords don't match.";
        return;
      }
      if (this.pw.next.length < this.minPasswordLength) {
        this.pw.error = `New password must be at least ${this.minPasswordLength} characters.`;
        return;
      }
      if (this.pw.next === this.pw.current) {
        this.pw.error = "That is already your password.";
        return;
      }
      this.pw.saving = true;
      try {
        const res = await api("POST", "/api/account/password", {
          currentPassword: this.pw.current,
          newPassword: this.pw.next,
        });
        this.pw = { current: "", next: "", confirm: "", error: "", saving: false };
        const n = (res && res.devicesSignedOut) || 0;
        this.notify(n
          ? `Password changed. ${n} app${n === 1 ? " was" : "s were"} signed out.`
          : "Password changed.");
        // Every enrolment is revoked by that call, and Preferences now shows the list directly
        // under this form — so the rows have to say so on the spot rather than at the next open,
        // or the warning above the fields is contradicted by the screen below them. That is also
        // why this no longer closes the dialog: there is more here than the password.
        this.devices.rows = this.devices.rows.map(d => ({ ...d, hasToken: false }));
      } catch (e) {
        this.pw.error = e.message;
      } finally {
        this.pw.saving = false;
      }
    },

    /* ---- authorized apps (device sync enrolments) ---- */

    get hasLiveDevices() { return this.devices.rows.some(d => d.hasToken); },

    /** Never blank: an unnamed enrolment is still one you may need to revoke. */
    deviceFallbackName(d) { return `Unnamed app (${String(d.deviceId || "").slice(0, 8)})`; },

    deviceLabel(d) {
      const n = (d.deviceName || "").trim();
      return n || this.deviceFallbackName(d);
    },

    deviceMeta(d) {
      // Token use beats sync date: it is the freshest evidence the enrolment is still alive.
      if (!d.hasToken) return "Signed out";
      const when = this.relativeDate(d.tokenLastUsed || d.lastSyncDate);
      return when ? `Last synced ${when}` : "Never synced";
    },

    async loadDevices() {
      this.devices.loading = true;
      this.devices.error = "";
      try {
        this.devices.rows = (await api("GET", "/api/auth/devices")) || [];
      } catch (e) {
        this.devices.error = `Couldn't load your apps: ${e.message}`;
      } finally {
        this.devices.loading = false;
      }
    },

    async renameDevice(d, name) {
      const trimmed = (name || "").trim();
      if (!trimmed || trimmed === (d.deviceName || "")) return;
      try {
        await api("PATCH", `/api/auth/devices/${encodeURIComponent(d.deviceId)}`, { deviceName: trimmed });
        d.deviceName = trimmed;
      } catch (e) {
        this.notify(e.message, "danger");
        await this.loadDevices();   // put the input back to what the server actually holds
      }
    },

    /** Removal deletes the row server-side, so drop it here rather than leaving a dead entry. */
    async removeDevice(d) {
      if (!d) return;
      const label = this.deviceLabel(d);
      this.pendingDeviceRemove = null;
      try {
        await api("DELETE", `/api/auth/devices/${encodeURIComponent(d.deviceId)}`);
        this.devices.rows = this.devices.rows.filter(r => r.deviceId !== d.deviceId);
        this.notify(`${label} removed.`);
      } catch (e) {
        this.notify(e.message, "danger");
      }
    },

    /**
     * Signs every app out without forgetting them — the rows stay, minus their tokens, so nothing
     * has to re-download when they sign back in. Removal is the per-app action.
     */
    async revokeAllDevices() {
      this.confirmRevokeAll = false;
      try {
        const res = await api("POST", "/api/auth/devices/revoke-all");
        this.devices.rows = this.devices.rows.map(d => ({ ...d, hasToken: false }));
        const n = (res && res.revoked) || 0;
        this.notify(n ? `${n} app${n === 1 ? "" : "s"} signed out.` : "Nothing to sign out.");
      } catch (e) {
        this.notify(e.message, "danger");
      }
    },

    /* ---- user administration ---- */

    get newUserReady() {
      return !this.users.creating &&
        this.users.newName.trim().length > 0 &&
        this.users.newPassword.length >= this.minPasswordLength;
    },

    async loadUsers() {
      this.users.loading = true;
      this.users.error = "";
      try {
        this.users.rows = (await api("GET", "/api/users")) || [];
      } catch (e) {
        this.users.error = `Couldn't load users: ${e.message}`;
      } finally {
        this.users.loading = false;
      }
    },

    async createUser() {
      if (!this.newUserReady) return;
      this.users.creating = true;
      try {
        await api("POST", "/api/users", {
          username: this.users.newName.trim(),
          password: this.users.newPassword,
          isAdmin: this.users.newAdmin,
        });
        this.notify(`${this.users.newName.trim()} created.`);
        this.users.newName = "";
        this.users.newPassword = "";
        this.users.newAdmin = false;
        await this.loadUsers();
      } catch (e) {
        this.notify(e.message, "danger");
      } finally {
        this.users.creating = false;
      }
    },

    /**
     * Demoting yourself is allowed (the server only stops the LAST admin from going), so this has
     * to handle the case where the screen it is running on is about to become forbidden: drop the
     * local flag, which hides the menu item, and close the dialog rather than leaving a live users
     * list that every subsequent click would 403 on.
     */
    async setUserAdmin(u, isAdmin) {
      try {
        await api("PATCH", `/api/users/${encodeURIComponent(u.id)}`, { isAdmin });
        u.isAdmin = isAdmin;
        this.notify(`${u.username} is ${isAdmin ? "now an administrator" : "no longer an administrator"}.`);
        if (u.isSelf) {
          this.isAdmin = isAdmin;
          if (!isAdmin) this.dismissDialog();
        }
      } catch (e) {
        this.notify(e.message, "danger");
      }
    },

    async resetUserPassword() {
      const pending = this.pendingUserPassword;
      if (!pending || pending.password.length < this.minPasswordLength) return;
      this.pendingUserPassword = null;
      try {
        await api("POST", `/api/users/${encodeURIComponent(pending.user.id)}/password`,
                  { password: pending.password });
        this.notify(`Password set for ${pending.user.username}. Their apps were signed out.`);
      } catch (e) {
        this.notify(e.message, "danger");
      }
    },

    async deleteUser() {
      const target = this.pendingUserDelete;
      if (!target) return;
      this.pendingUserDelete = null;
      try {
        await api("DELETE", `/api/users/${encodeURIComponent(target.id)}`);
        this.users.rows = this.users.rows.filter(u => u.id !== target.id);
        this.notify(`${target.username} deleted.`);
      } catch (e) {
        this.notify(e.message, "danger");
      }
    },


    /** What Add is bound to, so the button greys out while the POST is in flight. */
    get newTagReady() {
      return !this.newTagCreating && this.newTagName.trim().length > 0;
    },

    /**
     * Creates a tag and attaches it to the open recipe without leaving the editor. Tags get
     * invented while writing a recipe far more often than courses or categories do, which is why
     * this shortcut exists here and not for the others -- the library manager still handles those.
     *
     * `newTagCreating` is what stops a second Add landing while the first POST is out. The disabled
     * button is not enough on its own: Enter in the name field reaches here too, and the duplicate
     * check above it looks in `this.tags`, which the response has not been pushed into yet -- so
     * two clicks read an empty result each and created two tags with the same name. It is set
     * before the await and cleared in `finally`, the same shape createUser() uses.
     */
    async createTagInline() {
      if (!this.newTagReady) return;
      const name = this.newTagName.trim();
      const existing = this.tags.find(t => (t.name || "").toLowerCase() === name.toLowerCase());
      if (existing) {
        this.attachTag(existing.id);
        this.dismissNewTag();
        this.notify(`"${existing.name}" already existed; attached it`);
        return;
      }
      this.newTagCreating = true;
      try {
        const created = await api("POST", "/api/tags",
                                  { id: uuidv7(), name, lastModifiedDate: wireNow() });
        this.tags.push(created);
        this.tags.sort((a, b) => String(a.name || "").localeCompare(String(b.name || "")));
        this.attachTag(created.id);
        this.dismissNewTag();
        this.notify(`Added ${name}`);
      } catch (e) {
        this.notify(`Couldn't add tag: ${e.message}`, "danger");
      } finally {
        this.newTagCreating = false;
      }
    },

    attachTag(id) {
      if (!this.current) return;
      const ids = this.current.tagIds || [];
      if (!ids.includes(id)) { this.current.tagIds = [...ids, id]; this.touch(); }
    },

    async addClassifier(kind) {
      const name = (this.newClassifier[kind] || "").trim();
      if (!name) return;
      try {
        const created = await api("POST", this.endpointFor(kind),
                                  { id: uuidv7(), name, lastModifiedDate: wireNow() });
        this[this.collectionFor(kind)].push(created);
        this[this.collectionFor(kind)].sort((a, b) =>
          String(a.name || "").localeCompare(String(b.name || "")));
        this.newClassifier[kind] = "";
        this.notify(`Added ${name}`);
      } catch (e) {
        this.notify(`Couldn't add: ${e.message}`, "danger");
      }
    },

    async renameClassifier(kind, item, name) {
      const trimmed = (name || "").trim();
      if (!trimmed || trimmed === item.name) return;
      const previous = item.name;
      item.name = trimmed;                       // optimistic: the field already shows it
      try {
        await api("PUT", `${this.endpointFor(kind)}/${encodeURIComponent(item.id)}`,
                  { id: item.id, name: trimmed, lastModifiedDate: wireNow() });
        // A rename can change the heading of the list you're looking at.
        if (this.filter.kind === kind && this.filter.id === item.id) this.filter.label = trimmed;
      } catch (e) {
        item.name = previous;
        this.notify(`Couldn't rename: ${e.message}`, "danger");
      }
    },

    askDeleteClassifier(kind, item) { this.pendingClassifierDelete = { kind, item }; },

    async deleteClassifier() {
      const pending = this.pendingClassifierDelete;
      if (!pending) return;
      const { kind, item } = pending;
      this.pendingClassifierDelete = null;
      try {
        await api("DELETE", `${this.endpointFor(kind)}/${encodeURIComponent(item.id)}`);
        const key = this.collectionFor(kind);
        this[key] = this[key].filter(x => x.id !== item.id);
        // Recipes keep existing but lose the association, so refresh the list rather than
        // leaving stale ids behind in memory.
        await this.loadList();
        if (this.filter.kind === kind && this.filter.id === item.id) {
          this.setFilter("all", null, "All Recipes");
        }
        if (this.current) {
          this.current.categoryIds = (this.current.categoryIds || []).filter(x => x !== item.id);
          this.current.tagIds = (this.current.tagIds || []).filter(x => x !== item.id);
          if (this.current.courseId === item.id) this.current.courseId = null;
        }
        this.notify(`Deleted ${item.name}`);
      } catch (e) {
        this.notify(`Couldn't delete: ${e.message}`, "danger");
      }
    },

    /**
     * Whether a recipe belongs to the current library filter, ignoring the search box. Shared by
     * the list and by [setFilter]'s check that the open recipe still belongs to what's listed.
     */
    matchesFilter(r, f = this.filter) {
      if (f.kind === "favorites") return !!r.isFavorite;
      if (f.kind === "wantToMake") return !!r.wantToMake;
      if (f.kind === "course") return r.courseId === f.id;
      if (f.kind === "category") return (r.categoryIds || []).includes(f.id);
      if (f.kind === "tag") return (r.tagIds || []).includes(f.id);
      return true;
    },

    setFilter(kind, id, label) {
      this.section = "recipes";
      this.filter = { kind, id, label: label || "All Recipes" };
      this.pane = "list";
      // The detail column follows the list, as it does in the Swift client where detail is driven by
      // selection *within* the current list. Without this the right-hand pane goes on showing a
      // recipe the middle column no longer lists, and the two columns quietly disagree about where
      // you are. Editing can't reach here -- the editor is modal -- so there is nothing to discard.
      if (this.current && !this.matchesFilter(this.current)) {
        this.current = null;
        this.selectedId = null;
      }
    },

    /** "1 recipe" / "2 recipes" — the count row read wrong at exactly one. */
    recipeCountLabel(kind, id) {
      const n = this.countFor(kind, id);
      return `${n} ${n === 1 ? "recipe" : "recipes"}`;
    },

    countFor(kind, id) {
      if (kind === "course") return this.list.filter(r => r.courseId === id).length;
      if (kind === "category") return this.list.filter(r => (r.categoryIds || []).includes(id)).length;
      return this.list.filter(r => (r.tagIds || []).includes(id)).length;
    },

    courseName(id) { return id ? (this.courses.find(c => c.id === id) || {}).name || "" : ""; },

    /** Category and tag names for the read view's chip row. */
    /**
     * The quiet line under a recipe's name.
     *
     * Built as a list and joined, rather than four templates each carrying its own " \u00b7 ": that
     * spelling printed the separator for fields the recipe does not have, so a recipe with no
     * yield read "Main \u00b7  \u00b7 Serves 6 \u00b7 \u2605\u2605\u2605\u2605". Empty here also means the whole line
     * goes, rather than leaving a blank gap above the introduction.
     */
    get metaLine() {
      const r = this.current;
      if (!r) return "";
      return [
        this.courseName(r.courseId),
        r.yield,
        r.servings ? `Serves ${r.servings}` : "",
        r.rating ? "\u2605".repeat(r.rating) : "",
      ].map(part => String(part == null ? "" : part).trim()).filter(Boolean).join(" \u00b7 ");
    },

    /**
     * The one line under a recipe's name: its introduction, or failing that where it came from.
     * Whichever is present tells you what the recipe IS, which a modified-date never did.
     */
    /**
     * The row's second line. Sorting by "Last Made" swaps it for the date being sorted on, as the
     * CMP app does and for the same reason: otherwise that ordering has no visible explanation,
     * and the block of never-made recipes at the end reads as a bug rather than as the point.
     */
    rowSubtitle(r) {
      if (this.sortBy === "prepared") {
        return this.everMade(r) ? `Made ${this.relativeDate(r.lastPrepared)}` : "Never made";
      }
      return (r.introduction || r.source || r.sourceDetails || "").trim();
    },

    /**
     * The list asks for thumbnails, not full images: the server generates and caches those, so a
     * hundred rows cost a hundred small requests rather than a hundred full-size photos.
     */
    thumbUrl(r) {
      return r.imageFilename
        ? `/api/recipes/images/${encodeURIComponent(r.imageFilename)}/thumbnail`
        : null;
    },

    backToList() {
      if (this.mode === "edit" && this.dirty && !window.confirm("Discard unsaved changes?")) return;
      if (this.dirty) this.revert();
      this.pane = "list";
      this.mode = "read";
    },

    /* ---------------------------------------------------------------- chef mode -- */

    /**
     * Only from a recipe that is open and being read: chef mode hides the detail bar, so there
     * would be no way back out of an editor entered underneath it, and nothing to show if no
     * recipe were loaded.
     */
    enterChefMode() {
      if (!this.current || this.mode !== "read") return;
      this.chefMode = true;
      this.acquireWakeLock();
    },

    exitChefMode() {
      this.chefMode = false;
      this.releaseWakeLock();
    },

    /**
     * Screen Wake Lock is secure-context only, so a Salty reached over plain http on the LAN —
     * a normal way to run this — does not have it at all. Worth saying out loud in Preferences,
     * rather than offering a switch that silently does nothing.
     */
    get wakeLockSupported() {
      return typeof navigator !== "undefined" && "wakeLock" in navigator;
    },

    setWakeLockPref(on) {
      this.wakeLockPref = !!on;
      this.writeStored(this.wakeLockKey, this.wakeLockPref ? "1" : "0");
      // Takes effect now, not at the next chef mode: the switch is most likely to be reached by
      // someone who has just watched their screen do the wrong thing.
      if (this.wakeLockPref) this.acquireWakeLock(); else this.releaseWakeLock();
    },

    /**
     * Best effort, deliberately silent. The request is refused when the document is not visible,
     * when the browser is saving power, and everywhere the API is missing — none of which is worth
     * interrupting someone mid-recipe about, because the recipe is still on screen either way.
     */
    async acquireWakeLock() {
      if (!this.chefMode || !this.wakeLockPref || !this.wakeLockSupported) return;
      if (this._wakeLock) return;
      try {
        const lock = await navigator.wakeLock.request("screen");
        // Chef mode may have ended while that was in flight — the request is a round trip through
        // the platform, and Escape is one keystroke. Holding a lock for a mode that is over is
        // precisely the drain the preference exists to prevent.
        if (!this.chefMode || !this.wakeLockPref) { lock.release().catch(() => {}); return; }
        this._wakeLock = lock;
        // Fires for a lock the platform took back as well as for one we released, so this is the
        // only place the handle is cleared on that path.
        lock.addEventListener("release", () => { this._wakeLock = null; });
      } catch { /* refused: nothing to say, and nothing has broken */ }
    },

    releaseWakeLock() {
      const lock = this._wakeLock;
      this._wakeLock = null;
      if (lock) lock.release().catch(() => {});
    },

    /** The Save button: write if there's anything to write, then close. */
    async saveAndClose() {
      if (this.dirty) await this.save();
      if (!this.dirty) this.mode = "read";      // stays open when the save failed
    },

    /**
     * The Cancel button: throw away everything done since the dialog opened and close.
     *
     * Asks first only when there is something to lose, and reloads from the server rather than
     * merely clearing the flag -- otherwise the edited values would sit in `current` and the
     * reading view behind would render text that was never saved.
     */
    async cancelEdit() {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      if (this.isDraft) { this.discardDraft(); return; }
      this.resetImageStaging();
      const id = this.current && this.current.id;
      this.dirty = false;
      // Reload BEFORE closing. Closing first leaves the reload in flight while the editor is
      // reachable again, and when it lands it replaces `current` -- tearing down the form and
      // taking any keystrokes typed in between with it.
      if (id) await this.open(id, { keepMode: true });
      this.mode = "read";
    },

    /* --- image --- */

    /**
     * Stages a chosen file and previews it locally. Nothing is uploaded until Save, so Revert and
     * the dialog's discard path undo an image change like any other edit.
     */
    chooseImage(event) {
      const file = event.target.files && event.target.files[0];
      event.target.value = "";                 // so re-picking the same file fires change again
      if (!file) return;
      if (!IMAGE_TYPES.includes(file.type)) {
        this.notify("Images must be JPEG, PNG or GIF.", "danger");
        return;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        this.notify(`That image is ${(file.size / 1024 / 1024).toFixed(1)} MB; the limit is 25 MB.`, "danger");
        return;
      }
      this.releasePendingImage();
      this.pendingImageFile = file;
      this.pendingImageUrl = URL.createObjectURL(file);
      this.pendingImageRemoval = false;
      this.touch();
    },

    /** Stages removal. An image picked but not yet saved is simply dropped instead. */
    clearImage() {
      if (this.pendingImageFile) {
        this.releasePendingImage();
        // Falls back to whatever was already stored; only stage a real removal if that exists.
        if (!(this.current && this.current.imageFilename)) return;
      }
      this.pendingImageRemoval = true;
      this.touch();
    },

    /** Object URLs are held by the document until revoked, so drop the old one on every swap. */
    releasePendingImage() {
      if (this.pendingImageUrl) URL.revokeObjectURL(this.pendingImageUrl);
      this.pendingImageUrl = null;
      this.pendingImageFile = null;
    },

    resetImageStaging() {
      this.releasePendingImage();
      this.pendingImageRemoval = false;
    },

    /**
     * Applies a staged image change after the recipe body has been written. Order matters: the
     * body PUT carries the OLD imageFilename, so doing this first would let that stale value
     * overwrite what these endpoints just set.
     *
     * Both endpoints take the timestamp the change happened, which is what lets the other clients
     * see it via /sync/manifest instead of treating their own copy as current.
     */
    async applyImageChanges() {
      if (!this.current) return;
      const id = encodeURIComponent(this.current.id);
      const stamp = wireNow();
      if (this.pendingImageFile) {
        const body = new FormData();
        body.append("file", this.pendingImageFile, this.pendingImageFile.name || "image");
        body.append("lastModifiedImageDate", stamp);
        const resp = await fetch(`/api/recipes/${id}/image`, {
          method: "POST",
          headers: { "X-CSRF-Token": SALTY.csrfToken },   // no Content-Type: the browser sets the boundary
          credentials: "same-origin",
          body,
        });
        if (!resp.ok) {
          let detail = `${resp.status} ${resp.statusText}`;
          try { const j = await resp.json(); if (j && j.error) detail = j.error; } catch { /* keep status */ }
          throw new Error(detail);
        }
        const saved = await resp.json();
        this.current.imageFilename = saved.filename;
        this.current.lastModifiedImageDate = stamp;
      } else if (this.pendingImageRemoval) {
        await api("DELETE", `/api/recipes/${id}/image?lastModifiedImageDate=${encodeURIComponent(stamp)}`);
        this.current.imageFilename = null;
        this.current.lastModifiedImageDate = stamp;
      }
      this.resetImageStaging();
    },

    /* --- notes, variations and preparation times --- */

    /**
     * The three repeatable sub-lists behave identically -- append a row carrying a fresh id, or
     * drop one by id -- so they share these rather than being written out three times. Only the
     * shape of a blank row differs, and that's the caller's business.
     */
    addSubRow(field, blank) {
      if (!this.current) return;
      this.current[field] = [...(this.current[field] || []), { id: uuidv7(), ...blank }];
      this.touch();
    },

    removeSubRow(field, id) {
      if (!this.current) return;
      this.current[field] = (this.current[field] || []).filter(r => r.id !== id);
      this.touch();
    },

    /* --- nutrition --- */

    get nutritionGroups() { return NUTRITION_GROUPS; },

    /** Blank rather than 0 for an absent value, so an untouched field reads as unknown. */
    nutritionValue(key) {
      const n = this.current && this.current.nutrition;
      const v = n ? n[key] : null;
      return (v === null || v === undefined) ? "" : v;
    },

    /**
     * Writes one nutrition field, creating the record on first use and removing it again once the
     * last value is cleared -- so a recipe nobody entered nutrition for doesn't start carrying an
     * empty record with an id.
     *
     * An emptied field stores null, never 0: "0 g of fat" is a claim about the food and "unknown"
     * is not, and the native clients render the two differently.
     */
    setNutrition(key, raw, isText = false) {
      if (!this.current) return;
      const text = String(raw ?? "").trim();
      let value;
      if (isText) {
        value = text === "" ? null : text;
      } else {
        if (text === "") value = null;
        else {
          const n = Number(text);
          if (Number.isNaN(n)) return;      // mid-typing garbage: leave the stored value alone
          value = n;
        }
      }
      if (!this.current.nutrition) this.current.nutrition = { id: uuidv7() };
      this.current.nutrition[key] = value;
      if (!NUTRITION_FIELDS.some(f => {
        const v = this.current.nutrition[f.key];
        return v !== null && v !== undefined && v !== "";
      })) {
        this.current.nutrition = null;
      }
      this.touch();
    },

    /* --- presentation --- */

    touch() { this.dirty = true; },

    /**
     * Handed to <wa-toast>, which owns the stack, the timers, the pause-on-hover and the live
     * region. It is not merely less code than rendering the list here: a dialog is a native
     * <dialog> in the browser's top layer, and most of these messages are raised from inside one,
     * so a stack in the ordinary document showed them underneath it. See the element's comment.
     *
     * whenDefined because create() only exists once the element has upgraded, and the components
     * are loaded as a module — a toast fired by an early failure would otherwise land on a plain
     * HTMLElement and throw. Duration matches what the hand-rolled stack used; the component
     * pauses it while the pointer is over a toast, which the old one did not.
     */
    notify(text, variant = "success") {
      const icon = variant === "danger" ? "circle-exclamation" : "circle-check";
      customElements.whenDefined("wa-toast").then(() => {
        document.querySelector("wa-toast")?.create(text, { variant, icon, duration: 3200 });
      });
    },

    relativeDate(iso) {
      if (!iso) return "";
      const then = new Date(iso);
      const days = Math.floor((Date.now() - then.getTime()) / 86400000);
      if (isNaN(days)) return "";
      if (days <= 0) return "today";
      if (days === 1) return "yesterday";
      if (days < 7) return `${days} days ago`;
      return then.toLocaleDateString(undefined, { month: "short", day: "numeric" });
    },

    /** yyyy-MM-dd for <input type="date">, which refuses anything else. */
    dateOnly(iso) { return iso ? String(iso).slice(0, 10) : ""; },

    /**
     * lastPrepared travels with lastModifiedPreparedDate: the server merges the pair by that stamp
     * rather than the body clock, so a stale body upload can't clobber a newer "mark as made".
     * Sending one without the other would make this edit invisible to that merge.
     */
    setLastPrepared(value) {
      if (!this.current) return;
      this.current.lastPrepared = value ? new Date(`${value}T12:00:00Z`).toISOString() : null;
      this.current.lastModifiedPreparedDate = wireNow();
      this.touch();
    },

    stepNumber(i) {
      let n = 0;
      for (let j = 0; j <= i; j++) if (!this.directions[j].isHeading) n++;
      return n;
    },

    scaledHTML(text) {
      const factor = SCALES[this.scaleIdx];
      if (factor === 1) return esc(text);
      const p = parseLeadingAmount(text || "");
      if (!p) return esc(text);
      const out = formatAmount(p.value * factor);
      if (out === null) return esc(text);
      return `<span class="scaled">${esc(out)}</span>${esc(p.rest)}`;
    },

    bumpScale(d) {
      this.scaleIdx = Math.max(0, Math.min(SCALES.length - 1, this.scaleIdx + d));
      if (this.mode === "edit") this.mode = "read";   // scaling is a read-mode view of the data
    },

    /* --- row editing --- */

    addRow(which, isHeading) {
      this[which].push(newRow(isHeading ? "New section" : "", { isHeading }));
      this.touch();
    },

    removeRow(which, i) { this[which].splice(i, 1); this.touch(); },

    /** Called by @alpinejs/sort with the dragged item's key and its new index. */
    reorder(which, itemId, position) {
      const arr = this[which];
      const from = arr.findIndex(r => r.id === itemId);
      if (from === -1 || from === position) return;
      arr.splice(position, 0, arr.splice(from, 1)[0]);
      this.touch();
    },

    /**
     * Moves a row one place, the keyboard-reachable equivalent of dragging it.
     *
     * Reordering was bound only to x-sort with an aria-hidden drag handle, so it could not be done
     * without a pointer at all -- a WCAG 2.1.1 failure on new UI. @alpinejs/sort has no keyboard
     * mode, so these buttons are the way in; they reuse the same reorder() the drag path calls.
     */
    moveRow(which, itemId, delta) {
      const arr = this[which];
      const from = arr.findIndex(r => r.id === itemId);
      if (from === -1) return;
      const to = from + delta;
      if (to < 0 || to >= arr.length) return;
      this.reorder(which, itemId, to);
      // Keep focus on the button that was pressed, so a run of moves doesn't need re-tabbing.
      this.$nextTick(() => {
        const sel = `[data-move="${which}:${itemId}:${delta > 0 ? "down" : "up"}"]`;
        const el = document.querySelector(sel);
        if (el && typeof el.focus === "function") el.focus();
      });
    },

    /* --- data --- */

    async loadList() {
      this.listLoading = true;
      try {
        const rows = await api("GET", "/api/recipes?size=200");
        this.list = (rows || []).slice().sort((a, b) =>
          String(b.lastModifiedDate || "").localeCompare(String(a.lastModifiedDate || "")));
        this.loadError = "";
        // Nothing is opened here, on any width. The list is sorted by last-modified, so opening
        // its first row landed you on whichever recipe happened to be edited last -- neither where
        // you left off nor anything you clicked, which is what made it read as arbitrary. The
        // empty state is the honest landing view; the pane fills in when you pick a recipe.
      } catch (e) {
        this.loadError = e.message;
      } finally {
        this.listLoading = false;
      }
    },

    /**
     * Loads a recipe into the detail pane.
     *
     * `keepMode` exists for the discard path: that reload finishes AFTER the user may have already
     * re-opened the editor, and unconditionally forcing "read" at the end would slam the dialog
     * shut under them -- and silently drop whatever they had just typed.
     */
    async open(id, { keepMode = false } = {}) {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      this.resetImageStaging();
      this.loadingRecipe = true;
      try {
        const r = await api("GET", `/api/recipes/${encodeURIComponent(id)}`);
        // wa-select multiple binds to an array; normalise so x-model always has one, and so a
        // recipe that has never been categorised behaves like one with an empty list.
        r.categoryIds = r.categoryIds || [];
        r.tagIds = r.tagIds || [];
        // Same reason: the repeatable sub-editors bind straight to these, so they must be arrays
        // even for a recipe that has never had a note, a variation or a time.
        r.notes = r.notes || [];
        r.variations = r.variations || [];
        r.preparationTimes = r.preparationTimes || [];
        this.current = r;
        this.selectedId = r.id;
        this.isDraft = false;
        // Rows are held separately so Alpine's reactivity and x-sort keys stay simple; they're
        // folded back into `current` on save.
        this.ingredients = (r.ingredients || []).map(x => ({
          id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: !!x.isMain,
        }));
        this.directions = (r.directions || []).map(x => ({
          id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: false,
        }));
        this.dirty = false;
        if (!keepMode) this.mode = "read";
        this.pane = "detail";
      } catch (e) {
        this.notify(`Couldn't open recipe: ${e.message}`, "danger");
      } finally {
        this.loadingRecipe = false;
      }
    },

    closeRecipe() {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      this.dirty = false;
      this.current = null;
      this.selectedId = null;
    },

    /**
     * Escape and the dialog's close button both arrive here. `wa-hide` is cancelable, so an edit
     * with unsaved work can refuse to close. Saying yes has to actually discard: clearing the flag
     * alone would leave the edited values sitting in `current`, and the read view behind would then
     * render text that was never saved.
     */
    onEditDialogHide(event) {
      if (this.mode !== "edit") return;
      if (this.dirty) {
        if (!window.confirm("Discard unsaved changes?")) { event.preventDefault(); return; }
        this.revert();
      }
      this.mode = "read";
    },

    async save() {
      if (!this.current) return;
      this.saving = true;
      try {
        // Spread the loaded recipe so fields this screen doesn't edit — imageFilename,
        // lastModifiedImageDate, categoryIds, tagIds, notes, variations, preparationTimes,
        // nutrition, courseId — round-trip untouched. PUT replaces the row, so dropping any of
        // them here would silently wipe them.
        const payload = {
          ...this.current,
          // servings is an Int on the wire; a decimal typed here would fail deserialisation and
          // take the entire recipe save down with a generic 500.
          servings: this.current.servings == null || this.current.servings === ""
            ? null : Math.round(Number(this.current.servings)) || null,
          ingredients: this.ingredients,
          directions: this.directions,
          lastModifiedDate: wireNow(),
        };
        const saved = await api("PUT", `/api/recipes/${encodeURIComponent(this.current.id)}`, payload);
        this.current = saved;
        this.syncListRow();          // the body IS stored now, whatever the image does next

        // After the body, so the PUT's copy of imageFilename can't overwrite what these set. A
        // failure here must not be reported as "Save failed": the recipe was written, only the
        // image wasn't, and saying otherwise sends the user looking for lost text that is safe.
        try {
          await this.applyImageChanges();
        } catch (imageError) {
          this.syncListRow();
          this.notify(`Recipe saved, but the image didn't upload: ${imageError.message}`, "danger");
          return;                     // dirty stays true; the staged file survives for a retry
        }
        this.dirty = false;
        this.isDraft = false;         // it exists on the server now
        this.syncListRow();
        this.notify("Saved");
      } catch (e) {
        this.notify(`Save failed: ${e.message}`, "danger");
      } finally {
        this.saving = false;
      }
    },

    /** Keeps the middle column's row in step with `current` after a write. */
    syncListRow() {
      if (!this.current) return;
      const row = { ...this.current };
      const idx = this.list.findIndex(x => x.id === row.id);
      if (idx > -1) this.list.splice(idx, 1, row); else this.list.unshift(row);
    },

    /**
     * Throws away in-memory edits by reloading the stored recipe. Both callers set `mode`
     * themselves, so this reload must not touch it: it completes asynchronously, and forcing
     * "read" at the end would close an editor the user had meanwhile re-opened.
     */
    async revert() {
      this.resetImageStaging();
      if (!this.current) return;
      if (this.isDraft) { this.discardDraft(); return; }
      this.dirty = false;
      await this.open(this.current.id, { keepMode: true });
    },

    /** A draft was never stored, so discarding it is simply forgetting it. */
    discardDraft() {
      this.resetImageStaging();
      this.isDraft = false;
      this.dirty = false;
      this.mode = "read";
      this.current = null;
      this.selectedId = null;
    },

    async doDelete() {
      this.confirmDelete = false;
      if (!this.current) return;
      const gone = this.current.id;
      try {
        await api("DELETE", `/api/recipes/${encodeURIComponent(gone)}`);
        this.list = this.list.filter(x => x.id !== gone);
        this.current = null;
        this.selectedId = null;
        this.ingredients = [];
        this.directions = [];
        this.dirty = false;
        this.notify("Recipe deleted");
        // Nothing takes its place: the next-most-recently-modified recipe is no more what you
        // were reading than it was on load, and dropping straight into an unrelated recipe hides
        // the fact that the delete happened. The empty state is the result of the action.
      } catch (e) {
        this.notify(`Delete failed: ${e.message}`, "danger");
      }
    },

    async createRecipe() {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      const id = uuidv7();
      const now = wireNow();
      try {
        const saved = await api("PUT", `/api/recipes/${id}`, {
          id, name: "New Recipe", createdDate: now, lastModifiedDate: now,
          ingredients: [], directions: [],
        });
        this.list.unshift(saved);
        await this.open(saved.id);
        this.mode = "edit";   // a blank recipe has nothing to read
        this.notify("Recipe created");
      } catch (e) {
        this.notify(`Couldn't create recipe: ${e.message}`, "danger");
      }
    },
  };
}
