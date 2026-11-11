package com.laoke.weshirt.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class RoomOverviewTest {
    @Test fun narrowPortraitAndLandscapeKeepEveryActualDoorVertexInsideTheOverview() {
        for (aspect in listOf(.35, .45, .6, .84, 1.0, 2.2)) {
            val fov = if (aspect < .85) 72.0 else 58.0
            val overview = RoomOverview.fit(RoomLayout.slots, aspect, fov)
            val back = Vec3(0.0, sin(RoomOverview.PITCH), cos(RoomOverview.PITCH))
            val eye = overview.target + back * overview.distance
            val up = Vec3(0.0, cos(RoomOverview.PITCH), -sin(RoomOverview.PITCH))
            val halfHeight = tan(Math.toRadians(fov) / 2)
            RoomLayout.slots.forEach { slot ->
                val radius = if (slot.dryer) .23f else .205f
                val hinge = slot.point(-radius * .95f, .53f, if (slot.dryer) .36f else .38f)
                val mesh = RoomGeometry.door(slot)
                // Exercise actual native geometry throughout the opening motion,
                // including the rim/handle that the old center-only fit clipped.
                for (step in 0..8) {
                    val angle = slot.yaw - 1.72 * step / 8
                    for (index in mesh.positions.indices step 3) {
                        val x = mesh.positions[index].toDouble()
                        val y = mesh.positions[index + 1].toDouble()
                        val z = mesh.positions[index + 2].toDouble()
                        val point = hinge + Vec3(x * cos(angle) + z * sin(angle), y, -x * sin(angle) + z * cos(angle))
                        val relative = point - eye
                        val depth = -relative.dot(back)
                        assertTrue("Door ${slot.label} must remain in front of the camera ($aspect)", depth > .1)
                        val screenX = relative.x / (depth * halfHeight * aspect)
                        val screenY = relative.dot(up) / (depth * halfHeight)
                        assertTrue("Door ${slot.label} rim/handle was cropped horizontally ($aspect): $screenX", abs(screenX) < .9)
                        assertTrue("Door ${slot.label} rim/handle was cropped vertically ($aspect): $screenY", abs(screenY) < .9)
                    }
                }
            }
        }
    }

    @Test fun allOverviewDoorCentersPickTheirOwnBusinessIdWithoutOcclusion() {
        val slots = RoomLayout.slots.map { it.copy(slot = "business-${it.label}") }
        for (aspect in listOf(.35, .45, .6, 1.0, 2.2)) {
            val fov = if (aspect < .85) 72.0 else 58.0
            val overview = RoomOverview.fit(slots, aspect, fov)
            val eye = overview.target + Vec3(0.0, sin(RoomOverview.PITCH), cos(RoomOverview.PITCH)) * overview.distance
            for (slot in slots) {
                val ray = Ray(eye, (slot.doorCenter - eye).normalized())
                assertEquals("A foreground machine must not intercept ${slot.label} ($aspect)", slot.slot, RoomLayout.pick(ray, slots))
            }
        }
    }
}
