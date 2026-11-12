package com.laoke.weshirt.room

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

internal data class RoomCameraPose(val eye: Vec3, val target: Vec3) {
    fun approach(goal: RoomCameraPose, seconds: Double): RoomCameraPose {
        val amount = 1 - exp(-3.2 * seconds.coerceIn(0.0, .08))
        return RoomCameraPose(eye + (goal.eye - eye) * amount, target + (goal.target - target) * amount)
    }
    fun near(goal: RoomCameraPose): Boolean = distanceSquared(eye, goal.eye) < .0001 && distanceSquared(target, goal.target) < .0001
    val distance: Double get() = sqrt(distanceSquared(eye, target))
    private fun distanceSquared(a: Vec3, b: Vec3): Double { val delta = a - b; return delta.dot(delta) }
}

internal enum class RoomFixture { BOILER, WASHER_NOTICE, DRYER_NOTICE, FAUCET, WINDOW_0, WINDOW_1, WINDOW_2, WINDOW_3 }

/** Fixed surveyed fixtures; generic fallback layouts deliberately have no fixture hit targets. */
internal object RoomInteractions {
    private val bounds = linkedMapOf(
        RoomFixture.BOILER to RoomBounds(Vec3(-1.8, 0.0, 3.76), Vec3(-1.25, 1.72, 4.27)),
        RoomFixture.WASHER_NOTICE to RoomBounds(Vec3(-2.04, 1.73, .08), Vec3(-1.91, 2.31, .56)),
        RoomFixture.DRYER_NOTICE to RoomBounds(Vec3(-2.04, 1.73, .60), Vec3(-1.91, 2.31, 1.08)),
        RoomFixture.FAUCET to RoomBounds(Vec3(-2.04, .88, 3.02), Vec3(-1.73, 1.28, 3.28)),
    )

    private fun windowBounds(index: Int, open: Boolean): RoomBounds {
        val center = if (index < 2) -.845 else .845
        val direction = if (index % 2 == 0) 1.0 else -1.0
        val angle = if (open) -direction else 0.0
        val x = center - direction * .63 + direction * .56 * cos(angle) + .035 * sin(angle)
        val z = -2.03 - direction * .56 * sin(angle) + .035 * cos(angle)
        return RoomBounds(Vec3(x - .08, 1.32, z - .08), Vec3(x + .08, 1.68, z + .08))
    }

    fun pick(ray: Ray, openWindows: BooleanArray = BooleanArray(4)): Pair<RoomFixture, Double>? {
        val windows = (0..3).map { index -> RoomFixture.entries[RoomFixture.WINDOW_0.ordinal + index] to windowBounds(index, openWindows.getOrElse(index) { false }) }
        return (bounds.toList() + windows).mapNotNull { (id, box) -> box.intersection(ray)?.let { id to it } }.minByOrNull { it.second }
    }
}
