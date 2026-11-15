package com.laoke.weshirt.platform.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Check visible properties of the actual artwork, without reproducing its drawing paths. */
@RunWith(AndroidJUnit4::class)
class LaundryWidgetArtworkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun waterAmountIsMonotonicAndUnknownProgressHasNoInventedLiquid() {
        for (dark in listOf(false, true)) {
            val palette = LaundryWidgetTheme.resolve(context, forceDark = dark)
            val images = listOf(0, 10, 50, 100).map { LaundryWidgetArtwork.water(palette, it, 0) }
            val unknown = LaundryWidgetArtwork.water(palette, null, 0)
            try {
                val filled = images.map { image -> count(image) { x, y, color ->
                    inside(x, y, 44) && near(color, palette.accent, 40)
                } }
                assertTrue("An empty ball must contain no colored liquid: $filled", filled[0] < 10)
                for (index in 1 until filled.size) {
                    assertTrue("Increasing the actual percentage must visibly raise the water amount: $filled",
                        filled[index] > filled[index - 1] + 100)
                }
                assertTrue("A full ball should be mostly liquid", filled.last() > 4_000)
                assertEquals("Unknown progress cannot invent a liquid level", 0,
                    count(unknown) { x, y, color -> inside(x, y, 44) && near(color, palette.accent, 40) })
                for (image in images + unknown) {
                    assertEquals("Water artwork must remain transparent outside its circle", 0,
                        count(image) { x, y, color -> !inside(x, y, 53) && Color.alpha(color) != 0 })
                }
                val half = images[2]
                assertTrue("The percentage must retain readable ink above the surface",
                    count(half) { x, y, color -> x in 26..86 && y in 32..56 && near(color, palette.primaryText, 8) } > 15)
                val reversed = maxOf(
                    count(half) { x, y, color -> x in 26..86 && y in 58..80 && near(color, Color.WHITE, 8) },
                    count(half) { x, y, color -> x in 26..86 && y in 58..80 && near(color, Color.BLACK, 8) })
                assertTrue("The submerged percentage must have contrasting reversed ink", reversed > 15)
            } finally {
                (images + unknown).forEach(Bitmap::recycle)
            }
        }
    }

    @Test fun waveFramesMoveOnlyKnownIntermediateProgressAndBackgroundIsTranslucent() {
        for (dark in listOf(false, true)) {
            val palette = LaundryWidgetTheme.resolve(context, forceDark = dark)
            for (progress in listOf(null, 0, 50, 100)) {
                val frames = (0..2).map { LaundryWidgetArtwork.water(palette, progress, it) }
                try {
                    if (progress == 50) {
                        assertFalse("Running water must visibly move between native frames", frames[0].sameAs(frames[1]))
                        assertFalse(frames[1].sameAs(frames[2]))
                    } else {
                        assertTrue("Unknown, empty and full water levels must remain stationary", frames[0].sameAs(frames[1]))
                        assertTrue(frames[1].sameAs(frames[2]))
                    }
                } finally { frames.forEach(Bitmap::recycle) }
            }
            val background = LaundryWidgetArtwork.background(palette, 320, 140)
            val shell = LaundryWidgetArtwork.shell(palette)
            try {
                assertEquals("The complete background, including its glow and border, must respect 75% opacity", 0,
                    count(background) { _, _, color -> Color.alpha(color) > 192 })
                assertTrue("The background must still have a visible surface",
                    count(background) { _, _, color -> Color.alpha(color) in 180..192 } > background.width * background.height / 2)
                assertTrue("Foreground machines must keep their own full opacity",
                    count(shell) { _, _, color -> Color.alpha(color) == 255 } > 100)
            } finally { background.recycle(); shell.recycle() }
        }
    }

    private fun inside(x: Int, y: Int, radius: Int) = (x - 56) * (x - 56) + (y - 56) * (y - 56) <= radius * radius
    private fun near(first: Int, second: Int, tolerance: Int) = Color.alpha(first) > 240 &&
        abs(Color.red(first) - Color.red(second)) <= tolerance &&
        abs(Color.green(first) - Color.green(second)) <= tolerance &&
        abs(Color.blue(first) - Color.blue(second)) <= tolerance
    private fun count(image: Bitmap, predicate: (Int, Int, Int) -> Boolean): Int {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.indices.count { index -> predicate(index % image.width, index / image.width, pixels[index]) }
    }
}
