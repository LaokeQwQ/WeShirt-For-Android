package com.laoke.weshirt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable fun WeShirtTheme(appearance: String, palette: String, content: @Composable () -> Unit) {
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val seed = when(palette) { "green" -> Color(0xFF28765D); "purple" -> Color(0xFF7452B0); else -> Color(0xFF2965D9) }
    val scheme = if(palette == "dynamic" && android.os.Build.VERSION.SDK_INT >= 31) { if(dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current) }
        else if(dark) darkColorScheme(primary = seed.copy(red = (seed.red + 0.4f).coerceAtMost(1f), green = (seed.green + 0.3f).coerceAtMost(1f), blue = (seed.blue + 0.2f).coerceAtMost(1f)), background = Color(0xFF10141C), surface = Color(0xFF171D29))
        else lightColorScheme(primary = seed, background = Color(0xFFF6F8FD), surface = Color(0xFFFDFBFF), secondaryContainer = Color(0xFFE3ECFF), onSecondaryContainer = Color(0xFF18437F))
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
