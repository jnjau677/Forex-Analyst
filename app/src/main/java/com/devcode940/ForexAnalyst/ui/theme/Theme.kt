package com.devcode940.ForexAnalyst.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.devcode940.ForexAnalyst.data.settings.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80,
    background = CanvasDark, surface = CanvasDark
)
private val LightColorScheme = lightColorScheme(
    primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40,
    background = CanvasLight, surface = CanvasLight
)

/**
 * Application Material 3 theme.
 *
 * WHY [themeMode] instead of a Boolean: the Settings screen offered
 * System/Light/Dark, but the theme composable only ever read
 * `isSystemInDarkTheme()` — the user's choice was persisted and then ignored.
 * Driving it from [ThemeMode] makes the setting actually take effect.
 *
 * @param themeMode user-selected appearance; [ThemeMode.SYSTEM] follows the OS.
 * @param dynamicColor use Material You palette extraction on Android 12+.
 * @param content screen tree to theme.
 */
@Composable
fun ForexAnalystTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // WHY: exhaustive when over the sealed/enum states — adding a new mode is
    // a compile error here, not a silently ignored preference.
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // WHY: safe cast (as?) — the old `as Activity` crashed with
            // ClassCastException whenever the view's context was a
            // ContextThemeWrapper (e.g. previews, dialogs, some OEM launchers).
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
