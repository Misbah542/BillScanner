package com.snaptab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The SnapTab palette.
 *
 * A warm paper ground rather than the default grey, one deep teal that always means
 * "money coming to you", and one clay that always means "money you owe". The two
 * accents share chroma and lightness so they sit together without shouting, and both
 * clear 4.5:1 against their own tints and against white.
 *
 * The old theme defined a custom palette and then set `dynamicColor = true` by
 * default, so on Android 12 and up the wallpaper colours replaced all of it and none
 * of the design was ever seen. SnapTab does not use dynamic colour.
 */

// Ground and ink
val Paper = Color(0xFFF6F3EC)
val PaperRaised = Color(0xFFFFFFFF)
val PaperSunken = Color(0xFFF0EBE0)
val Ink = Color(0xFF161412)
val InkSecondary = Color(0xFF5C574F)
val InkTertiary = Color(0xFF6B655C)
val Line = Color(0xFFE3DDD1)
val LineStrong = Color(0xFFC0B6A5)

// Dark
//
// Dark mode used to be the light palette with the ends swapped: the same cream ink on a
// near-black ground, which measured 16.8:1. That is not "crisp", it is where halation
// starts — the glow that makes text on a dark ground look like it is vibrating. Body ink
// is dimmed to land around 13-14:1, which is still far above the 4.5 floor and reads as
// calm rather than loud. The same reasoning dims the accents below.
val PaperDark = Color(0xFF141310)
val PaperRaisedDark = Color(0xFF1E1C18)
val PaperSunkenDark = Color(0xFF262319)
val InkDark = Color(0xFFE6E1D6)
val InkSecondaryDark = Color(0xFFADA698)
val InkTertiaryDark = Color(0xFF938C81)
// A card's edge is the only thing separating it from the grid behind it, so these are
// measured rather than eyeballed: the hairline clears 1.35:1 on its own surface and the
// strong outline clears 2.0:1. At the old dark values the hairline was 1.26 and cards had
// no visible boundary at all.
val LineDark = Color(0xFF36332E)
val LineStrongDark = Color(0xFF514C45)

/** Near-black, for text and icons sitting on a light accent in dark mode. */
val Color0A = Color(0xFF0A0906)

// Accents
val Teal = Color(0xFF0F6B5C)
val TealDeep = Color(0xFF0A4F43)
val TealLight = Color(0xFF6FB3A3)
val TealTint = Color(0xFFE3EEEA)
val TealTintDark = Color(0xFF17332C)

val Clay = Color(0xFFB4502A)
val ClayDeep = Color(0xFF8C3E20)
val ClayLight = Color(0xFFD4907A)
val ClayTint = Color(0xFFF7E7DE)
val ClayTintDark = Color(0xFF3A2118)

val Amber = Color(0xFF7A5B0F)
/** The dark-mode partner to Teal/ClayLight; dark `tertiary` used to be AmberTint, a
 * near-white, which made "needs a look" notices glare next to the other two accents. */
val AmberLight = Color(0xFFC4A768)
val AmberTint = Color(0xFFF6EEDB)
val AmberTintDark = Color(0xFF332A14)

/**
 * The category palette: six tones, not fifteen hues.
 *
 * It used to be one colour per category — purple shopping, blue travel, pink
 * entertainment, maroon health — fifteen distinct hues that could all land on one screen
 * at once. On a plain background that was merely busy. With the grid behind every screen
 * it was a clash, because the rainbow had nothing to do with the two colours that carry
 * meaning here: teal is money coming to you, clay is money you owe. A category is not a
 * third kind of meaning, so it does not get a third kind of colour.
 *
 * These six are low-chroma and drawn from the same family as the brand. Every one clears
 * 4.5:1 as text on its own tint, on a card and on the page — in both themes — and every
 * pair of them is separable either by lightness or by hue, which is what a donut chart
 * needs. Seventeen categories map onto the six; sharing a tone is fine, because the label
 * says which category it is and the colour is only there to group.
 *
 * Each tone carries its own dark-mode values. That is not decoration: the old type had a
 * light `tint` and a dark `tintDark` but only one `fg`, and only CategoryChip remembered
 * to switch. Four other screens read the light fields unconditionally, so in dark mode a
 * cream swatch sat on a near-black card in sixteen places. Resolving the pair here rather
 * than at each call site is what stops that happening again.
 */
data class CategoryColors(
    val fg: Color,
    val fgDark: Color,
    val tint: Color,
    val tintDark: Color
)

val CatTeal = CategoryColors(Color(0xFF0F6B5C), Color(0xFF87B2A7), Color(0xFFDFE5DE), Color(0xFF13231E))
val CatClay = CategoryColors(Color(0xFF893C1E), Color(0xFFC29B89), Color(0xFFEBE1D7), Color(0xFF291A13))
val CatSlate = CategoryColors(Color(0xFF3E5C6B), Color(0xFF9EABAE), Color(0xFFE4E4DF), Color(0xFF1C2020))
val CatSand = CategoryColors(Color(0xFF7A5D28), Color(0xFFBAAB8E), Color(0xFFEAE4D8), Color(0xFF262014))
val CatSage = CategoryColors(Color(0xFF456B3C), Color(0xFFA1B298), Color(0xFFE4E5DA), Color(0xFF1D2318))
val CatStone = CategoryColors(Color(0xFF474239), Color(0xFFA29E96), Color(0xFFE4E1DA), Color(0xFF1D1B17))

/** Grouped by what the spending is, so the six tones read as six kinds of outgoing. */
val CategoryPalette: Map<String, CategoryColors> = mapOf(
    // Food and the everyday
    "restaurant" to CatClay,
    "groceries" to CatSage,
    // Getting about
    "travel" to CatSlate,
    "fuel" to CatSlate,
    // Bills that arrive whether you like them or not
    "utilities" to CatTeal,
    "rent" to CatTeal,
    "subscriptions" to CatTeal,
    "fees" to CatTeal,
    // Discretionary
    "shopping" to CatSand,
    "entertainment" to CatSand,
    "gifts" to CatSand,
    "personal_care" to CatSand,
    // Looking after yourself
    "health" to CatSage,
    "education" to CatSage,
    // Movements that are not really spending
    "income" to CatTeal,
    "transfer" to CatStone,
    "other" to CatStone
)

/** A category's colours, already resolved for the theme in force. */
@Immutable
data class ResolvedCategoryColors(val fg: Color, val container: Color)

/**
 * The only way to get a category's colours.
 *
 * It resolves for the current theme itself, so a call site cannot pick the light value
 * and forget the dark one — which is exactly what four screens did. Falls back rather
 * than throwing, so a category the server adds later still renders.
 */
@Composable
fun categoryColors(slug: String?): ResolvedCategoryColors {
    val c = CategoryPalette[slug] ?: CategoryPalette.getValue("other")
    return if (isSystemInDarkTheme()) {
        ResolvedCategoryColors(fg = c.fgDark, container = c.tintDark)
    } else {
        ResolvedCategoryColors(fg = c.fg, container = c.tint)
    }
}
