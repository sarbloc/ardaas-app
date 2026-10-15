package io.sarbloc.ardaas.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** White lifted over the top of the gradient: an opaque step up from navy. */
private fun lift(alpha: Float): Color = Color.White.copy(alpha = alpha).compositeOver(ArdaasColors.NavyTop)

/**
 * The brand palette mapped onto Material 3 roles, so stock components pick it
 * up without per-call colours: kesri is the accent, navy every surface, and
 * the parchment / mist reading colours the on-surface text. Surface containers
 * (sheets, menus, cards) step up from navy with white, matching iOS's raised
 * fill rather than the baseline scheme's purple-grey.
 */
internal val ArdaasColorScheme: ColorScheme = darkColorScheme(
    primary = ArdaasColors.Kesri,
    onPrimary = ArdaasColors.NavyBottom,
    primaryContainer = ArdaasColors.Kesri.copy(alpha = 0.15f).compositeOver(ArdaasColors.NavyTop),
    onPrimaryContainer = ArdaasColors.Kesri,
    secondary = ArdaasColors.Sand,
    onSecondary = ArdaasColors.NavyBottom,
    tertiary = ArdaasColors.Mist,
    onTertiary = ArdaasColors.NavyBottom,
    background = ArdaasColors.NavyBottom,
    onBackground = ArdaasColors.Parchment,
    surface = ArdaasColors.NavyBottom,
    onSurface = ArdaasColors.Parchment,
    surfaceVariant = lift(0.06f),
    onSurfaceVariant = ArdaasColors.Mist,
    // Neutral, so any tonal elevation lightens navy instead of tinting it kesri.
    surfaceTint = Color.White,
    surfaceDim = ArdaasColors.NavyBottom,
    surfaceBright = lift(0.14f),
    surfaceContainerLowest = ArdaasColors.NavyBottom,
    surfaceContainerLow = ArdaasColors.NavyTop,
    surfaceContainer = lift(0.06f),
    surfaceContainerHigh = lift(0.10f),
    surfaceContainerHighest = lift(0.14f),
    outline = ArdaasColors.Mist,
    outlineVariant = lift(0.14f),
)

/**
 * Dark by design, matching iOS (`.preferredColorScheme(.dark)`): always the
 * brand scheme. Deliberately no `isSystemInDarkTheme()` branch and no dynamic
 * (wallpaper) colour — the icon is the theme.
 */
@Composable
fun ArdaasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArdaasColorScheme,
        typography = Typography(),
        content = content,
    )
}
