package com.pakdel.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** دو شخصیت بصری: اپ مدیریت (آرام/حرفه‌ای) و اپ فروشگاه (رنگارنگ) */
enum class PakdelBrand { Manager, Shop }

private val ManagerLight = lightColorScheme(
    primary = PakdelBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E4F5),
    onPrimaryContainer = PakdelBlueDark,
    secondary = PakdelAmber,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2B1B00),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLightVariant,
    onSurface = OnSurfaceLight,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEDF0F4),
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE3E7EC),
    error = DangerRed,
    onError = androidx.compose.ui.graphics.Color.White,
)

private val ManagerDark = darkColorScheme(
    primary = PakdelBlueLight,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF05213F),
    primaryContainer = PakdelBlueDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E4F5),
    secondary = PakdelAmber,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2B1B00),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDarkVariant,
    onSurface = OnSurfaceDark,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252A2F),
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
)

private val ShopLight = lightColorScheme(
    primary = ShopPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE9E1FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF2A0E6B),
    secondary = ShopSecondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = ShopTertiary,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFFFBF9FF),
    onBackground = OnSurfaceLight,
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = OnSurfaceLight,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1EDF9),
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = DangerRed,
)

private val ShopDark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB6A0FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF2A0E6B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF452197),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE9E1FF),
    secondary = ShopCoral,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF3B0A00),
    tertiary = androidx.compose.ui.graphics.Color(0xFF66E0D0),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF00332D),
    background = androidx.compose.ui.graphics.Color(0xFF141019),
    onBackground = OnSurfaceDark,
    surface = androidx.compose.ui.graphics.Color(0xFF1E1926),
    onSurface = OnSurfaceDark,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A2434),
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = androidx.compose.ui.graphics.Color(0xFFFF8A80),
)

/**
 * تم اصلی برنامه. همیشه راست‌چین است — بدون توجه به زبان دستگاه.
 */
@Composable
fun PakdelTheme(
    brand: PakdelBrand = PakdelBrand.Manager,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when (brand) {
        PakdelBrand.Manager -> if (darkTheme) ManagerDark else ManagerLight
        PakdelBrand.Shop -> if (darkTheme) ShopDark else ShopLight
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colors,
            typography = PakdelTypography,
            content = content,
        )
    }
}
