import { createDarkTheme, createLightTheme } from "@fluentui/react-components";

/*
 * Salty's brand ramp.
 *
 * This is Fluent's own way to brand an app -- `createLightTheme`/`createDarkTheme` take a sixteen
 * step brand ramp and derive every brand token from it, so nothing here overrides a component or
 * fights the design system. The neutrals, spacing, shape and type are all still Fluent's.
 *
 * WHY THE RAMP IS NOT SIMPLY SALTY'S BLUE. The app's blue is #0097F5, and white text on it is
 * 3.11:1 -- under WCAG AA's 4.5:1 for body text (Fluent's own #0f6cbd is 5.38:1). Fluent puts
 * brand80 behind primary buttons in light mode and brand70 in dark, both with white text, so
 * dropping #0097F5 into slot 80 would have shipped a button whose label fails at the default size.
 *
 * So the ramp keeps the brand's hue (203°) and full saturation and follows Fluent's own lightness
 * curve, shifted so that slot 80 lands on the lightest shade of that hue which still carries white
 * at 4.5:1. Salty's blue itself comes out at slot 90 (#039EFF, a shade off #0097F5), which is where
 * Fluent uses it: accents, hover states and brand text on dark backgrounds.
 *
 * Checked against the pairs Fluent actually builds from this:
 *
 *   white on brand80 (light primary button)     4.51:1
 *   white on brand70 (dark primary button)      5.58:1
 *   brand80 on white (light brand text/link)    4.51:1
 *   brand100 on #292929 (dark brand text)       6.22:1
 *   body text on brand160 (selected row tint)  13.86:1
 *
 * If the ramp is ever regenerated, those five are the ones to re-check.
 */
const saltyBrand = {
  10: "#001725",
  20: "#00253B",
  30: "#00314F",
  40: "#003F65",
  50: "#004E7E",
  60: "#005D96",
  70: "#006CAF",
  80: "#007BC7",
  90: "#039EFF",
  100: "#3BB2FD",
  110: "#57BCFC",
  120: "#6EC5FC",
  130: "#8FD2FC",
  140: "#AEDEFB",
  150: "#CAE8FB",
  160: "#E7F4FC",
};

export const saltyLightTheme = createLightTheme(saltyBrand);
export const saltyDarkTheme = createDarkTheme(saltyBrand);

/*
 * The type is left alone deliberately.
 *
 * Fluent's `fontFamilyBase` is already a per-platform stack --
 *   'Segoe UI', 'Segoe UI Web (West European)', -apple-system, BlinkMacSystemFont, Roboto, ...
 * -- so Windows gets Segoe UI, macOS falls through to -apple-system (San Francisco), Android to
 * Roboto. That is the recommended behaviour rather than a gap to fill: pinning Segoe everywhere
 * would ship a webfont to make a Mac look like Windows, and pinning a single neutral face would
 * throw away the platform matching Fluent already does.
 */
