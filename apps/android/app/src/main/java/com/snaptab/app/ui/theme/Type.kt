package com.snaptab.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.snaptab.app.R

/**
 * Two geometric sans faces, no serif anywhere.
 *
 * The previous scale used FontFamily.Serif for headings and amounts. A serif reads as
 * traditional — a newspaper, a bank statement — which is the opposite of what this app
 * is going for, and it was the single thing making the UI feel dated.
 *
 * Outfit carries the headings and the amounts: geometric, nearly monolinear, and its
 * digits are wide and even, which is what you want for a column of figures. Inter
 * carries everything else; it was designed for screen UI at small sizes and is the most
 * legible thing available at 12sp.
 *
 * The other half of looking current is not the faces at all, it is the metrics: tight
 * negative tracking that grows tighter as the size grows, line heights close to the
 * cap height on display sizes, and tabular figures everywhere a number might be
 * compared with the number above it.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    // Shipped by androidx.compose.ui:ui-text-google-fonts. Deliberately not hand-written:
    // a wrong certificate does not fail the build, it just silently never loads the font.
    certificates = R.array.com_google_android_gms_fonts_certs
)

/**
 * Both families list their weights explicitly and end at a platform fallback. Fonts are
 * fetched once and cached by Play Services; until the first fetch lands, and forever on
 * a device with no Play Services, the fallback renders. Text is therefore never invisible
 * and never blocks a frame — the worst case is that it looks like the old build.
 */
private fun googleFamily(name: String): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(
        Font(googleFont = font, fontProvider = provider, weight = FontWeight.Normal),
        Font(googleFont = font, fontProvider = provider, weight = FontWeight.Medium),
        Font(googleFont = font, fontProvider = provider, weight = FontWeight.SemiBold),
        Font(googleFont = font, fontProvider = provider, weight = FontWeight.Bold)
    )
}

private val displayFamily: FontFamily = googleFamily("Outfit")
private val bodyFamily: FontFamily = googleFamily("Inter")

val DisplayFamily: FontFamily = displayFamily
val BodyFamily: FontFamily = bodyFamily

/** Display text sits on its own line height rather than the font's generous default. */
private val displayLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both
)

private val textLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/** Numbers do not reflow as they change. Every amount and count opts in. */
private const val TABULAR = "tnum"

private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

val SnapTabTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.4).sp,
        fontFeatureSettings = TABULAR,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    displayMedium = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 33.sp,
        lineHeight = 37.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = TABULAR,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    displaySmall = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.7).sp,
        fontFeatureSettings = TABULAR,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    headlineLarge = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    headlineMedium = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    headlineSmall = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = displayLineHeight,
        platformStyle = noFontPadding
    ),
    titleLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = textLineHeight
    ),
    titleMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = textLineHeight
    ),
    titleSmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        lineHeightStyle = textLineHeight
    ),
    bodyLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = textLineHeight
    ),
    bodyMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = textLineHeight
    ),
    bodySmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        lineHeightStyle = textLineHeight
    ),
    labelLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

/**
 * The section headers: small, wide-tracked, quiet. Tracking rather than a rule or a
 * heavier weight is what separates sections without adding furniture.
 */
val SectionLabelStyle: TextStyle = SnapTabTypography.labelMedium.copy(
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.8.sp
)

/** Any figure the user might compare against the figure above it. */
val AmountStyle: TextStyle = TextStyle(
    fontFamily = displayFamily,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = TABULAR,
    platformStyle = noFontPadding
)

/** Inline amounts in a list row: the body face, still tabular so columns line up. */
val InlineAmountStyle: TextStyle = TextStyle(
    fontFamily = bodyFamily,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.1).sp,
    fontFeatureSettings = TABULAR,
    platformStyle = noFontPadding
)
