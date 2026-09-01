/*
 * Material Symbols for <wa-icon>.
 *
 * The app's icons used to be Font Awesome Free, which is what Web Awesome's "default" icon library
 * resolves to. They are now Google's Material Symbols, so the web UI reads as the same product as
 * the Compose app -- which draws Material icons everywhere -- instead of a lookalike built from a
 * different icon family.
 *
 * Nothing about the markup changed. Web Awesome resolves every <wa-icon name="..."> through a named
 * library, and registering one called "default" replaces the source for all of them at once. The
 * ALIASES table below maps the Font Awesome names still written in app.mustache to their Material
 * equivalents, so the swap is one file rather than 77 edited tags. Writing a Material name directly
 * also works -- see MATERIAL_NAMES below -- so tags can migrate a few at a time.
 *
 * Web Awesome's own internal icons (the dropdown caret, the dialog close button, a checkbox tick)
 * do NOT come through here. They live in a separate `system` library that ships inline in the
 * bundle, so overriding "default" cannot break a component's chrome.
 *
 * OUTLINED VS FILLED. Material Symbols publishes both, and <wa-icon variant> picks between them:
 *
 *     <wa-icon name="star">                    outlined (the default, matching the Compose app)
 *     <wa-icon name="star" variant="regular">  outlined
 *     <wa-icon name="star" variant="solid">    filled -- resolves star-fill.svg
 *
 * Note the flipped default: Font Awesome's unqualified name meant the *solid* glyph, Material's
 * means the outlined one. That is the intended change -- the Compose app is Icons.Outlined.* almost
 * everywhere -- but it is why an icon that looked heavy before looks lighter now.
 *
 * Unlike Font Awesome Free, which ships a `regular` cut for only a handful of icons, every Material
 * symbol has both. Any icon can be filled; none of them need a table of which ones are allowed to.
 *
 * SWITCHING TO SELF-HOSTED. The SVGs come from jsDelivr today, which is the same posture as the
 * rest of the page (Web Awesome and Alpine are CDN too). The whole set is 47 files / 19 KB raw, so
 * serving it ourselves is cheap if that ever matters -- an air-gapped install, a CDN outage, or
 * just not wanting a third party in the load path:
 *
 *   1. Vendor the files. No npm and no build step -- the names are the right-hand column of
 *      ALIASES plus the DIRECT list, which is what the two sed expressions below pick up. The
 *      `-fill` cuts are only needed for the icons used with variant="solid":
 *
 *        cd server/src/main/resources/static/app && mkdir -p icons && cd icons
 *        base=https://cdn.jsdelivr.net/npm/@material-symbols/svg-600@0.47.0/outlined  # match WEIGHT
 *        for n in $(sed -n 's/.*: "\([a-z_]*\)",$/\1/p; s/^  "\([a-z_]*\)",$/\1/p' ../icons.js); do
 *            curl -sfO "$base/$n.svg"
 *        done
 *        for n in favorite star bookmark check_circle; do curl -sfO "$base/$n-fill.svg"; done
 *
 *   2. Change BASE below to "/static/app/icons". Nothing else moves.
 *
 * Self-hosting also makes the set editable: the resolver returns a URL, so dropping a hand-drawn
 * or legacy-Material SVG in as <name>.svg overrides that one icon and leaves the rest alone.
 *
 * PINNED VERSIONS. The Web Awesome version here has to match the one app.mustache loads -- two
 * copies of the bundle would mean two icon registries, and the one this file writes to would not be
 * the one the components read. The import is `components/icon/library.js` rather than the top-level
 * `webawesome.js` because the barrel drags in twice the code (105 KB vs 50 KB) for the same
 * function, and every chunk this path touches is one the loader fetches anyway.
 */
import { registerIconLibrary }
  from "https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.12.0/dist-cdn/components/icon/library.js";

/**
 * Material Symbols' stroke weight, one of 100..700 -- the axis the old Material Icons set does not
 * have, and the reason this app does not need it. The family's default of 400 is visibly lighter
 * than the icons the Compose app draws, which is what made the first pass read as thin.
 *
 * 600 is not a taste setting. Rasterising each icon at 128px and counting opaque pixels gives the
 * ink a drawing actually lays down, which for an outline icon is a direct proxy for stroke weight.
 * Averaged over settings, search, edit, delete, more_vert, checklist, upload, tune, description and
 * logout: legacy 18.2%, wght 400 14.4%, wght 500 15.9%, wght 600 18.7%. So 600 is where Material
 * Symbols matches the weight the Compose app is already drawing, and 400 was a fifth lighter.
 *
 * The one glyph no weight reconciles is more_vert: legacy draws its three dots at 6.2% ink against
 * 2.8% at wght 600, because Symbols redrew them smaller. Fixing that one means overriding it with
 * a hand-supplied SVG, which is what self-hosting (above) makes possible.
 */
const WEIGHT = 600;

/** Where the outlined SVGs live. See "SWITCHING TO SELF-HOSTED" above. */
const BASE = `https://cdn.jsdelivr.net/npm/@material-symbols/svg-${WEIGHT}@0.47.0/outlined`;

/**
 * Font Awesome name -> Material Symbols name, for the names app.mustache and app.js still use.
 * Every one of these was checked to exist in both the outlined and filled cuts.
 */
const ALIASES = {
  "arrow-down-short-wide": "sort",
  "arrow-up-from-bracket": "upload",
  "book-open": "menu_book",
  "bookmark": "bookmark",
  "broom": "cleaning_services",
  // Material draws a triangle for arrow_drop_down and a chevron for keyboard_arrow_down; Font
  // Awesome's caret/chevron split means the same thing, so the pairing survives the move.
  "caret-down": "arrow_drop_down",
  "cart-shopping": "shopping_cart",
  "chevron-down": "keyboard_arrow_down",
  "chevron-left": "chevron_left",
  "chevron-up": "keyboard_arrow_up",
  "circle-check": "check_circle",
  "circle-exclamation": "error",
  "circle-info": "info",
  "circle-play": "play_circle",
  "circle-user": "account_circle",
  "ellipsis-vertical": "more_vert",
  "file-lines": "description",
  "folder": "folder",
  "gear": "settings",
  "grip-vertical": "drag_indicator",
  "heading": "title",
  "heart": "favorite",
  "image": "image",
  "key": "key",
  "link": "link",
  "list-check": "checklist",
  "magnifying-glass": "search",
  "minus": "remove",
  "pen": "edit",
  "plus": "add",
  "right-from-bracket": "logout",
  "sliders": "tune",
  "star": "star",
  "table-list": "list_alt",
  "tag": "sell",
  "trash": "delete",
  "triangle-exclamation": "warning",
  "user-gear": "manage_accounts",
  "user-minus": "person_remove",
  "user-shield": "admin_panel_settings",
  "users": "group",
  "utensils": "restaurant",
  "xmark": "close",
};

/**
 * Material names used directly, with no Font Awesome name behind them -- either because nothing in
 * Font Awesome meant the same thing, or because a tag has already been migrated off its alias.
 * The list is explicit rather than inferred so that a typo is still a warning and not a silent
 * request for an icon that does not exist.
 */
const DIRECT = [
  // What the Compose app draws for the Categories row (Icons.Outlined.Category). Font Awesome's
  // nearest was `folder`, which is a different idea wearing the same slot.
  "category",
];

/** Lets a tag write the Material name directly, so the aliases can be retired piecemeal. */
const MATERIAL_NAMES = new Set([...Object.values(ALIASES), ...DIRECT]);

const FONT_AWESOME_VERSION = "7.3.0";
const warned = new Set();

/**
 * Anything unmapped falls back to the Font Awesome icon it would have resolved to before, rather
 * than rendering nothing. A missing icon is invisible in a screenshot and in a test that only
 * checks the element exists, so a typo'd name would otherwise be a silent hole in the UI; this way
 * it is a visibly wrong icon plus a console warning, both of which get noticed.
 */
function fontAwesomeFallback(name, family, variant) {
  if (!warned.has(name)) {
    warned.add(name);
    console.warn(`[icons] "${name}" has no Material Symbols mapping; falling back to Font Awesome.`);
  }
  const folder = family === "brands" ? "brands"
    : ["thin", "light", "regular"].includes(variant) ? variant
    : "solid";
  return `https://ka-f.fontawesome.com/releases/v${FONT_AWESOME_VERSION}/svgs/${folder}/${name}.svg`;
}

registerIconLibrary("default", {
  resolver(name, family, variant) {
    const symbol = ALIASES[name] ?? (MATERIAL_NAMES.has(name) ? name : null);
    if (symbol === null) return fontAwesomeFallback(name, family, variant);
    // `variant` arrives undefined when the tag omits it -- Web Awesome only defaults it to "solid"
    // inside its own library's resolver, not before calling a custom one -- so outlined is what an
    // unqualified <wa-icon name="..."> gets.
    return `${BASE}/${symbol}${variant === "solid" ? "-fill" : ""}.svg`;
  },
  // Same as the stock library: without this the SVG keeps its own fill and stops following the
  // text colour, so an icon in a coloured button or a `.rrow__fav` heart renders black.
  mutator(svg) {
    if (!svg.hasAttribute("fill")) svg.setAttribute("fill", "currentColor");
  },
});
