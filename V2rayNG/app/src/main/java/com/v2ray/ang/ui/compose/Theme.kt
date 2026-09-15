package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ParsV2R palette, taken from the lion badge: warm gold on near-black.
private val ParsGold = Color(0xFFF5B841)
private val ParsGoldDeep = Color(0xFFB9791A)
private val ParsInk = Color(0xFF0B0B0D)

private val LightColor = lightColorScheme(
    primary = ParsGoldDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE7C0),
    onPrimaryContainer = Color(0xFF2A1A00),
    secondary = ParsGoldDeep,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE7C0),
    onSecondaryContainer = Color(0xFF2A1A00),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1B1813),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1B1813),
    surfaceVariant = Color(0xFFEFE8DC),
    onSurfaceVariant = Color(0xFF5A5246),
    outline = Color(0xFF8C8374),
    outlineVariant = Color(0xFFDDD4C5),
    surfaceTint = ParsGoldDeep,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1E8),
    surfaceContainer = Color(0xFFF1EBE0),
    surfaceContainerHigh = Color(0xFFEBE4D8),
    surfaceContainerHighest = Color(0xFFE5DDD0),
)

private val DarkColor = darkColorScheme(
    primary = ParsGold,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF3A2A0C),
    onPrimaryContainer = Color(0xFFFBE7C0),
    secondary = ParsGold,
    onSecondary = Color(0xFF2A1A00),
    secondaryContainer = Color(0xFF3A2A0C),
    onSecondaryContainer = Color(0xFFFBE7C0),
    tertiary = Color(0xFF83D6B5), // Mint Green
    onTertiary = Color(0xFF00382E), // Dark Teal
    tertiaryContainer = Color(0xFF005143), // Teal
    onTertiaryContainer = Color(0xFFA0F2D0), // Light Green
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = ParsInk,
    onBackground = Color(0xFFF2EDE4),
    surface = ParsInk,
    onSurface = Color(0xFFF2EDE4),
    surfaceVariant = Color(0xFF2A2721),
    onSurfaceVariant = Color(0xFFBDB5A6),
    outline = Color(0xFF7A7264),
    outlineVariant = Color(0xFF2E2B25),
    inverseSurface = Color(0xFFF2EDE4),
    inverseOnSurface = ParsInk,
    inversePrimary = ParsGoldDeep,
    scrim = Color(0xFF000000),
    surfaceTint = ParsGold,
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = Color(0xFF131316),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1E1E22),
    surfaceContainerHighest = Color(0xFF26262B),
)

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = Color(0xFFD9A032) // ParsV2R gold, readable on light and dark
val colorFabActive = Color(0xFFF5B841) // ParsV2R gold
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    // ParsV2R opens dark with its own palette. Wallpaper-based dynamic colour would replace
    // the gold with whatever the phone picks, so it starts off; both can still be changed in
    // settings.
    const val DEFAULT_UI_MODE_NIGHT = "2"
    const val DEFAULT_DYNAMIC_COLOR = false

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_UI_MODE_NIGHT) ?: DEFAULT_UI_MODE_NIGHT
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_UI_MODE_NIGHT) ?: DEFAULT_UI_MODE_NIGHT
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
