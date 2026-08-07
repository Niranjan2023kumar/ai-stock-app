package com.example.myapplication3.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Groww look color scheme — light background, green as primary ──────────────
// The app is intentionally locked to this single light scheme regardless of the
// system dark/light setting, for one coherent look.
private val StyleCColorScheme = lightColorScheme(
    primary             = GoldAccent,          // brand green
    onPrimary           = TextOnGold,          // white text on green CTA
    primaryContainer    = GoldContainer,       // soft green tint
    onPrimaryContainer  = GoldLight,           // deep green ink on the tint
    secondary           = GreenPrimary,
    onSecondary         = Color.White,
    secondaryContainer  = GreenContainer,
    onSecondaryContainer = GreenLight,
    tertiary            = BlueAccent,
    onTertiary          = Color.White,
    tertiaryContainer   = BlueContainer,
    onTertiaryContainer = BlueLight,
    background          = DarkBackground,       // #F7F8FA light
    onBackground        = TextPrimary,          // near-black
    surface             = DarkSurface,          // white
    onSurface           = TextPrimary,          // near-black
    surfaceVariant      = DarkSurfaceElevated,  // neutral light chip
    onSurfaceVariant    = TextSecondary,
    outline             = DarkBorder,           // light hairline
    outlineVariant      = Color(0xFFEEF1F5),
    error               = RedPrimary,
    onError             = Color.White,
    errorContainer      = RedContainer,         // soft red tint
    onErrorContainer    = RedLight,             // deep red ink
    inverseSurface      = Color(0xFF2B3040),    // dark surface for snackbars
    inverseOnSurface    = Color(0xFFF7F8FA),
    scrim               = Color(0x99000000),
)

@Composable
fun StockIntelligenceTheme(content: @Composable () -> Unit) {
    // Light background → status-bar & nav-bar icons must be DARK to stay visible.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(
        colorScheme = StyleCColorScheme,
        typography  = Typography,
        content     = content
    )
}

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) = StockIntelligenceTheme(content)
