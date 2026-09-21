package com.snaptab.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.snaptab.app.R

/**
 * Two geometric sans faces, bundled, no serif anywhere.
 *
 * The previous scale used FontFamily.Serif for headings and amounts. A serif reads as
 * traditional — a newspaper, a bank statement — which is the opposite of what this app is
 * going for, and it was the single thing making the UI feel dated.
 *
 * Outfit carries the headings and the amounts: geometric, nearly monolinear, with wide even
 * digits, which is what a column of figures needs. Inter carries everything else; it was
 * drawn for screen UI and is the most legible thing available at 12sp.
 *
 * The files are in res/font rather than fetched at runtime. Downloadable fonts were the
 * first attempt and are the wrong trade here: they need Play Services, they need a
 * certificate array that turned out not to ship with the library, and a wrong certificate
 * does not fail the build — it silently renders the fallback forever. 1.3MB of APK buys
 * text that is correct on the first frame, on every device, with nothing to configure.
 *
 * Only the weights actually used are bundled. Compose picks the nearest weight for anything
 * else, so the display face needs no Normal: every display style here is SemiBold.
 *
 * The other half of looking current is not the faces at all, it is the metrics: negative
 * tracking that tightens as the size grows, line heights close to the cap height on display
 * sizes, and tabular figures everywhere a number might be compared with the one above it.
 *
 * Both families are licensed under the SIL Open Font License 1.1 — see
 * apps/android/fonts-LICENSE.txt, which the OFL requires to travel with them.
 */
private val displayFamily = FontFamily(
    Font(R.font.outfit_600, FontWeight.SemiBold),
    Font(R.font.outfit_700, FontWeight.Bold)
)

private val bodyFamily = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold)
)

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
