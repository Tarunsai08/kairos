package com.tarun.kairos.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val light =
    lightColorScheme(
        primary = Color(0xFF3D6658),
        secondary = Color(0xFF826946),
        background = Color(0xFFF8F9F4),
        surface = Color(0xFFF8F9F4),
    )
private val dark =
    darkColorScheme(
        primary = Color(0xFFA1D0BA),
        secondary = Color(0xFFD8BE94),
        background = Color(0xFF101713),
        surface = Color(0xFF101713),
    )

@Composable
fun KairosTheme(theme: String = "SYSTEM", content: @Composable () -> Unit) {
    val night =
        when (theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }
    }
    val colors =
        if (Build.VERSION.SDK_INT >= 31) {
            if (night) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else if (night) dark else light
    MaterialTheme(colorScheme = colors, content = content)
}
