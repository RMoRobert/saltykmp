/*
 * Salty web recipe editor — Alpine + Web Awesome.
 *
 * Talks to the same JSON API the native clients use (/api/recipes), authenticated by the web session
 * cookie rather than a Bearer token. Every state-changing call echoes the session's CSRF token; see
 * ApiCsrfGuard on the server.
 *
 * No build step: Alpine and Web Awesome come from a CDN (see editor.mustache).
 *
 * Note there is no x-model workaround here. Shoelace 2 fired only sl-input/sl-change, which broke
 * Alpine's two-way binding; Web Awesome 3 emits standard `input` and `change` alongside its wa-*
 * events, so x-model works in both directions. Verified, not assumed.
 */
function saltyEditor() {
  "use strict";

  const SALTY = window.SALTY || { csrfToken: "" };

  const SCALES = [0.5, 1, 1.5, 2, 3, 4];
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
    mdUp: window.innerWidth >= 900,
    query: "",
    list: [],
    listLoading: true,
    loadError: "",
    current: null,
    ingredients: [],
    directions: [],
    selectedId: null,
    mode: "edit",
    scaleIdx: 1,
    dirty: false,
    saving: false,
    loadingRecipe: false,
    confirmDelete: false,
    toasts: [],
    _toastSeq: 0,

    // computed
    get scaleLabel() { return `${SCALES[this.scaleIdx]}×`; },
    get ingCount() { return this.ingredients.filter(r => !r.isHeading).length; },
    get dirCount() { return this.directions.filter(r => !r.isHeading).length; },
    get filtered() {
      const q = this.query.trim().toLowerCase();
      if (!q) return this.list;
      return this.list.filter(r => (r.name || "").toLowerCase().includes(q));
    },

    async init() {
      // matchMedia rather than a resize listener seeded from innerWidth: the window can still be
      // settling when Alpine initialises (a pane that opens narrow and widens, a restored window),
      // and a stale mdUp silently skipped the auto-open below.
      const wide = window.matchMedia("(min-width: 900px)");
      this.mdUp = wide.matches;
      wide.addEventListener("change", e => { this.mdUp = e.matches; });
      window.addEventListener("beforeunload", e => {
        if (this.dirty) { e.preventDefault(); e.returnValue = ""; }
      });
      await this.loadList();
    },

    /* --- presentation --- */

    touch() { this.dirty = true; },

    notify(text, variant = "success") {
      const id = ++this._toastSeq;
      this.toasts.push({ id, text, variant });
      setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 3200);
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

    /* --- data --- */

    async loadList() {
      this.listLoading = true;
      try {
        const rows = await api("GET", "/api/recipes?size=200");
        this.list = (rows || []).slice().sort((a, b) =>
          String(b.lastModifiedDate || "").localeCompare(String(a.lastModifiedDate || "")));
        this.loadError = "";
        // Read the viewport fresh rather than trusting cached state: on a narrow screen the list
        // is the landing view, so we deliberately don't auto-open there.
        const wideNow = window.matchMedia("(min-width: 900px)").matches;
        if (!this.selectedId && this.list.length && wideNow) await this.open(this.list[0].id);
      } catch (e) {
        this.loadError = e.message;
      } finally {
        this.listLoading = false;
      }
    },

    async open(id) {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      this.loadingRecipe = true;
      try {
        const r = await api("GET", `/api/recipes/${encodeURIComponent(id)}`);
        this.current = r;
        this.selectedId = r.id;
        // Rows are held separately so Alpine's reactivity and x-sort keys stay simple; they're
        // folded back into `current` on save.
        this.ingredients = (r.ingredients || []).map(x => ({
          id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: !!x.isMain,
        }));
        this.directions = (r.directions || []).map(x => ({
          id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: false,
        }));
        this.dirty = false;
      } catch (e) {
        this.notify(`Couldn't open recipe: ${e.message}`, "danger");
      } finally {
        this.loadingRecipe = false;
      }
    },

    closeRecipe() {
      if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
      this.current = null;
      this.selectedId = null;
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
          ingredients: this.ingredients,
          directions: this.directions,
          lastModifiedDate: wireNow(),
        };
        const saved = await api("PUT", `/api/recipes/${encodeURIComponent(this.current.id)}`, payload);
        this.current = saved;
        this.dirty = false;
        const idx = this.list.findIndex(x => x.id === saved.id);
        if (idx > -1) this.list.splice(idx, 1, saved); else this.list.unshift(saved);
        this.notify("Saved");
      } catch (e) {
        this.notify(`Save failed: ${e.message}`, "danger");
      } finally {
        this.saving = false;
      }
    },

    revert() { if (this.current) { this.dirty = false; this.open(this.current.id); } },

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
        if (this.list.length && this.mdUp) await this.open(this.list[0].id);
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
        this.notify("Recipe created");
      } catch (e) {
        this.notify(`Couldn't create recipe: ${e.message}`, "danger");
      }
    },
  };
}
