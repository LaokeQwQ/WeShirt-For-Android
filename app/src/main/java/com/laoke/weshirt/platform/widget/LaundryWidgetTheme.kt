package com.laoke.weshirt.platform.widget

import android.app.WallpaperManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.laoke.weshirt.ui.isWeShirtDark
import com.laoke.weshirt.ui.normalizeAppearance
import com.laoke.weshirt.ui.normalizePalette
import com.laoke.weshirt.ui.resolveWeShirtColorScheme

/** Widget appearance belongs to the app's preferences, independently of the launcher's theme. */
object LaundryWidgetTheme {
    private var observing = false
    private var lastConfiguration: Configuration? = null
    private val handler = Handler(Looper.getMainLooper())

    internal fun followsSystem(context: Context): Boolean = normalizeAppearance(
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("appearance", "system")) == "system"

    internal fun resolve(context: Context, forceDark: Boolean? = null): WidgetArtworkPalette {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        val appearance = normalizeAppearance(prefs.getString("appearance", "system"))
        val palette = normalizePalette(prefs.getString("palette", "blue"))
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = forceDark ?: isWeShirtDark(appearance, systemDark)
        val scheme = resolveWeShirtColorScheme(context, if (dark) "dark" else "light", palette, systemDark)
        val surface = scheme.background.toArgb()
        val accent = scheme.primary.toArgb()
        val text = scheme.onBackground.toArgb()
        fun mix(first: Int, second: Int, secondFraction: Float) = ColorUtils.blendARGB(first, second, secondFraction)
        return WidgetArtworkPalette(
            surface = surface,
            insetSurface = mix(surface, accent, if (dark) .08f else .055f),
            outline = mix(surface, text, if (dark) .20f else .14f),
            primaryText = text,
            secondaryText = mix(surface, text, if (dark) .72f else .68f),
            accent = accent,
            accentSoft = mix(surface, accent, if (dark) .20f else .14f),
            machineBody = mix(surface, text, if (dark) .10f else .025f),
            machineEdge = mix(surface, text, if (dark) .40f else .30f),
            glass = mix(surface, accent, if (dark) .12f else .08f),
            cloth = mix(accent, text, if (dark) .22f else .30f),
            dark = dark,
        )
    }

    /** Commit before publishing RemoteViews; a launched host must never see the old preference. */
    internal fun savePreference(
        context: Context,
        key: String,
        value: String,
        redraw: (Context) -> Unit = LaundryWidgetStore::refresh,
    ): String? {
        val normalized = when (key) {
            "appearance" -> normalizeAppearance(value)
            "palette" -> normalizePalette(value)
            else -> return null
        }
        if (context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putString(key, normalized).commit()) redraw(context)
        return normalized
    }

    /** Uses app-scoped callbacks; no service, alarm, endpoint or extra periodic fetch is involved. */
    @Synchronized fun observe(context: Context) {
        if (observing) return
        val app = context.applicationContext
        observing = true
        lastConfiguration = Configuration(app.resources.configuration)
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val previous = lastConfiguration
                lastConfiguration = Configuration(newConfig)
                if (previous == null || previous.uiMode != newConfig.uiMode || previous.locales != newConfig.locales ||
                    previous.fontScale != newConfig.fontScale || previous.densityDpi != newConfig.densityDpi) {
                    handler.post { LaundryWidgetStore.refresh(app) }
                }
            }
            override fun onLowMemory() = Unit
        })
        // Reading wallpaper pixels needs extra access; listening for a color change does not.
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener({ _, _ ->
                if (normalizePalette(app.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("palette", "blue")) == "dynamic") {
                    handler.post { LaundryWidgetStore.refresh(app) }
                }
            }, handler)
        }
    }
}
