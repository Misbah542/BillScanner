package com.snaptab.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Two faces with real contrast: a serif for amounts and headings, a grotesk for
 * everything else. Amounts in a serif is the deliberate bit — it makes a number feel
 * like a figure on a receipt rather than another line of UI.
 *
 * These resolve to the platform's serif and sans families, so text always renders and
 * nothing is bundled or downloaded. The design was drawn with Fraunces (display) and
 * Schibsted Grotesk (body); to use those, drop the variable .ttf files into
 * `res/font/` and change the two families below to
 * `FontFamily(Font(R.font.fraunces, FontWeight.SemiBold), …)`. Nothing else changes.
 *
 * Note what is NOT here: Inter and Roboto, and any reliance on downloadable fonts,
 * whose provider certificates would have to be right for the text to appear at all.
 */
private val displayFamily = FontFamily.Serif
private val bodyFamily = FontFamily.SansSerif

val DisplayFamily: FontFamily = displayFamily
val BodyFamily: FontFamily = bodyFamily

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

val SnapTabTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = tightLineHeight
    ),
    displayMedium = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = tightLineHeight
    ),
    displaySmall = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = tightLineHeight
    ),
    headlineMedium = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = displayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

/** The section headers: small, spaced, upper-case. */
val SectionLabelStyle: TextStyle = SnapTabTypography.labelMedium.copy(
    letterSpacing = 0.5.sp
)

/** Any figure the user might compare against another figure. */
val AmountStyle: TextStyle = TextStyle(
    fontFamily = displayFamily,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/** Inline amounts in a list row, in the body face but still tabular. */
val InlineAmountStyle: TextStyle = TextStyle(
    fontFamily = bodyFamily,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum"
)
