package com.laoke.weshirt.room

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Fits the actual door rims/handles, rather than only their centers, to the viewport. */
internal data class RoomOverview(val target: Vec3, val distance: Double) {
    companion object {
        const val PITCH = .17325
        private const val SAFE_EXTENT = .86

        fun fit(slots: List<RoomSlot>, aspect: Double, fovDegrees: Double): RoomOverview {
            val portrait = aspect < .85
            val targetZ = if (portrait) -1.3 else -.8
            val minimumDistance = if (portrait) 5.685 else 5.12
            if (slots.isEmpty()) return RoomOverview(Vec3(0.0, .53, targetZ), minimumDistance)
            val corners = slots.flatMap { slot ->
                val radius = if (slot.dryer) .23f else .205f
                val hinge = slot.point(-radius * .95f, .53f, if (slot.dryer) .36f else .38f)
                // The ring has a .038 m tube, and the handle extends another .044 m.
                // Include both open and closed doors; opening toward the aisle must
                // not change an otherwise visible machine into a clipped target.
                buildList {
                    for (angle in listOf(0.0, -Math.PI / 2, -1.72)) {
                        val yaw = slot.yaw + angle
                        for (x in listOf(-.05, radius * 1.95 + .05))
                            for (y in listOf(-radius - .04, radius + .04))
                                for (z in listOf(-.04, .05))
                                    add(hinge + Vec3(x * cos(yaw) + z * sin(yaw), y, -x * sin(yaw) + z * cos(yaw)))
                    }
                }
            }
            val vertical = tan(Math.toRadians(fovDegrees) / 2)
            val horizontal = vertical * aspect.coerceAtLeast(.01)
            val back = Vec3(0.0, sin(PITCH), cos(PITCH))
            val up = Vec3(0.0, cos(PITCH), -sin(PITCH))

            fun fitAt(height: Double): Pair<RoomOverview, Double> {
                val target = Vec3(0.0, height, targetZ)
                var distance = minimumDistance
                for (point in corners) {
                    val relative = point - target
                    val along = relative.dot(back)
                    distance = max(distance, along + abs(relative.x) / (horizontal * SAFE_EXTENT))
                    distance = max(distance, along + abs(relative.dot(up)) / (vertical * SAFE_EXTENT))
                }
                var low = Double.POSITIVE_INFINITY
                var high = Double.NEGATIVE_INFINITY
                for (point in corners) {
                    val relative = point - target
                    val y = relative.dot(up) / (vertical * (distance - relative.dot(back)))
                    low = minOf(low, y)
                    high = maxOf(high, y)
                }
                return RoomOverview(target, distance) to (low + high) / 2
            }

            // Center the machine rows vertically too: a fixed high look-at point
            // wastes tall-screen space on the ceiling while hiding the near doors.
            var low = -1.0
            var high = 2.0
            repeat(24) {
                val middle = (low + high) / 2
                if (fitAt(middle).second > 0) low = middle else high = middle
            }
            return fitAt((low + high) / 2).first
        }
    }
}
