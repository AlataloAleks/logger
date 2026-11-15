package dev.tohma.logger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Palette offered when choosing an activity color (ARGB). */
val ActivityPalette: List<Int> = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00897B,
    0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60,
).map { it.toInt() }

@Composable
fun LoggerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme(primary = Color(0xFF90CAF9))
        else -> lightColorScheme(primary = Color(0xFF1E88E5))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
