package com.apricotiris.IrisShot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

private val GameLongShotDarkColors = darkColorScheme(
    primary = M3DarkPrimary,
    onPrimary = M3DarkOnPrimary,
    primaryContainer = M3DarkPrimaryContainer,
    onPrimaryContainer = M3DarkOnPrimaryContainer,
    secondary = M3DarkSecondary,
    onSecondary = M3DarkOnSecondary,
    secondaryContainer = M3DarkSecondaryContainer,
    onSecondaryContainer = M3DarkOnSecondaryContainer,
    tertiary = M3DarkTertiary,
    onTertiary = M3DarkOnTertiary,
    background = M3DarkBackground,
    onBackground = M3DarkOnBackground,
    surface = M3DarkSurface,
    onSurface = M3DarkOnSurface,
    surfaceVariant = M3DarkSurfaceVariant,
    onSurfaceVariant = M3DarkOnSurfaceVariant,
    outline = M3DarkOutline,
    outlineVariant = M3DarkOutlineVariant,
    surfaceContainer = M3DarkSurfaceContainer,
    surfaceContainerHigh = M3DarkSurfaceContainerHigh,
    error = M3DarkError,
    onError = M3DarkOnError,
)

private val GameLongShotLightColors = lightColorScheme(
    primary = M3LightPrimary,
    onPrimary = M3LightOnPrimary,
    primaryContainer = M3LightPrimaryContainer,
    onPrimaryContainer = M3LightOnPrimaryContainer,
    secondary = M3LightSecondary,
    onSecondary = M3LightOnSecondary,
    secondaryContainer = M3LightSecondaryContainer,
    onSecondaryContainer = M3LightOnSecondaryContainer,
    tertiary = M3LightTertiary,
    onTertiary = M3LightOnTertiary,
    background = M3LightBackground,
    onBackground = M3LightOnBackground,
    surface = M3LightSurface,
    onSurface = M3LightOnSurface,
    surfaceVariant = M3LightSurfaceVariant,
    onSurfaceVariant = M3LightOnSurfaceVariant,
    outline = M3LightOutline,
    outlineVariant = M3LightOutlineVariant,
    surfaceContainer = M3LightSurfaceContainer,
    surfaceContainerHigh = M3LightSurfaceContainerHigh,
    error = M3LightError,
    onError = M3LightOnError,
)

private fun useDarkTheme(themeMode: String, systemInDarkTheme: Boolean): Boolean =
    when (themeMode.uppercase()) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemInDarkTheme
    }

private fun resolveColorScheme(
    context: Context,
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> GameLongShotDarkColors
    else -> GameLongShotLightColors
}

internal fun resolveGameLongShotWindowBackgroundColor(
    context: Context,
    themeMode: String,
    dynamicColor: Boolean,
): Int {
    val systemInDarkTheme = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return resolveColorScheme(
        context = context,
        darkTheme = useDarkTheme(themeMode, systemInDarkTheme),
        dynamicColor = dynamicColor,
    ).background.toArgb()
}

@Suppress("DEPRECATION")
@Composable
fun GameLongShotTheme(
    themeMode: String = "SYSTEM",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = useDarkTheme(themeMode, systemInDarkTheme)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val colorScheme = remember(context, configuration, darkTheme, dynamicColor) {
        resolveColorScheme(context, darkTheme, dynamicColor)
    }

    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme, colorScheme.background) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            onDispose {}
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = GameLongShotShapes,
        content = content,
    )
}
