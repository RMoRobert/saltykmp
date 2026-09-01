package com.enuvro.saltykmp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * How tightly the Material components pack themselves.
 *
 * Material 3's metrics are sized for thumbs: a 48dp minimum interactive target, 12–28dp corner radii, a
 * type ramp whose headings start at 22sp. That is right on a phone and wrong in a 1280×860 desktop
 * window, where it reads as a blown-up Android app rather than a desktop one.
 *
 * This is theming first: [Compact] mostly just changes the values [SaltyTheme] hands to `MaterialTheme`.
 * There is no second set of composables and no per-platform screen.
 *
 * It is not theming *only*, though, and it can't be. M3 hardcodes the heights of its chrome —
 * `NavigationDrawerItem` at 56dp, `ListItem` at 56/72dp, `TopAppBar` at 64dp — behind no theme input of
 * any kind, and chrome is most of what a desktop window's proportions actually are. See
 * [DensityMetrics.drawerRowHeight] for the one place that forces the issue, and why it's only one.
 */
internal enum class UiDensity {
    /** Stock Material metrics: what every platform used before compact existed, and what touch wants. */
    Comfortable,

    /** Tightened for a pointer: less dead space around controls, smaller radii, smaller headings. */
    Compact,
    ;

    /** The label the Settings toggle reads by; [Comfortable] is presented as "touch-friendly". */
    val isTouchFriendly: Boolean get() = this == Comfortable
}

/**
 * The density a platform gets when the user has not chosen one.
 *
 * To back the whole feature out, make the desktop actual return [UiDensity.Comfortable] — that is the
 * single-word undo, and it leaves the setting harmlessly in place for anyone who had already chosen.
 */
internal expect val platformDefaultDensity: UiDensity

/**
 * Whether to offer the density toggle in Settings at all.
 *
 * Desktop only. A phone or tablet running the touch build has no reason to be given a compact mode, but
 * a desktop machine might well be a Surface or another touchscreen laptop — that user is on the JVM
 * build, gets [UiDensity.Compact] by default, and needs a way back.
 */
internal expect val uiDensityChoiceSupported: Boolean

/**
 * The Material values a density resolves to. Everything here is a `MaterialTheme` input or a
 * CompositionLocal that stock M3 components already read, which is what keeps this a theme rather than
 * a fork.
 */
internal class DensityMetrics(
    /**
     * Minimum size of the invisible touch target M3 pads around small controls (checkboxes, switches,
     * radio buttons, icon buttons, text fields). The control's *visible* size does not change — this is
     * purely the dead space around it, which is why it is the single biggest desktop win for no layout
     * work at all.
     *
     * Compact stays at 32dp rather than dropping to the control's own size: WCAG 2.2 SC 2.5.8 asks for
     * 24×24 CSS px, and 32dp keeps a comfortable margin over that floor while still removing 16dp of
     * padding from every one of the ~70 such controls in the app.
     */
    val minInteractiveSize: Dp,
    /**
     * Height forced onto the drawer/sidebar rows.
     *
     * Unlike everything else here this is NOT something M3 reads from the theme: `NavigationDrawerItem`
     * hardcodes `heightIn(min = NavigationDrawerTokens.ActiveIndicatorHeight)` — 56dp, a thumb-sized row
     * — and no CompositionLocal reaches it. The caller's modifier is applied *before* that `heightIn`
     * though, so an exact `Modifier.height` upstream constrains it and wins.
     *
     * It earns the exception because the sidebar is the single most visually dominant thing in the
     * desktop window: a dozen 56dp rows are most of what "this looks like a phone app" actually means
     * here. [Comfortable] deliberately restates M3's own 56dp, so that path stays a no-op.
     */
    val drawerRowHeight: Dp,
    /**
     * Multiplier applied to `LocalDensity`, shrinking every dp-based measurement at once — text field
     * heights, paddings, icons, chrome — with no call site involved. The blunt instrument, and the only
     * one that reaches M3's own internal padding.
     *
     * Text is deliberately NOT scaled with it: `fontScale` is divided by the same factor in [SaltyTheme],
     * and since an sp renders at `sp x density x fontScale` the two cancel exactly. So layout tightens
     * and type stays pixel-identical, which is the whole point — this app's problem is padding, not
     * type size. (The heading ramp is handled separately by [headingScale], which still earns its keep.)
     *
     * Watch for: `widthClassFor` reads window width in Dp from a `BoxWithConstraints`, so at 0.92 the
     * same window reports ~9% more Dp and the layout breakpoints in Adaptive.kt shift down with it.
     */
    val layoutScale: Float,
    val shapes: Shapes,
    /**
     * Multiplier applied to the display/headline/titleLarge steps of the type ramp — the ones at 22sp
     * and above. Body and label styles are never scaled: shrinking 12sp helper text is a legibility
     * regression, not a density improvement, and leaving everything at or below 16sp alone also keeps
     * the ramp monotonic (scaled titleLarge is 19.8sp, still clear of titleMedium's 16sp).
     */
    val headingScale: Float,
)

/**
 * The active density, for the handful of places that need the value itself rather than a Material theme
 * input — see [DensityMetrics.drawerRowHeight] for why any such place exists at all.
 */
internal val LocalUiDensity = staticCompositionLocalOf { UiDensity.Comfortable }

internal val UiDensity.metrics: DensityMetrics
    get() = when (this) {
        UiDensity.Comfortable -> ComfortableMetrics
        UiDensity.Compact -> CompactMetrics
    }

/** Stock M3: 48dp targets, the default shape set, an unscaled ramp. */
private val ComfortableMetrics = DensityMetrics(
    minInteractiveSize = 48.dp,
    drawerRowHeight = 56.dp, // NavigationDrawerTokens.ActiveIndicatorHeight — restated, so this is a no-op
    layoutScale = 1f,
    shapes = Shapes(),
    headingScale = 1f,
)

private val CompactMetrics = DensityMetrics(
    minInteractiveSize = 32.dp,
    // A desktop sidebar row: an IntelliJ tree row is ~24dp and a macOS source-list row ~28-32dp, so 38dp
    // still errs roomy. Comfortably clears the 24dp icon plus its padding, so nothing clips.
    drawerRowHeight = 38.dp,
    // Everything below scales by this too, so the 38dp row above renders at ~35dp. Kept uniform on
    // purpose: a compensating fudge on one value would make both numbers hard to reason about later.
    // If the sidebar overshoots, 41.dp here restores exactly the pre-scaling row.
    layoutScale = 0.88f,
    // Against M3's 4/8/12/16/28. Desktop toolkits round corners far less; this is roughly two-thirds of
    // the way to square, which reads as "desktop" without going brutalist. Components reach these
    // through `Shapes.fromToken`, so cards, dialogs, menus, sheets and text fields all follow.
    //
    // Two things this cannot reach. Buttons resolve to `CornerFull` — a hard-coded CircleShape, not a
    // scale entry — so they stay pills whatever is set here. And the `largeIncreased`/`extraLarge-
    // Increased`/`extraExtraLarge` steps are only on the expressive constructor, so they keep stock
    // radii; nothing in this app reads them today (see [scaleHeadings] for the same trade).
    shapes = Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(20.dp),
    ),
    headingScale = 0.90f,
)

/**
 * This [Density] with every dp scaled by [factor], and type left rendering at exactly the same pixel
 * size — `fontScale` takes the reciprocal, and an sp resolves through `sp x density x fontScale`, so the
 * two cancel. See [DensityMetrics.layoutScale].
 */
internal fun Density.scaledBy(factor: Float): Density =
    if (factor == 1f) this else Density(density * factor, fontScale / factor)

/**
 * Scales the heading end of [Typography] by [factor], leaving title-medium and below untouched.
 *
 * Applied to a `Typography` rather than spelled out as absolute sizes so an M3 upgrade that retunes the
 * ramp carries through instead of being silently overridden.
 *
 * The parallel `Emphasized` ramp is deliberately left alone. Only M3's expressive components read those
 * tokens, this app uses none of them, and reaching them means opting in to
 * `ExperimentalMaterial3ExpressiveApi` — not a dependency worth taking on to scale text nothing renders.
 * If an expressive component is ever adopted here, its heading will need adding to this list.
 */
internal fun Typography.scaleHeadings(factor: Float): Typography {
    if (factor == 1f) return this
    return copy(
        displayLarge = displayLarge.scaled(factor),
        displayMedium = displayMedium.scaled(factor),
        displaySmall = displaySmall.scaled(factor),
        headlineLarge = headlineLarge.scaled(factor),
        headlineMedium = headlineMedium.scaled(factor),
        headlineSmall = headlineSmall.scaled(factor),
        titleLarge = titleLarge.scaled(factor),
    )
}

/** This style at [factor] times its size. Also how [ChefScreen] resolves its text-size stepper. */
internal fun TextStyle.scaled(factor: Float): TextStyle =
    copy(fontSize = fontSize.scaled(factor), lineHeight = lineHeight.scaled(factor))

/**
 * Unspecified stays unspecified, and a non-sp unit (em) is left alone — scaling a relative size by the
 * same factor as the font it is relative to would apply the factor twice.
 */
internal fun TextUnit.scaled(factor: Float): TextUnit =
    if (isSpecified && isSp) (value * factor).sp else this
