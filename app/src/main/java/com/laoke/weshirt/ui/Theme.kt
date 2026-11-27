package com.laoke.weshirt.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable fun WeShirtTheme(appearance: String, palette: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDark = isSystemInDarkTheme()
    val scheme = remember(context, configuration, appearance, palette, systemDark) {
        resolveWeShirtColorScheme(context, appearance, palette, systemDark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
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
    return if (selected == "dynamic" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        val primary = lerp(seed, Color.White, .55f)
        val container = lerp(Color(0xFF10141C), seed, .42f)
        darkColorScheme(primary = primary, onPrimary = lerp(Color.Black, seed, .20f),
            primaryContainer = container, onPrimaryContainer = lerp(primary, Color.White, .38f),
            secondaryContainer = lerp(Color(0xFF171D29), seed, .25f), onSecondaryContainer = Color(0xFFEDF1F9),
            background = Color(0xFF10141C), surface = Color(0xFF171D29))
    } else {
        val tinted = lerp(seed, Color.White, .85f)
        val ink = lerp(seed, Color.Black, .55f)
        lightColorScheme(primary = seed, onPrimary = Color.White,
            primaryContainer = tinted, onPrimaryContainer = ink,
            secondaryContainer = lerp(seed, Color.White, .90f), onSecondaryContainer = ink,
            background = Color(0xFFF6F8FD), surface = Color(0xFFFDFBFF))
    }
}
