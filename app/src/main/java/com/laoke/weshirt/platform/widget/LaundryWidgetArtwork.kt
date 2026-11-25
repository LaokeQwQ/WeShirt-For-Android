package com.laoke.weshirt.platform.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Resolved app colours, shared by the native widget text and its artwork. */
internal data class WidgetArtworkPalette(
    val surface: Int,
    val insetSurface: Int,
    val outline: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val accentSoft: Int,
    val machineBody: Int,
    val machineEdge: Int,
    val glass: Int,
    val cloth: Int,
    val dark: Boolean,
)

/**
 * Small, bounded native bitmaps for RemoteViews, without a renderer or web view.
 * All washer layers use the same 96 × 112 coordinate space, so swapping only
 * the clothes frames never moves the case, glass or device-derived progress.
 */
internal object LaundryWidgetArtwork {
    private const val MACHINE_WIDTH = 192
    private const val MACHINE_HEIGHT = 224
    private const val DOOR_X = 48f
    private const val DOOR_Y = 66f

    fun background(palette: WidgetArtworkPalette, widthDp: Int = 320, heightDp: Int = 320): Bitmap {
        val logicalWidth = widthDp.coerceIn(64, 640).toFloat()
        val logicalHeight = heightDp.coerceIn(32, 960).toFloat()
        // Day/night RemoteViews may carry both palettes. Keep their combined
        // artwork below the bitmap allowance of common 720p launchers.
        val scale = min(1.5f, min(256f / logicalWidth, 384f / logicalHeight))
        return bitmap((logicalWidth * scale).roundToInt(), (logicalHeight * scale).roundToInt()) { canvas, paint ->
            // Composite first, then apply opacity once: overlapping glows and
            // borders must not make any background pixel more opaque than 75%.
            val cardLayer = canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), 191)
            canvas.scale(scale, scale)
            val bounds = RectF(.6f, .6f, logicalWidth - .6f, logicalHeight - .6f)
            val radius = min(26f, logicalHeight * .26f)
            val card = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(card)
            val deepSurface = if (palette.dark) mix(palette.surface, Color.BLACK, .36f)
                else mix(palette.surface, Color.WHITE, .32f)
            paint.shader = LinearGradient(0f, 0f, logicalWidth * .48f, logicalHeight,
                deepSurface, palette.surface, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            // Localize the reflection under the illustration, leaving an even surface under the labels.
            val square = logicalWidth <= logicalHeight * 1.25f
            val lightX = logicalWidth * if (square) .50f else .19f
            val lightY = logicalHeight * if (square) .46f else .73f
            val glowRadius = logicalHeight * if (square) .49f else .82f
            paint.shader = RadialGradient(lightX, lightY, glowRadius,
                intArrayOf(alpha(palette.accent, if (palette.dark) 115 else 49),
                    alpha(palette.accent, if (palette.dark) 36 else 18), Color.TRANSPARENT),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, logicalHeight * .30f,
                alpha(Color.WHITE, if (palette.dark) 11 else 118), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            canvas.restore()
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = alpha(palette.outline, if (palette.dark) 136 else 126)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            canvas.restoreToCount(cardLayer)
        }
    }

    fun shell(palette: WidgetArtworkPalette): Bitmap = washer { canvas, paint ->
        // A small contact shadow grounds the appliance without a heavy drop shadow.
        paint.shader = RadialGradient(48f, 108f, 43f,
            alpha(Color.BLACK, if (palette.dark) 76 else 29), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(6f, 103f, 90f, 112f), paint)
        paint.shader = null
        paint.color = mix(palette.machineEdge, palette.glass, .35f)
        canvas.drawRoundRect(RectF(14f, 105f, 25f, 111f), 2f, 2f, paint)
        canvas.drawRoundRect(RectF(71f, 105f, 82f, 111f), 2f, 2f, paint)

        // A darker right return and foot turn the front panel into an appliance rather than a flat icon.
        paint.color = mix(palette.machineEdge, Color.BLACK, if (palette.dark) .18f else .27f)
        canvas.drawRoundRect(RectF(9f, 3.5f, 94f, 108f), 10f, 10f, paint)
        val body = RectF(4f, 2f, 90f, 106f)
        paint.shader = LinearGradient(5f, 5f, 91f, 102f,
            intArrayOf(mix(palette.machineBody, Color.WHITE, .22f), palette.machineBody,
                mix(palette.machineBody, palette.machineEdge, .20f)), floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, 10f, 10f, paint)
        paint.shader = null
        paint.color = alpha(palette.machineEdge, 160)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .8f
        canvas.drawRoundRect(body, 10f, 10f, paint)
        paint.color = alpha(Color.WHITE, if (palette.dark) 36 else 190)
        canvas.drawRoundRect(RectF(6.4f, 3.4f, 89.6f, 106.6f), 8.8f, 8.8f, paint)
        paint.style = Paint.Style.FILL

        // Recessed detergent drawer, dial and two small indicator lights.
        paint.color = mix(palette.machineBody, palette.machineEdge, if (palette.dark) .40f else .20f)
        canvas.drawRoundRect(RectF(9f, 7f, 87f, 28f), 6f, 6f, paint)
        paint.color = alpha(palette.machineEdge, 96)
        canvas.drawRoundRect(RectF(15f, 12f, 43f, 20.3f), 2f, 2f, paint)
        paint.color = mix(palette.machineBody, Color.WHITE, .13f)
        canvas.drawRoundRect(RectF(16f, 12.8f, 42f, 18.8f), 1.5f, 1.5f, paint)
        paint.color = alpha(palette.machineEdge, 150)
        canvas.drawRoundRect(RectF(22f, 17.2f, 36f, 18.2f), .5f, .5f, paint)
        paint.shader = LinearGradient(60f, 10f, 73f, 23f,
            mix(palette.machineBody, Color.WHITE, .45f), palette.machineEdge, Shader.TileMode.CLAMP)
        canvas.drawCircle(67f, 16.5f, 6.5f, paint)
        paint.shader = null
        paint.color = palette.machineBody
        canvas.drawCircle(67f, 16.5f, 4.8f, paint)
        paint.color = palette.accent
        paint.strokeWidth = 1.5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(67f, 13f, 67f, 15.5f, paint)
        canvas.drawCircle(79f, 14f, 1.6f, paint)
        paint.color = alpha(palette.machineEdge, 160)
        canvas.drawRoundRect(RectF(76f, 18f, 82f, 20f), 1f, 1f, paint)
        paint.color = alpha(palette.machineEdge, 72)
        canvas.drawRect(9f, 28.5f, 87f, 29.2f, paint)

        // A metal rim and deep tinted glass. The progress layer sits on its edge.
        paint.shader = LinearGradient(24f, 37f, 73f, 96f,
            intArrayOf(mix(palette.machineEdge, Color.WHITE, .5f), palette.machineEdge,
                mix(palette.machineEdge, palette.glass, .6f)), floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(DOOR_X, DOOR_Y, 30.8f, paint)
        paint.shader = null
        val deepGlass = mix(palette.glass, Color.BLACK, if (palette.dark) .57f else .78f)
        paint.color = deepGlass
        canvas.drawCircle(DOOR_X, DOOR_Y, 27.2f, paint)
        paint.shader = RadialGradient(40f, 56f, 38f,
            intArrayOf(mix(deepGlass, palette.accent, .37f), deepGlass,
                mix(deepGlass, Color.BLACK, .48f)), floatArrayOf(0f, .63f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(DOOR_X, DOOR_Y, 24.8f, paint)
        paint.shader = null
        paint.color = alpha(palette.machineEdge, if (palette.dark) 75 else 95)
        for (index in 0 until 24) {
            val angle = Math.toRadians((index * 15).toDouble())
            canvas.drawCircle(DOOR_X + cos(angle).toFloat() * 22.2f,
                DOOR_Y + sin(angle).toFloat() * 22.2f, .52f, paint)
        }
        paint.color = alpha(palette.machineEdge, 190)
        canvas.drawRoundRect(RectF(77f, 61f, 82f, 74f), 2f, 2f, paint)
        paint.color = alpha(Color.WHITE, if (palette.dark) 40 else 145)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .8f
        canvas.drawArc(RectF(19.5f, 37.5f, 76.5f, 94.5f), 205f, 98f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = alpha(palette.machineEdge, 95)
        canvas.drawRoundRect(RectF(15f, 99.5f, 32f, 101f), .75f, .75f, paint)
        canvas.drawRoundRect(RectF(72f, 99f, 79f, 102f), 1.5f, 1.5f, paint)
    }

    /** Only fabric and a fixed glass reflection; the case remains completely static. */
    fun drum(palette: WidgetArtworkPalette, frame: Int): Bitmap = washer { canvas, paint ->
        val glassClip = Path().apply { addCircle(DOOR_X, DOOR_Y, 23.9f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(glassClip)
        canvas.save()
        canvas.rotate(((frame % 3 + 3) % 3) * 120f, DOOR_X, DOOR_Y)
        val towel = Path().apply {
            moveTo(29f, 78f); cubicTo(37f, 72f, 47f, 76f, 56f, 74f)
            cubicTo(64f, 73f, 68f, 81f, 65f, 87f)
            cubicTo(52f, 94f, 35f, 89f, 29f, 84f); close()
        }
        paint.shader = LinearGradient(32f, 73f, 61f, 90f,
            mix(palette.cloth, Color.WHITE, .15f), mix(palette.cloth, palette.glass, .17f), Shader.TileMode.CLAMP)
        canvas.drawPath(towel, paint)
        paint.shader = null
        paint.color = alpha(palette.glass, 65)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .8f
        canvas.drawPath(Path().apply { moveTo(34f, 81f); cubicTo(42f, 83f, 51f, 80f, 61f, 83f) }, paint)
        paint.style = Paint.Style.FILL

        val shirt = Path().apply {
            moveTo(39f, 49f); lineTo(43f, 47f)
            cubicTo(45f, 51f, 51f, 51f, 53f, 47f)
            lineTo(58f, 49.5f); lineTo(65f, 60f); lineTo(58f, 64f)
            lineTo(55f, 59.5f); lineTo(55f, 80f)
            quadTo(47f, 83f, 39f, 79.5f); lineTo(39f, 59.5f)
            lineTo(36f, 64f); lineTo(29f, 60f); close()
        }
        canvas.save()
        canvas.translate(1f, 1.5f)
        paint.color = alpha(Color.BLACK, 35)
        canvas.drawPath(shirt, paint)
        canvas.restore()
        paint.shader = LinearGradient(35f, 47f, 58f, 81f,
            mix(palette.accent, Color.WHITE, if (palette.dark) .42f else .66f),
            mix(palette.accent, Color.WHITE, if (palette.dark) .08f else .26f), Shader.TileMode.CLAMP)
        canvas.drawPath(shirt, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = alpha(palette.accent, palette.dark.then(140, 100))
        canvas.drawPath(Path().apply { moveTo(43f, 49f); quadTo(48f, 54f, 53f, 49f) }, paint)
        canvas.drawPath(Path().apply { moveTo(41f, 76f); quadTo(48f, 79f, 53f, 77f) }, paint)
        paint.color = alpha(Color.WHITE, palette.dark.then(42, 100))
        paint.strokeWidth = .75f
        canvas.drawPath(Path().apply { moveTo(42f, 57f); quadTo(40.5f, 65f, 42f, 73f) }, paint)
        canvas.restore()

        // Reflections remain fixed while the fabric tumbles underneath them.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.7f
        paint.color = alpha(Color.WHITE, palette.dark.then(38, 100))
        canvas.drawArc(RectF(28f, 46f, 68f, 86f), 207f, 59f, false, paint)
        paint.strokeWidth = .65f
        paint.color = alpha(Color.WHITE, palette.dark.then(18, 52))
        canvas.drawArc(RectF(30f, 48f, 66f, 84f), 32f, 55f, false, paint)
        canvas.restore()
    }

    fun refresh(palette: WidgetArtworkPalette): Bitmap = bitmap(64, 64) { canvas, paint ->
        paint.color = alpha(palette.outline, if (palette.dark) 166 else 122)
        canvas.drawCircle(32f, 32f, 30f, paint)
        paint.color = palette.primaryText
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.1f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(15f, 15f, 49f, 49f), 45f, 290f, false, paint)
        paint.style = Paint.Style.FILL
        val angle = Math.toRadians(335.0)
        val endX = 32f + cos(angle).toFloat() * 17f
        val endY = 32f + sin(angle).toFloat() * 17f
        val tangentX = -sin(angle).toFloat()
        val tangentY = cos(angle).toFloat()
        canvas.drawPath(Path().apply {
            moveTo(endX + tangentX * 5.5f, endY + tangentY * 5.5f)
            lineTo(endX - tangentX * 4f - tangentY * 5f, endY - tangentY * 4f + tangentX * 5f)
            lineTo(endX - tangentX * 4f + tangentY * 5f, endY - tangentY * 4f - tangentX * 5f)
            close()
        }, paint)
    }

    /** Static form for idle, unverified and completed tasks. */
    fun dial(palette: WidgetArtworkPalette, progress: Int?): Bitmap = water(palette, progress, 0)

    /** Three phases move only the surface; the actual percentage never changes between frames. */
    fun water(palette: WidgetArtworkPalette, progress: Int?, frame: Int): Bitmap = bitmap(112, 112) { canvas, paint ->
        val value = progress?.coerceIn(0, 100)
        val circle = Path().apply { addCircle(56f, 56f, 48f, Path.Direction.CW) }
        val phase = ((frame % 3 + 3) % 3) * (2.0 * PI / 3.0)
        val level = 104f - (value ?: 0) * .96f
        // Fade the wave at the endpoints, so 0% stays empty and 100% stays full.
        val amplitude = value?.let { 3.2f * sin(PI * it / 100.0).toFloat() } ?: 0f
        val water = Path().apply {
            moveTo(8f, 106f)
            lineTo(8f, level + amplitude * sin(phase).toFloat())
            for (x in 10..104 step 2) {
                val wave = sin((x - 8) / 96.0 * 2.0 * PI + phase).toFloat()
                lineTo(x.toFloat(), level + amplitude * wave)
            }
            lineTo(104f, 106f)
            close()
        }
        canvas.save()
        canvas.clipPath(circle)
        paint.shader = RadialGradient(41f, 26f, 78f,
            mix(palette.surface, Color.WHITE, if (palette.dark) .125f else .56f),
            mix(palette.insetSurface, Color.BLACK, if (palette.dark) .33f else .04f), Shader.TileMode.CLAMP)
        canvas.drawCircle(56f, 56f, 48f, paint)
        paint.shader = null
        if (value != null && value > 0) {
            paint.shader = LinearGradient(8f, level, 104f, 104f,
                mix(palette.accent, Color.WHITE, .12f), mix(palette.accent, Color.BLACK, .19f), Shader.TileMode.CLAMP)
            canvas.drawPath(water, paint)
            paint.shader = null
        }

        val label = value?.let { "$it%" } ?: "—"
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (value == 100) 29f else 35f
        val metrics = paint.fontMetrics
        val baseline = 56f - (metrics.ascent + metrics.descent) / 2f
        // Paint exactly the same glyphs on each side of the actual wave path.
        // Clipping also handles letters crossed by the moving surface.
        canvas.save()
        if (value != null) canvas.clipOutPath(water)
        paint.color = if (value == null) palette.secondaryText else palette.primaryText
        canvas.drawText(label, 56f, baseline, paint)
        canvas.restore()
        if (value != null && value > 0) {
            canvas.save()
            canvas.clipPath(water)
            paint.color = contrastingInk(palette.accent)
            canvas.drawText(label, 56f, baseline, paint)
            canvas.restore()
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.3f
        paint.color = alpha(mix(palette.outline, palette.accent, .18f), if (palette.dark) 210 else 192)
        canvas.drawCircle(56f, 56f, 49f, paint)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.6f
        paint.color = alpha(Color.WHITE, if (palette.dark) 100 else 225)
        canvas.drawArc(RectF(7.5f, 7.5f, 104.5f, 104.5f), 210f, 74f, false, paint)
    }

    fun track(palette: WidgetArtworkPalette, progress: Int?): Bitmap = bitmap(512, 12) { canvas, paint ->
        paint.color = alpha(palette.outline, if (palette.dark) 115 else 90)
        canvas.drawRoundRect(RectF(0f, 0f, 512f, 12f), 6f, 6f, paint)
        val value = progress?.coerceIn(0, 100) ?: return@bitmap
        if (value == 0) return@bitmap
        paint.shader = LinearGradient(0f, 0f, 512f, 0f,
            mix(palette.accent, palette.accentSoft, .24f), palette.accent, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, 512f * value / 100f, 12f), 6f, 6f, paint)
    }

    private fun washer(draw: (Canvas, Paint) -> Unit): Bitmap = bitmap(MACHINE_WIDTH, MACHINE_HEIGHT) { canvas, paint ->
        canvas.scale(MACHINE_WIDTH / 96f, MACHINE_HEIGHT / 112f)
        draw(canvas, paint)
    }

    private fun bitmap(width: Int, height: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.density = Bitmap.DENSITY_NONE
        draw(Canvas(image), Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        return image
    }

    private fun alpha(color: Int, value: Int): Int = (color and 0x00ffffff) or (value.coerceIn(0, 255) shl 24)

    private fun contrastingInk(color: Int): Int {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
        }
        val luminance = .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color))
        return if ((luminance + .05) / .05 >= 1.05 / (luminance + .05)) Color.BLACK else Color.WHITE
    }

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * fraction).roundToInt()
        return Color.argb(channel(Color.alpha(first), Color.alpha(second)), channel(Color.red(first), Color.red(second)),
            channel(Color.green(first), Color.green(second)), channel(Color.blue(first), Color.blue(second)))
    }

    private fun Boolean.then(yes: Float, no: Float): Float = if (this) yes else no
    private fun Boolean.then(yes: Int, no: Int): Int = if (this) yes else no
}
