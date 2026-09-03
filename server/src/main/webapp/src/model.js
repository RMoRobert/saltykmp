/*
 * Recipe data rules that are not React's business: ids, wire timestamps, ingredient scaling and
 * the sort comparators. Ported from the Alpine app rather than reinvented, because every one of
 * these has to agree with what the Swift and Compose clients do to the same rows.
 */

/* ------------------------------------------------------------------------ ids -- */

let _seq = 0;
let _lastMs = 0;

/**
 * UUIDv7, minted client-side.
 *
 * The server does not assign ids: they sort by creation time, and the moment a recipe is created is
 * here, in the browser, not whenever the save happens to land. The 12-bit sequence counter keeps
 * ids created in the same millisecond ordered among themselves.
 */
export function uuidv7() {
  const ms = Date.now();
  if (ms === _lastMs) _seq = (_seq + 1) & 0x0fff;
  else {
    _seq = 0;
    _lastMs = ms;
  }
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[0] = Math.floor(ms / 2 ** 40) & 0xff;
  b[1] = Math.floor(ms / 2 ** 32) & 0xff;
  b[2] = (ms >>> 24) & 0xff;
  b[3] = (ms >>> 16) & 0xff;
  b[4] = (ms >>> 8) & 0xff;
  b[5] = ms & 0xff;
  b[6] = 0x70 | ((_seq >> 8) & 0x0f);
  b[7] = _seq & 0xff;
  b[8] = 0x80 | (b[8] & 0x3f);
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("").toUpperCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** The wire format the server expects: yyyy-MM-dd'T'HH:mm:ss.SSS'Z' — exactly toISOString(). */
export const wireNow = () => new Date().toISOString();

export const newRow = (text = "", extra = {}) => ({ id: uuidv7(), text, ...extra });

/* -------------------------------------------------------------------- scaling -- */

export const SCALES = [0.5, 1, 1.5, 2, 3, 4];

const FRACTIONS = [
  [1, 8, "1/8"], [1, 4, "1/4"], [1, 3, "1/3"], [3, 8, "3/8"], [1, 2, "1/2"],
  [5, 8, "5/8"], [2, 3, "2/3"], [3, 4, "3/4"], [7, 8, "7/8"],
];

/** A scaled quantity as a cook would write it: "1 1/2", not "1.5", and never "0.30000000000004". */
function formatAmount(v) {
  if (!isFinite(v) || v <= 0) return null;
  const whole = Math.floor(v + 1e-9);
  const frac = v - whole;
  if (frac < 0.02) return String(whole);
  let best = null;
  let bestErr = 1;
  for (const [n, d, label] of FRACTIONS) {
    const err = Math.abs(frac - n / d);
    if (err < bestErr) {
      bestErr = err;
      best = label;
    }
  }
  // Too far from any friendly fraction to pretend: two decimals is more honest than a wrong 1/3.
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
  return { value: val, rest: String(text).slice(m[0].length) };
}

/**
 * An ingredient line at a scale factor, split so the caller can style the changed part.
 * Returns { amount, rest } with amount null when there is no leading quantity to scale --
 * "salt to taste" doubles to "salt to taste".
 */
export function scaleLine(text, factor) {
  if (factor === 1) return { amount: null, rest: text || "" };
  const p = parseLeadingAmount(text || "");
  if (!p) return { amount: null, rest: text || "" };
  const out = formatAmount(p.value * factor);
  if (out === null) return { amount: null, rest: text || "" };
  return { amount: out, rest: p.rest };
}

/* ------------------------------------------------------------------ ordering -- */

/**
 * The sort menu, in menu order. The labels are the Compose and Swift apps' own, so the same
 * ordering goes by the same name whichever client you opened. Direction is a separate choice
 * because "Ascending" on a date is not self-evidently oldest-first.
 */
export const SORT_OPTIONS = [
  { key: "name", label: "Name", asc: "A → Z", desc: "Z → A" },
  { key: "modified", label: "Date modified", asc: "Oldest first", desc: "Newest first" },
  { key: "created", label: "Date created", asc: "Oldest first", desc: "Newest first" },
  { key: "prepared", label: "Last made", asc: "Oldest first", desc: "Newest first" },
];

const byText = (a, b) =>
  (a || "").localeCompare(b || "", undefined, { sensitivity: "base", numeric: true });
const byDate = (a, b) => String(a || "").localeCompare(String(b || ""));

const COMPARATORS = {
  name: (a, b) => byText(a.name, b.name),
  modified: (a, b) => byDate(a.lastModifiedDate, b.lastModifiedDate),
  created: (a, b) => byDate(a.createdDate, b.createdDate),
  prepared: (a, b) => byDate(a.lastPrepared, b.lastPrepared),
};

export const everMade = (r) => !!r.lastPrepared;

/** Whether a recipe belongs to a library filter, ignoring the search box. */
export function matchesFilter(r, f) {
  if (f.kind === "favorites") return !!r.isFavorite;
  if (f.kind === "wantToMake") return !!r.wantToMake;
  if (f.kind === "course") return r.courseId === f.id;
  if (f.kind === "category") return (r.categoryIds || []).includes(f.id);
  if (f.kind === "tag") return (r.tagIds || []).includes(f.id);
  return true;
}

/** The middle pane: library filter first, then the search box on top of it, then the sort. */
export function visibleRecipes(list, filter, query, sortBy, sortAsc) {
  let rows = list.filter((r) => matchesFilter(r, filter));
  const q = query.trim().toLowerCase();
  if (q) rows = rows.filter((r) => (r.name || "").toLowerCase().includes(q));
  const cmp = COMPARATORS[sortBy] || COMPARATORS.name;
  rows = [...rows].sort(sortAsc ? cmp : (a, b) => -cmp(a, b));
  // Never-made recipes go LAST in both directions, as in the Compose and Swift apps: ascending
  // would otherwise open with every recipe that has no date at all, which is noise for a sort
  // that exists to answer "what have I cooked lately".
  if (sortBy === "prepared") {
    return rows.filter(everMade).concat(rows.filter((r) => !everMade(r)));
  }
  return rows;
}

/* -------------------------------------------------------------------- display -- */

export const DIFFICULTIES = [
  { value: 0, label: "(not set)" },
  { value: 1, label: "Easy" },
  { value: 2, label: "Somewhat Easy" },
  { value: 3, label: "Medium" },
  { value: 4, label: "Slightly Difficult" },
  { value: 5, label: "Difficult" },
];

export const difficultyLabel = (n) =>
  DIFFICULTIES.find((d) => d.value === n && d.value !== 0)?.label ?? null;

/**
 * How dense the recipe list is, in the order Settings offers them, loosest first.
 *
 * The first two keys and labels are the Swift app's own `RecipeListViewStyle` -- `summary` and
 * `smallIcons` -- so a reader who has set this on the Mac meets the same two words here. `list` has
 * no Swift counterpart; the name is Explorer's, for the view it behaves like: one line per recipe,
 * a small icon, and nothing else competing with the name.
 */
export const LIST_STYLES = [
  {
    key: "summary",
    label: "Summary",
    hint: "A large thumbnail with the recipe name, summary, and star rating",
  },
  {
    key: "smallIcons",
    label: "Small icons",
    hint: "A more compact summary view with smaller icons and less spacing",
  },
  {
    key: "list",
    label: "List",
    hint: "Small recipe icon and name only",
  },
];

/**
 * A stored style, or the default.
 *
 * localStorage outlives the build that wrote it, so an unknown key here is an ordinary thing rather
 * than a bug: without this guard it would fall through every branch in the row and render one with
 * no size at all.
 */
export const listStyleKey = (v) =>
  LIST_STYLES.some((s) => s.key === v) ? v : LIST_STYLES[0].key;

/**
 * The row's second line.
 *
 * Sorting by "Last Made" swaps it for the date being sorted on, as the Compose app does and for the
 * same reason: otherwise that ordering has no visible explanation, and the block of never-made
 * recipes at the end reads as a bug rather than as the point.
 */
export function rowSubtitle(r, sortBy) {
  if (sortBy === "prepared") {
    return everMade(r) ? `Made ${relativeDate(r.lastPrepared)}` : "Never made";
  }
  return (r.introduction || r.source || r.sourceDetails || "").trim();
}

/**
 * Nutrition, or nothing.
 *
 * An emptied record must not be stored: leaving an object of nulls behind would mean a recipe that
 * once had nutrition can never go back to having none, and the reading view would keep a heading
 * over an empty table. Anything actually set -- including a deliberate zero -- keeps the record.
 */
export function cleanNutrition(n) {
  if (!n) return null;
  const hasValue = Object.entries(n).some(
    ([k, v]) => k !== "id" && v !== null && v !== undefined && v !== "",
  );
  return hasValue ? n : null;
}

/** Section headings are list items too, so a direction's number is not its index. */
export function stepNumbers(directions) {
  let n = 0;
  return directions.map((d) => (d.isHeading ? null : ++n));
}

/** The first http(s) URL in a source line, so it can be offered as a link. */
export function sourceLink(r) {
  const text = [r.source, r.sourceDetails].filter(Boolean).join(" ");
  return text.match(/https?:\/\/\S+/)?.[0] ?? null;
}

export const NUTRITION_GROUPS = [
  {
    group: "General",
    fields: [
      { key: "servingSize", label: "Serving Size", text: true, placeholder: "e.g., 1 cup, 2 slices" },
      { key: "calories", label: "Calories" },
    ],
  },
  {
    group: "Macronutrients",
    fields: [
      { key: "protein", label: "Protein", unit: "g" },
      { key: "carbohydrates", label: "Carbohydrates", unit: "g" },
      { key: "fat", label: "Total Fat", unit: "g" },
      { key: "saturatedFat", label: "Saturated Fat", unit: "g" },
      { key: "transFat", label: "Trans Fat", unit: "g" },
      { key: "fiber", label: "Fiber", unit: "g" },
      { key: "sugar", label: "Sugar", unit: "g" },
      { key: "addedSugar", label: "Added Sugar", unit: "g" },
    ],
  },
  {
    group: "Other Nutrients",
    fields: [
      { key: "sodium", label: "Sodium", unit: "mg" },
      { key: "cholesterol", label: "Cholesterol", unit: "mg" },
    ],
  },
  {
    group: "Vitamins and Minerals",
    fields: [
      { key: "vitaminD", label: "Vitamin D", unit: "μg" },
      { key: "calcium", label: "Calcium", unit: "mg" },
      { key: "iron", label: "Iron", unit: "mg" },
      { key: "potassium", label: "Potassium", unit: "mg" },
      { key: "vitaminA", label: "Vitamin A", unit: "μg" },
      { key: "vitaminC", label: "Vitamin C", unit: "mg" },
    ],
  },
];

/**
 * "3 days ago", for dates the reader only needs to place roughly. Exact stamps are for the API;
 * a device list wants to answer "recently?" at a glance.
 */
export function relativeDate(iso) {
  const then = Date.parse(iso || "");
  if (!Number.isFinite(then)) return "";
  const days = Math.round((Date.now() - then) / 86_400_000);
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 30) return `${days} days ago`;
  const months = Math.round(days / 30);
  if (months < 12) return `${months} month${months === 1 ? "" : "s"} ago`;
  const years = Math.round(days / 365);
  return `${years} year${years === 1 ? "" : "s"} ago`;
}

const DAY_PARTS = { year: "numeric", month: "short", day: "numeric" };

/** "Aug 20, 2026", in the reader's own zone and locale. Null when there is no usable date. */
export function formatDay(iso) {
  const t = Date.parse(iso || "");
  return Number.isFinite(t) ? new Date(t).toLocaleDateString(undefined, DAY_PARTS) : null;
}

/** "Aug 20, 2026, 9:14 AM" -- for the stamps that really are moments rather than calendar days. */
export function formatMoment(iso) {
  const t = Date.parse(iso || "");
  return Number.isFinite(t)
    ? new Date(t).toLocaleString(undefined, { ...DAY_PARTS, hour: "numeric", minute: "2-digit" })
    : null;
}

/* ----------------------------------------------------------------- last made -- */

/*
 * "Last made on" is a calendar DAY, but the column holding it is a UTC timestamp that every client
 * renders in local time. So a picked day is stored at LOCAL NOON: local midnight would render as
 * the PREVIOUS day for anyone west of UTC, while noon stays on the right day across every real
 * offset (UTC-12…UTC+14) and DST shift.
 *
 * This is not a web rule. `PreparedDates` in `shared` and the Swift app's `localNoon(on:)` do the
 * same thing to the same column, so these two functions have to keep agreeing with them.
 */

const pad = (n) => String(n).padStart(2, "0");
const dayValue = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/** Today as an `<input type="date">` value -- the cap on a field that cannot accept the future. */
export const todayValue = () => dayValue(new Date());

/** A stored "last made" stamp as the local day to seed a date field with, or "" for never made. */
export function preparedToDayValue(iso) {
  const t = Date.parse(iso || "");
  return Number.isFinite(t) ? dayValue(new Date(t)) : "";
}

/** The inverse: the wire timestamp to store for a picked day. Null when the field is empty. */
export function dayValueToPrepared(value) {
  const [y, m, d] = String(value || "").split("-").map(Number);
  if (!y || !m || !d) return null;
  const noon = new Date(y, m - 1, d, 12, 0, 0, 0);
  // The two-digit-year rule: `new Date(1, ...)` means 1901, so a year under 100 -- which a date
  // field will happily accept -- would be stored as a date nobody picked. Setting it again after
  // construction means what it says.
  noon.setFullYear(y);
  // toISOString always emits milliseconds, which is exactly the wire format's `.SSS`.
  return noon.toISOString();
}
