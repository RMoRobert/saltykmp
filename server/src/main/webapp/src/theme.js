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

/*
 * The body type is left alone deliberately.
 *
 * Fluent's `fontFamilyBase` is already a per-platform stack --
 *   'Segoe UI', 'Segoe UI Web (West European)', -apple-system, BlinkMacSystemFont, Roboto, ...
 * -- so Windows gets Segoe UI, macOS falls through to -apple-system (San Francisco), Android to
 * Roboto. That is the recommended behaviour rather than a gap to fill: pinning Segoe everywhere
 * would ship a webfont to make a Mac look like Windows, and pinning a single neutral face would
 * throw away the platform matching Fluent already does. Segoe is not ours to ship in any case --
 * it is licensed for use in Microsoft's own products, not for self-hosting, which is why Fluent
 * ships a stack instead of a webfont.
 *
 * The monospace stack is the one place that needs help. Fluent's is
 *   Consolas, 'Courier New', Courier, monospace
 * and Consolas ships only with Windows, so macOS and Linux both fall through to Courier New for
 * the one thing that uses it (the recipe id in RecipeInfoDialog). This is the same per-platform
 * idea as `fontFamilyBase`, applied to monospace: each platform's own UI mono first, Fluent's
 * stack untouched behind it.
 */
const fontFamilyMonospace =
  "ui-monospace, 'SF Mono', SFMono-Regular, Menlo, 'DejaVu Sans Mono', " +
  "Consolas, 'Courier New', Courier, monospace";

/*
 * The selection tint is ours too.
 *
 * Fluent's `colorBrandBackground2` is brand160 in light and brand20 in dark, and both sit so
 * close to their surface that the selected row barely reads: 1.12:1 against white, 1.09:1
 * against the dark theme's #292929. This is a custom key rather than an override of
 * `colorBrandBackground2` because that token also paints tint Badges, Tags, RatingItem and the
 * selected circular Tab; only the row selection is too faint.
 *
 * FluentProvider emits every theme key as a CSS variable, so a custom key is read as
 * `var(--colorSaltySelectedBackground)` -- there is no `tokens.` entry for a name Fluent's own
 * token map does not declare.
 *
 * Light lands on brand150 because it is the darkest step on the ramp that keeps the whole row at
 * WCAG AA: the rows' secondary text is `colorNeutralForeground3` (#616161), which is 4.85:1 on
 * brand150 but 4.32:1 on brand140. Dark lands on brand40, 1.32:1 against the surface with
 * #ADADAD still at 4.93:1. Going darker in light means also lifting that secondary text to
 * `colorNeutralForeground2`.
 */
export const saltyLightTheme = {
  ...createLightTheme(saltyBrand),
  fontFamilyMonospace,
  colorSaltySelectedBackground: saltyBrand[150],
};

export const saltyDarkTheme = {
  ...createDarkTheme(saltyBrand),
  fontFamilyMonospace,
  colorSaltySelectedBackground: saltyBrand[40],
};
