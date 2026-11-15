package com.laoke.weshirt.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable fun WeShirtTheme(appearance: String, palette: String, content: @Composable () -> Unit) {
    val scheme = resolveWeShirtColorScheme(LocalContext.current, appearance, palette, isSystemInDarkTheme())
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

internal fun normalizeAppearance(value: String?) = value?.takeIf { it in setOf("system", "light", "dark") } ?: "system"
internal fun normalizePalette(value: String?) = value?.takeIf { it in setOf("blue", "green", "purple", "dynamic") } ?: "blue"
internal fun isWeShirtDark(appearance: String?, systemDark: Boolean): Boolean = when (normalizeAppearance(appearance)) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/** Both Compose and RemoteViews use this exact scheme, including Android's wallpaper palette. */
internal fun resolveWeShirtColorScheme(
    context: Context,
    appearance: String?,
    palette: String?,
    systemDark: Boolean = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
): ColorScheme {
    val dark = isWeShirtDark(appearance, systemDark)
    val selected = normalizePalette(palette)
    val seed = when(selected) { "green" -> Color(0xFF28765D); "purple" -> Color(0xFF7452B0); else -> Color(0xFF2965D9) }
    return if(selected == "dynamic" && Build.VERSION.SDK_INT >= 31) { if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if(dark) darkColorScheme(primary = seed.copy(red = (seed.red + 0.4f).coerceAtMost(1f), green = (seed.green + 0.3f).coerceAtMost(1f), blue = (seed.blue + 0.2f).coerceAtMost(1f)), background = Color(0xFF10141C), surface = Color(0xFF171D29))
        else lightColorScheme(primary = seed, background = Color(0xFFF6F8FD), surface = Color(0xFFFDFBFF), secondaryContainer = Color(0xFFE3ECFF), onSecondaryContainer = Color(0xFF18437F))
}
