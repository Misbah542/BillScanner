package com.snaptab.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = PaperRaised,
    primaryContainer = TealTint,
    onPrimaryContainer = TealDeep,

    secondary = Clay,
    onSecondary = PaperRaised,
    secondaryContainer = ClayTint,
    onSecondaryContainer = ClayDeep,

    tertiary = Amber,
    onTertiary = PaperRaised,
    tertiaryContainer = AmberTint,
    onTertiaryContainer = Amber,

    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = InkSecondary,

    outline = LineStrong,
    outlineVariant = Line,

    // "You owe" is a state, not a failure, so error is the same clay rather than a
    // shouting red that would make every unsettled tab look like a problem.
    error = Clay,
    onError = PaperRaised,
    errorContainer = ClayTint,
    onErrorContainer = ClayDeep,

    inverseSurface = Ink,
    inverseOnSurface = Paper
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color0A,
    primaryContainer = TealTintDark,
    onPrimaryContainer = TealLight,

    secondary = ClayLight,
    onSecondary = Color0A,
    secondaryContainer = ClayTintDark,
    onSecondaryContainer = ClayLight,

    tertiary = AmberLight,
    onTertiary = Color0A,
    tertiaryContainer = AmberTintDark,
    onTertiaryContainer = AmberLight,

    background = PaperDark,
    onBackground = InkDark,
    surface = PaperRaisedDark,
    onSurface = InkDark,
    surfaceVariant = PaperSunkenDark,
    onSurfaceVariant = InkSecondaryDark,

    outline = LineStrongDark,
    outlineVariant = LineDark,

    error = ClayLight,
    onError = Color0A,
    errorContainer = ClayTintDark,
    onErrorContainer = ClayLight,

    // Not Paper. An inverted panel in dark mode is the brightest thing on the screen by
    // definition, and at pure Paper it was a 16.8:1 slab — the single loudest surface in
    // the app. The dimmed ink is bright enough to still read as inverted.
    inverseSurface = InkDark,
    inverseOnSurface = PaperDark
)

private val SnapTabShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

/**
 * The app theme.
 *
 * Deliberately NOT dynamic colour. The old theme defined a custom palette and then
 * defaulted `dynamicColor = true`, so on Android 12 and up the wallpaper replaced the
 * lot and none of the design was ever seen. SnapTab's teal and clay carry meaning —
 * teal is money coming to you, clay is money you owe — and a wallpaper-derived palette
 * would break that.
 */
@Composable
fun SnapTabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge draws behind transparent bars, so the only thing to set is
            // whether the bar icons should be dark. The old code set window.statusBarColor
            // (deprecated, and a no-op with edge-to-edge) and had this flag inverted:
            // `isAppearanceLightStatusBars = darkTheme` gave dark icons on a dark bar.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SnapTabTypography,
        shapes = SnapTabShapes,
        content = content
    )
}
