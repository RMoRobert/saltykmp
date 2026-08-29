/*
 * Salty web recipe editor.
 *
 * Talks to the same JSON API the native clients use (/api/recipes, /api/categories, ...), authenticated
 * by the web session cookie rather than a Bearer token. Every state-changing call echoes the session's
 * CSRF token; see ApiCsrfGuard on the server.
 *
 * No build step: Vue and Vuetify are vendored under /static/vendor (see the README there).
 */
(function () {
  "use strict";

  var SALTY = window.SALTY || { csrfToken: "" };

  /* ---------------------------------------------------------------- api --- */

  function apiUrl(path) { return path; }

  async function api(method, path, body) {
    var headers = { "Accept": "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && method !== "HEAD") headers["X-CSRF-Token"] = SALTY.csrfToken;

    var resp = await fetch(apiUrl(path), {
      method: method,
      headers: headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body)
    });

    // The session expired or was cleared — send the user back to the login page rather than
    // failing silently with an unexplained error.
    if (resp.status === 401) { window.location.href = "/login"; throw new Error("Not signed in"); }
    if (resp.status === 204) return null;

    var text = await resp.text();
    var data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }

    if (!resp.ok) {
      var msg = (data && (data.error || data.message)) || (resp.status + " " + resp.statusText);
      var err = new Error(msg);
      err.status = resp.status;
      throw err;
    }
    return data;
  }

  /* ------------------------------------------------------------- helpers --- */

  // UUIDv7: 48-bit big-endian millisecond timestamp, version 7, variant 10, random rest.
  // Time-ordered so ids sort chronologically, matching what the Swift and KMP clients mint.
  // Uppercase, because the rest of Salty stores ids as uppercase TEXT and SQLite compares them
  // with a binary collation — a lowercase id would neither sort nor match correctly.
  function uuidv7() {
    var ms = Date.now();
    var bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[0] = (ms / 1099511627776) & 0xff;   // ms >> 40
    bytes[1] = (ms / 4294967296) & 0xff;      // ms >> 32
    bytes[2] = (ms >>> 24) & 0xff;
    bytes[3] = (ms >>> 16) & 0xff;
    bytes[4] = (ms >>> 8) & 0xff;
    bytes[5] = ms & 0xff;
    bytes[6] = 0x70 | (bytes[6] & 0x0f);      // version 7
    bytes[8] = 0x80 | (bytes[8] & 0x3f);      // variant 10xx
    var hex = [];
    for (var i = 0; i < 16; i++) hex.push(bytes[i].toString(16).padStart(2, "0"));
    var s = hex.join("").toUpperCase();
    return [s.slice(0, 8), s.slice(8, 12), s.slice(12, 16), s.slice(16, 20), s.slice(20)].join("-");
  }

  /** The wire timestamp the server expects: yyyy-MM-dd'T'HH:mm:ss.SSS'Z' — exactly toISOString(). */
  function wireNow() { return new Date().toISOString(); }

  function relativeDate(iso) {
    if (!iso) return "";
    var then = new Date(iso), now = new Date();
    var days = Math.floor((now - then) / 86400000);
    if (isNaN(days)) return "";
    if (days <= 0) return "today";
    if (days === 1) return "yesterday";
    if (days < 7) return days + " days ago";
    return then.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  /* ------------------------------------------------------------- scaling --- */

  var SCALES = [0.5, 1, 1.5, 2, 3, 4];
  var FRACTIONS = [[1,8,"1/8"],[1,4,"1/4"],[1,3,"1/3"],[3,8,"3/8"],[1,2,"1/2"],
                   [5,8,"5/8"],[2,3,"2/3"],[3,4,"3/4"],[7,8,"7/8"]];

  function formatAmount(v) {
    if (!isFinite(v) || v <= 0) return null;
    var whole = Math.floor(v + 1e-9), frac = v - whole;
    if (frac < 0.02) return String(whole);
    var best = null, bestErr = 1;
    for (var i = 0; i < FRACTIONS.length; i++) {
      var err = Math.abs(frac - FRACTIONS[i][0] / FRACTIONS[i][1]);
      if (err < bestErr) { bestErr = err; best = FRACTIONS[i][2]; }
    }
    if (bestErr > 0.04) return (Math.round(v * 100) / 100).toString();
    return whole > 0 ? whole + " " + best : best;
  }

  function parseLeadingAmount(text) {
    var m = text.match(/^\s*(\d+\s+\d+\/\d+|\d+\/\d+|\d*\.\d+|\d+)/);
    if (!m) return null;
    var tok = m[1].trim(), val;
    if (tok.indexOf(" ") > -1) {
      var p = tok.split(/\s+/), f = p[1].split("/");
      val = parseFloat(p[0]) + parseInt(f[0], 10) / parseInt(f[1], 10);
    } else if (tok.indexOf("/") > -1) {
      var g = tok.split("/"); val = parseInt(g[0], 10) / parseInt(g[1], 10);
    } else { val = parseFloat(tok); }
    return { value: val, rest: text.slice(m[0].length) };
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  /* --------------------------------------------------------------- icons --- */

  var ICONS = {};

  function makeVuetify() {
    var h = Vue.h;
    var svgSet = {
      component: function (props) {
        // Sized in em, not %: an <svg> with only a viewBox falls back to its default intrinsic
        // size (300x300), and a percentage would resolve against a .v-icon that is itself
        // sized by its content -- circular, and it lands right back on 300px.
        return h("svg", {
          class: "v-icon__svg", xmlns: "http://www.w3.org/2000/svg",
          viewBox: "0 0 24 24", width: "1em", height: "1em",
          role: "img", "aria-hidden": "true"
        }, [h("path", { d: props.icon, fill: "currentColor" })]);
      }
    };
    var A = ICONS;
    return Vuetify.createVuetify({
      icons: {
        defaultSet: "svgpaths",
        sets: { svgpaths: svgSet },
        aliases: {
          collapse: A.mdiChevronUp, complete: A.mdiCheck, cancel: A.mdiClose, close: A.mdiClose,
          delete: A.mdiClose, clear: A.mdiClose, success: A.mdiCheckCircle, info: A.mdiInformation,
          warning: A.mdiAlert, error: A.mdiAlertCircle, prev: A.mdiChevronLeft, next: A.mdiChevronRight,
          checkboxOn: A.mdiCheckboxMarked, checkboxOff: A.mdiCheckboxBlankOutline,
          checkboxIndeterminate: A.mdiMinusBox, delimiter: A.mdiCircleSmall,
          sortAsc: A.mdiArrowUp, sortDesc: A.mdiArrowDown, expand: A.mdiChevronDown,
          menu: A.mdiDotsVertical, subgroup: A.mdiMenuDown, dropdown: A.mdiMenuDown,
          radioOn: A.mdiRadioboxMarked, radioOff: A.mdiRadioboxBlank, edit: A.mdiPencil,
          ratingEmpty: A.mdiStarOutline, ratingFull: A.mdiStar, ratingHalf: A.mdiStarHalfFull,
          loading: A.mdiUnfoldMoreHorizontal, first: A.mdiPageFirst, last: A.mdiPageLast,
          unfold: A.mdiUnfoldMoreHorizontal, file: A.mdiPaperclip, plus: A.mdiPlus, minus: A.mdiMinus,
          calendar: A.mdiCalendar, sort: A.mdiSort, eyeDropper: A.mdiEyeOutline, upload: A.mdiImagePlus
        }
      },
      theme: {
        defaultTheme: "saltyLight",
        themes: {
          saltyLight: { dark: false, colors: {
            background: "#F2F4F3", surface: "#FFFFFF", primary: "#1E8C6E", secondary: "#4A5450",
            error: "#B3392F", warning: "#C98A1E", info: "#2C6E8C", success: "#1E8C6E" } },
          saltyDark: { dark: true, colors: {
            background: "#161A19", surface: "#212826", primary: "#3FB894", secondary: "#B6C0BC",
            error: "#E0756A", warning: "#E0AB4A", info: "#6FB5D1", success: "#3FB894" } }
        }
      },
      // Material's touch defaults read as a stretched phone app on a desktop window, so tighten
      // the things that carry text. NOT via `global`: that applies density to every component that
      // accepts it, including VBtn, where it stacks on top of size="small" and collapses a button
      // to ~16px tall. Density belongs on the input-ish components only.
      defaults: {
        VTextField: { density: "compact", variant: "outlined", hideDetails: true },
        VTextarea: { density: "compact", hideDetails: true },
        VSelect: { density: "compact", variant: "outlined", hideDetails: true },
        VList: { density: "compact" },
        VToolbar: { density: "compact" }
      }
    });
  }

  /* ----------------------------------------------------------------- app --- */

  var TEMPLATE = "";

  function buildApp(vuetify) {
    return Vue.createApp({
      data: function () {
        return {
          ic: ICONS,
          drawer: true,
          width: window.innerWidth,
          query: "",
          list: [],
          listLoading: true,
          current: null,        // the full ServerRecipe as loaded, minus rows (kept separately)
          ingredients: [],
          directions: [],
          selectedId: null,
          mode: "edit",
          scaleIdx: 1,
          dirty: false,
          saving: false,
          loadingRecipe: false,
          confirmDelete: false,
          loadError: "",
          snack: { show: false, text: "", color: undefined },
          difficulties: [
            { title: "Not set", value: 0 }, { title: "Easy", value: 1 },
            { title: "Moderate", value: 2 }, { title: "Hard", value: 3 }
          ]
        };
      },
      computed: {
        mdAndUp: function () { return this.width >= 960; },
        scaleLabel: function () { return SCALES[this.scaleIdx] + "×"; },
        filtered: function () {
          var q = this.query.trim().toLowerCase();
          if (!q) return this.list;
          return this.list.filter(function (r) {
            return (r.name || "").toLowerCase().indexOf(q) > -1;
          });
        },
        ingCount: function () { return this.ingredients.filter(function (r) { return !r.isHeading; }).length; },
        dirCount: function () { return this.directions.filter(function (r) { return !r.isHeading; }).length; }
      },
      methods: {
        notify: function (text, color) { this.snack = { show: true, text: text, color: color }; },
        touch: function () { this.dirty = true; },
        relativeDate: relativeDate,

        stepNumber: function (i) {
          var n = 0;
          for (var j = 0; j <= i; j++) if (!this.directions[j].isHeading) n++;
          return n;
        },

        scaledHTML: function (text) {
          var factor = SCALES[this.scaleIdx];
          if (factor === 1) return escapeHtml(text);
          var p = parseLeadingAmount(text || "");
          if (!p) return escapeHtml(text);
          var out = formatAmount(p.value * factor);
          if (out === null) return escapeHtml(text);
          return '<span class="scaled">' + escapeHtml(out) + "</span>" + escapeHtml(p.rest);
        },
        bumpScale: function (d) {
          this.scaleIdx = Math.max(0, Math.min(SCALES.length - 1, this.scaleIdx + d));
          if (this.mode === "edit") this.mode = "read";
        },

        add: function (which, heading) {
          var row = { id: uuidv7(), text: heading ? "New section" : "", isHeading: heading };
          if (which === "ingredients") row.isMain = false;
          this[which].push(row);
          this.touch();
        },
        remove: function (which, i) { this[which].splice(i, 1); this.touch(); },
        move: function (which, i, d) {
          var arr = this[which], j = i + d;
          if (j < 0 || j >= arr.length) return;
          var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
          this.touch();
        },

        async loadList() {
          this.listLoading = true;
          try {
            var rows = await api("GET", "/api/recipes?size=200");
            this.list = (rows || []).slice().sort(function (a, b) {
              return String(b.lastModifiedDate || "").localeCompare(String(a.lastModifiedDate || ""));
            });
            this.loadError = "";
            if (!this.selectedId && this.list.length) await this.open(this.list[0].id);
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
            var r = await api("GET", "/api/recipes/" + encodeURIComponent(id));
            this.current = r;
            this.selectedId = r.id;
            // Rows are edited separately so Vue reactivity is straightforward; they are folded
            // back into `current` on save.
            this.ingredients = (r.ingredients || []).map(function (x) {
              return { id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading, isMain: !!x.isMain };
            });
            this.directions = (r.directions || []).map(function (x) {
              return { id: x.id || uuidv7(), text: x.text || "", isHeading: !!x.isHeading };
            });
            this.dirty = false;
            if (!this.mdAndUp) this.drawer = false;
          } catch (e) {
            this.notify("Couldn't open recipe: " + e.message, "error");
          } finally {
            this.loadingRecipe = false;
          }
        },

        async save() {
          if (!this.current) return;
          this.saving = true;
          try {
            // Spread the loaded recipe so fields this screen doesn't edit — imageFilename,
            // lastModifiedImageDate, lastPrepared/lastModifiedPreparedDate, categoryIds, tagIds,
            // notes, variations, preparationTimes, nutrition — round-trip untouched. Dropping any of
            // them here would wipe them on the server, since PUT replaces the row.
            var payload = Object.assign({}, this.current, {
              ingredients: this.ingredients,
              directions: this.directions,
              lastModifiedDate: wireNow()
            });
            var saved = await api("PUT", "/api/recipes/" + encodeURIComponent(this.current.id), payload);
            this.current = saved;
            this.dirty = false;
            var idx = this.list.findIndex(function (x) { return x.id === saved.id; });
            if (idx > -1) this.list.splice(idx, 1, saved);
            this.notify("Saved");
          } catch (e) {
            this.notify("Save failed: " + e.message, "error");
          } finally {
            this.saving = false;
          }
        },

        async doDelete() {
          this.confirmDelete = false;
          try {
            await api("DELETE", "/api/recipes/" + encodeURIComponent(this.current.id));
            var gone = this.current.id;
            this.list = this.list.filter(function (x) { return x.id !== gone; });
            this.current = null; this.selectedId = null;
            this.ingredients = []; this.directions = []; this.dirty = false;
            this.notify("Recipe deleted");
            if (this.list.length) await this.open(this.list[0].id);
          } catch (e) {
            this.notify("Delete failed: " + e.message, "error");
          }
        },

        async createRecipe() {
          if (this.dirty && !window.confirm("Discard unsaved changes?")) return;
          var id = uuidv7();
          var now = wireNow();
          try {
            var saved = await api("PUT", "/api/recipes/" + id, {
              id: id, name: "New Recipe", createdDate: now, lastModifiedDate: now,
              ingredients: [], directions: []
            });
            this.list.unshift(saved);
            await this.open(saved.id);
            this.notify("Recipe created");
          } catch (e) {
            this.notify("Couldn't create recipe: " + e.message, "error");
          }
        },

        revert: function () {
          if (this.current) this.open(this.current.id);
        }
      },

      async mounted() {
        var self = this;
        window.addEventListener("resize", function () { self.width = window.innerWidth; });
        window.addEventListener("beforeunload", function (e) {
          if (self.dirty) { e.preventDefault(); e.returnValue = ""; }
        });

        var mq = window.matchMedia("(prefers-color-scheme: dark)");
        function syncTheme() {
          vuetify.theme.global.name.value = mq.matches ? "saltyDark" : "saltyLight";
        }
        mq.addEventListener("change", syncTheme);
        syncTheme();

        await this.loadList();
      },

      template: TEMPLATE
    });
  }

  /* --------------------------------------------------------------- boot --- */

  async function boot() {
    // The markup lives in its own static file rather than in the Mustache page: Mustache and Vue both
    // use {{ }}, so a Vue template inside a .mustache template would be mangled before the browser
    // ever saw it. Fetching it keeps the two templating languages entirely separate.
    var results = await Promise.all([
      fetch("/static/app/editor.html", { credentials: "same-origin" }).then(function (r) { return r.text(); }),
      fetch("/static/vendor/mdi-paths.json", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .catch(function () { return {}; })   // icons degrade to blank glyphs; the UI still works
    ]);
    TEMPLATE = results[0];
    ICONS = results[1];
    var vuetify = makeVuetify();
    buildApp(vuetify).use(vuetify).mount("#app");
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
