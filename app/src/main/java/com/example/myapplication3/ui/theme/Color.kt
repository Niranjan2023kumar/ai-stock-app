package com.example.myapplication3.ui.theme

import androidx.compose.ui.graphics.Color

// ── Groww look: Light + Green ────────────────────────────────────────────────
// NOTE: token NAMES are historical (Dark*/Gold*) but their VALUES are now the
// Groww light palette. Every name below is still referenced by screens, so none
// may be removed — they are only re-valued. Re-theme the whole app from here.

// Backgrounds — light
val DarkBackground      = Color(0xFFF7F8FA)   // main app background (light grey)
val DarkSurface         = Color(0xFFFFFFFF)   // cards, bottom sheets (white)
val DarkSurfaceElevated = Color(0xFFEEF1F6)   // neutral chips, tab bars, selected states
val DarkCard            = Color(0xFFFFFFFF)   // card containers (white)
val DarkBorder          = Color(0xFFE3E7EE)   // dividers, hairline outlines
val DarkTabBar          = Color(0xFFFFFFFF)   // bottom tab bar background (white)

// Brand green — primary CTA / selected tab / brand accent (was Gold*)
val GoldAccent          = Color(0xFF00B386)   // primary CTA, selected tabs, brand
val GoldLight           = Color(0xFF006B4A)   // deep green INK on soft-green containers (>=4.5:1)
val GoldDim             = Color(0xFF009E78)   // pressed / dimmed green
val GoldContainer       = Color(0xFFE1F6F0)   // soft brand-green tinted surface

// Green — buy / positive / up (price up = #00B386)
val GreenPrimary        = Color(0xFF00B386)   // BUY signal, gain, price up
val GreenLight          = Color(0xFF007A5C)   // readable gain text / ink on green tint
val GreenDark           = Color(0xFF009060)   // pressed green
val GreenContainer      = Color(0xFFE3F7EE)   // soft green-tinted card bg

// Red — sell / negative / down (price down = #EB5B3C)
val RedPrimary          = Color(0xFFEB5B3C)   // SELL signal, loss, price down
val RedLight            = Color(0xFFC0392B)   // readable loss text / ink on red tint (>=4.5:1)
val RedDark             = Color(0xFFA93226)   // pressed red
val RedContainer        = Color(0xFFFDECEA)   // soft red-tinted card bg on white

// Blue — info, links, secondary
val BlueAccent          = Color(0xFF1E63D8)   // readable info/link text on white (>=4.5:1)
val BlueLight           = Color(0xFF174EA6)   // deep blue ink on soft-blue container
val BlueContainer       = Color(0xFFE8F0FE)   // soft blue-tinted surface

// Text hierarchy — dark ink on light bg
val TextPrimary         = Color(0xFF1A1A2E)   // headings, important data (near-black)
val TextSecondary       = Color(0xFF3A4A5A)   // sub-labels, metadata
val TextMuted           = Color(0xFF5B6B7B)   // placeholder, disabled (>=4.5:1 on #F7F8FA)
val TextOnGold          = Color(0xFFFFFFFF)   // white text on the green CTA button

// Caution amber — warnings / practice-mode / medium-confidence (NOT the brand green)
val CautionAmber        = Color(0xFFF5A623)   // amber accent / icon / border for warnings
val CautionContainer    = Color(0xFFFEF4E0)   // soft amber-tinted surface for caution cards

// Legacy aliases — kept so older screens compile without changes
val Green  = GreenPrimary
val Red    = RedPrimary
val Gold   = GoldAccent
val Blue   = BlueAccent
