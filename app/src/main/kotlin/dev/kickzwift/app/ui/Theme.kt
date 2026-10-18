package dev.kickzwift.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Rouge dans l'esprit COROS pour les boutons et les accents, gris neutres pour les fonds :
// aucune teinte violette héritée du thème Material par défaut.
private val Red = Color(0xFFE4002B)
private val RedDark = Color(0xFFC8102E)

/** Pastille « prêt » : vert, pour ne pas confondre avec le rouge des boutons et des pertes. */
val ReadyGreen = Color(0xFF43A047)

private val Dark =
    darkColorScheme(
        primary = Red,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF5C0011),
        onPrimaryContainer = Color(0xFFFFDAD6),
        secondary = Color(0xFFBDBDBD),
        onSecondary = Color(0xFF1A1A1A),
        secondaryContainer = Color(0xFF2E2E2E),
        onSecondaryContainer = Color(0xFFEAEAEA),
        tertiary = Color(0xFFFFB74D),
        onTertiary = Color(0xFF2B1700),
        tertiaryContainer = Color(0xFF4A3300),
        onTertiaryContainer = Color(0xFFFFDDB3),
        background = Color.Black,
        onBackground = Color(0xFFE6E6E6),
        surface = Color.Black,
        onSurface = Color(0xFFE6E6E6),
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFC4C4C4),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0E0E0E),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF242424),
        outline = Color(0xFF8C8C8C),
        outlineVariant = Color(0xFF3A3A3A),
    )

private val Light =
    lightColorScheme(
        primary = RedDark,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF410006),
        secondary = Color(0xFF5E5E5E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE6E6E6),
        onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFF8A5100),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB3),
        onTertiaryContainer = Color(0xFF2B1700),
        background = Color.White,
        onBackground = Color(0xFF1A1A1A),
        surface = Color.White,
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFEEEEEE),
        onSurfaceVariant = Color(0xFF444444),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF1F1F1),
        surfaceContainerHigh = Color(0xFFEBEBEB),
        surfaceContainerHighest = Color(0xFFE4E4E4),
        outline = Color(0xFF767676),
        outlineVariant = Color(0xFFC8C8C8),
    )

/** Thème Material 3, sombre par défaut ; clair ou système dans les réglages. */
@Composable
fun KickZwiftTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}
