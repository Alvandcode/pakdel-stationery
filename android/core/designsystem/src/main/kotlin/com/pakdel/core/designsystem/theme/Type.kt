package com.pakdel.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * فونت فارسی: Vazirmatn.
 *
 * برای فعال‌سازی فونت اختصاصی، فایل‌های زیر را در `res/font/` قرار دهید:
 *   vazirmatn_regular.ttf, vazirmatn_medium.ttf, vazirmatn_bold.ttf
 * سپس در همین فایل `fontFamily = FontFamily(Font(R.font.vazirmatn_regular, FontWeight.Normal), …)`
 * را جایگزین [PakdelFontFamily] کنید. تا آن زمان از فونت سیستمی استفاده می‌شود
 * تا پروژه بدون وابستگی به فایل باینری قابل بیلد باشد.
 */
val PakdelFontFamily: FontFamily = FontFamily.SansSerif

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = PakdelFontFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

val PakdelTypography = Typography(
    displaySmall = style(30, 42, FontWeight.Bold),
    headlineLarge = style(26, 38, FontWeight.Bold),
    headlineMedium = style(22, 34, FontWeight.Bold),
    headlineSmall = style(20, 30, FontWeight.SemiBold),
    titleLarge = style(18, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 22, FontWeight.Medium),
    bodyLarge = style(16, 26),
    bodyMedium = style(14, 24),
    bodySmall = style(12, 20),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 18, FontWeight.Medium),
    labelSmall = style(11, 16),
)
