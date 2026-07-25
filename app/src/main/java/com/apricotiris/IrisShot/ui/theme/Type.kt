package com.apricotiris.IrisShot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BrandFont = FontFamily.SansSerif

val Typography = Typography(
    displaySmall = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = BrandFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)

val GameLongShotShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
