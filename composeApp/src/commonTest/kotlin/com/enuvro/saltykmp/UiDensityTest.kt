package com.enuvro.saltykmp

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enuvro.saltykmp.di.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Named apart from SettingsSecretsTest's equivalent: two file-private classes of one name in one package clash. */
private class FakeDensityStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()
    override fun getString(key: String, default: String): String = values[key] ?: default
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class UiDensityTest {

    @Test
    fun density_defaults_to_the_platform_when_unset() {
        assertEquals(platformDefaultDensity, SettingsState(FakeDensityStore()).uiDensity)
    }

    @Test
    fun a_stored_choice_wins_over_the_platform_default() {
        val settings = SettingsState(FakeDensityStore())
        settings.uiDensity = UiDensity.Comfortable
        assertEquals(UiDensity.Comfortable, settings.uiDensity)
        settings.uiDensity = UiDensity.Compact
        assertEquals(UiDensity.Compact, settings.uiDensity)
    }

    /** A renamed or removed enum entry must not strand the app on an unreadable setting. */
    @Test
    fun an_unrecognised_stored_value_falls_back_to_the_platform_default() {
        val settings = SettingsState(FakeDensityStore(mapOf("uiDensity" to "Roomy")))
        assertEquals(platformDefaultDensity, settings.uiDensity)
    }

    @Test
    fun compact_is_tighter_than_comfortable_but_stays_above_the_wcag_target_floor() {
        val compact = UiDensity.Compact.metrics
        val comfortable = UiDensity.Comfortable.metrics
        assertTrue(compact.minInteractiveSize < comfortable.minInteractiveSize)
        // WCAG 2.2 SC 2.5.8 asks for 24x24; going under it would be a real regression, not a tighter UI.
        assertTrue(compact.minInteractiveSize.value >= 24f, "compact target below the WCAG floor")
        // The sidebar row: M3 hardcodes 56dp and Comfortable restates it, so that path stays a no-op.
        assertEquals(56f, comfortable.drawerRowHeight.value)
        assertTrue(compact.drawerRowHeight < comfortable.drawerRowHeight)
        // Must still clear a 24dp icon plus padding, or the row clips its own content.
        assertTrue(compact.drawerRowHeight.value >= 32f, "drawer row too short for a 24dp icon")
        assertTrue(compact.headingScale < 1f)
        assertEquals(1f, comfortable.headingScale)
        assertTrue(compact.layoutScale < 1f)
        assertEquals(1f, comfortable.layoutScale)
    }

    /**
     * The point of the whole scheme: layout tightens, type does not move. If someone ever "simplifies"
     * [scaledBy] by dropping the reciprocal on fontScale, every label in the app silently shrinks.
     */
    @Test
    fun scaling_shrinks_dp_but_leaves_sp_rendering_identical() {
        val base = Density(density = 2f, fontScale = 1f)
        val scaled = base.scaledBy(0.92f)

        val basePx = with(base) { 56.dp.toPx() }
        val scaledPx = with(scaled) { 56.dp.toPx() }
        assertEquals(basePx * 0.92f, scaledPx, absoluteTolerance = 0.01f)

        val baseSp = with(base) { 16.sp.toPx() }
        val scaledSp = with(scaled) { 16.sp.toPx() }
        assertEquals(baseSp, scaledSp, absoluteTolerance = 0.01f)
    }

    /** An unscaled density is handed back untouched rather than rebuilt with float drift. */
    @Test
    fun an_unscaled_density_is_returned_untouched() {
        val base = Density(density = 2f, fontScale = 1.3f)
        assertSame(base, base.scaledBy(1f))
    }

    /** A non-default OS font scale has to survive the round trip, or accessibility settings get eaten. */
    @Test
    fun scaling_preserves_a_user_font_scale() {
        val base = Density(density = 2f, fontScale = 1.5f)
        val scaled = base.scaledBy(0.92f)
        assertEquals(with(base) { 16.sp.toPx() }, with(scaled) { 16.sp.toPx() }, absoluteTolerance = 0.01f)
    }

    @Test
    fun scaling_shrinks_headings_and_leaves_body_and_label_alone() {
        val base = Typography()
        val scaled = base.scaleHeadings(0.90f)

        assertEquals(base.titleLarge.fontSize.value * 0.90f, scaled.titleLarge.fontSize.value)
        assertEquals(base.headlineSmall.lineHeight.value * 0.90f, scaled.headlineSmall.lineHeight.value)
        assertNotEquals(base.displayLarge.fontSize, scaled.displayLarge.fontSize)

        assertEquals(base.titleMedium.fontSize, scaled.titleMedium.fontSize)
        assertEquals(base.bodyLarge.fontSize, scaled.bodyLarge.fontSize)
        assertEquals(base.bodySmall.fontSize, scaled.bodySmall.fontSize)
        assertEquals(base.labelSmall.fontSize, scaled.labelSmall.fontSize)
    }

    /** Scaled titleLarge (19.8sp) must still outrank titleMedium (16sp), or the hierarchy inverts. */
    @Test
    fun the_scaled_ramp_stays_monotonic() {
        val t = Typography().scaleHeadings(UiDensity.Compact.metrics.headingScale)
        assertTrue(t.displaySmall.fontSize.value > t.headlineLarge.fontSize.value)
        assertTrue(t.headlineLarge.fontSize.value > t.headlineMedium.fontSize.value)
        assertTrue(t.headlineMedium.fontSize.value > t.headlineSmall.fontSize.value)
        assertTrue(t.headlineSmall.fontSize.value > t.titleLarge.fontSize.value)
        assertTrue(t.titleLarge.fontSize.value > t.titleMedium.fontSize.value)
    }

    @Test
    fun an_unscaled_ramp_is_returned_untouched() {
        val base = Typography()
        assertSame(base, base.scaleHeadings(1f))
    }
}
