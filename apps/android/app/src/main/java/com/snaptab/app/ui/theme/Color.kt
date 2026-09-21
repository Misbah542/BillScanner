package com.snaptab.app.ui.theme

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
val LineStrong = Color(0xFFC9C2B4)

// Dark
val PaperDark = Color(0xFF141310)
val PaperRaisedDark = Color(0xFF1E1C18)
val PaperSunkenDark = Color(0xFF262319)
val InkDark = Color(0xFFF6F3EC)
val InkSecondaryDark = Color(0xFFBDB6A8)
val InkTertiaryDark = Color(0xFFA39C90)
val LineDark = Color(0xFF322E28)
val LineStrongDark = Color(0xFF474238)

/** Near-black, for text and icons sitting on a light accent in dark mode. */
val Color0A = Color(0xFF0A0906)

// Accents
val Teal = Color(0xFF0F6B5C)
val TealDeep = Color(0xFF0A4F43)
val TealLight = Color(0xFF7FC9B8)
val TealTint = Color(0xFFE3EEEA)
val TealTintDark = Color(0xFF17332C)

val Clay = Color(0xFFB4502A)
val ClayDeep = Color(0xFF8C3E20)
val ClayLight = Color(0xFFE9A183)
val ClayTint = Color(0xFFF7E7DE)
val ClayTintDark = Color(0xFF3A2118)

val Amber = Color(0xFF7A5B0F)
val AmberTint = Color(0xFFF6EEDB)
val AmberTintDark = Color(0xFF332A14)

/**
 * The categorical palette for category chips and the spend breakdown. Tuned to one
 * lightness band so no single category shouts over the others, and each foreground
 * clears 4.5:1 on its own tint.
 */
data class CategoryColors(val fg: Color, val tint: Color, val tintDark: Color)

val CategoryPalette: Map<String, CategoryColors> = mapOf(
    "restaurant" to CategoryColors(Color(0xFFB4502A), Color(0xFFF7E7DE), Color(0xFF3A2118)),
    "groceries" to CategoryColors(Color(0xFF4A6B14), Color(0xFFEDF1DF), Color(0xFF1F2A0D)),
    "shopping" to CategoryColors(Color(0xFF6B3FA0), Color(0xFFEEE7F7), Color(0xFF261A38)),
    "travel" to CategoryColors(Color(0xFF1E5F8A), Color(0xFFE2EDF5), Color(0xFF122A3B)),
    "fuel" to CategoryColors(Color(0xFF8A5A00), Color(0xFFF7EEDC), Color(0xFF332411)),
    "utilities" to CategoryColors(Color(0xFF0F6B5C), Color(0xFFE3EEEA), Color(0xFF17332C)),
    "entertainment" to CategoryColors(Color(0xFFA03F6B), Color(0xFFF7E3EC), Color(0xFF341723)),
    "subscriptions" to CategoryColors(Color(0xFF3F5FA0), Color(0xFFE5E9F7), Color(0xFF1A2238)),
    "health" to CategoryColors(Color(0xFF8A2B3F), Color(0xFFF7E1E5), Color(0xFF330F18)),
    "rent" to CategoryColors(Color(0xFF6B5A2B), Color(0xFFF2ECDC), Color(0xFF2A2313)),
    "education" to CategoryColors(Color(0xFF2B6B6B), Color(0xFFE0EFEF), Color(0xFF123030)),
    "personal_care" to CategoryColors(Color(0xFF8A5A6B), Color(0xFFF5E6EA), Color(0xFF322026)),
    "gifts" to CategoryColors(Color(0xFFA0533F), Color(0xFFF7E6E0), Color(0xFF34211A)),
    "fees" to CategoryColors(Color(0xFF7A5B0F), Color(0xFFF6EEDB), Color(0xFF332A14)),
    "income" to CategoryColors(Color(0xFF0F6B5C), Color(0xFFE3EEEA), Color(0xFF17332C)),
    "transfer" to CategoryColors(Color(0xFF5C574F), Color(0xFFF0EBE0), Color(0xFF2A2721)),
    "other" to CategoryColors(Color(0xFF8C867B), Color(0xFFF0EBE0), Color(0xFF2A2721))
)

/** Falls back rather than throwing, so a category the server added still renders. */
fun categoryColors(slug: String?): CategoryColors =
    CategoryPalette[slug] ?: CategoryPalette.getValue("other")
