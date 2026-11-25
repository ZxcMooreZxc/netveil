package com.netveil.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VeilBg = Color(0xFF090D16)
val VeilSurface = Color(0xFF111827)
val VeilSurfaceHigh = Color(0xFF1A2334)
val VeilBorder = Color(0xFF263248)
val VeilEmerald = Color(0xFF10B981)
val VeilCyan = Color(0xFF38BDF8)
val VeilAmber = Color(0xFFF59E0B)
val VeilRed = Color(0xFFEF4444)
val VeilText = Color(0xFFE2E8F0)
val VeilMuted = Color(0xFF94A3B8)

private val NetVeilDarkScheme = darkColorScheme(
  primary = VeilCyan,
  onPrimary = Color(0xFF05131E),
  primaryContainer = Color(0xFF0E3A54),
  onPrimaryContainer = Color(0xFFBAE6FD),
  secondary = VeilEmerald,
  onSecondary = Color(0xFF022C22),
  secondaryContainer = Color(0xFF064E3B),
  onSecondaryContainer = Color(0xFFA7F3D0),
  background = VeilBg,
  onBackground = VeilText,
  surface = VeilSurface,
  onSurface = VeilText,
  surfaceVariant = VeilSurfaceHigh,
  onSurfaceVariant = VeilMuted,
  outline = VeilBorder,
  error = VeilRed,
  onError = Color.White
)

@Composable
fun NetVeilTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = NetVeilDarkScheme,
    typography = Typography,
    content = content
  )
}
