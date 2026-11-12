package com.laoke.weshirt.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomInteractionsTest {
    @Test fun focusApproachesTheMachineSmoothlyWithoutOvershooting() {
        val origin = RoomCameraPose(Vec3(0.0, 1.7, 5.6), Vec3(0.0, .74, -1.3))
        val slot = RoomLayout.slots.first()
        val goal = RoomCameraPose(slot.point(0f, .95f, 1.55f), slot.point(0f, .53f, .45f))
        var pose = origin
        repeat(240) {
            val previousDistance = (pose.eye - goal.eye).let { delta -> delta.dot(delta) }
            pose = pose.approach(goal, 1.0 / 60)
            val remaining = (pose.eye - goal.eye).let { delta -> delta.dot(delta) }
            assertTrue(remaining <= previousDistance)
            assertTrue(pose.distance > .25)
        }
        assertTrue(pose.near(goal))
        assertEquals(origin, origin.approach(goal, -1.0))
    }

    @Test fun fixturesRespectRayDirectionAndTheActualWindowHandlePosition() {
        assertEquals(RoomFixture.FAUCET, RoomInteractions.pick(Ray(Vec3(-1.5, 1.03, 3.15), Vec3(-1.0, 0.0, 0.0)))?.first)
        assertNull(RoomInteractions.pick(Ray(Vec3(0.0, 1.5, 0.0), Vec3(0.0, 0.0, 1.0))))
        val closedRay = Ray(Vec3(-.915, 1.5, -1.0), Vec3(0.0, 0.0, -1.0))
        assertEquals(RoomFixture.WINDOW_0, RoomInteractions.pick(closedRay)?.first)
        val opened = booleanArrayOf(true, false, false, false)
        assertNull(RoomInteractions.pick(closedRay, opened))
        val movedHandle = Ray(Vec3(-1.202, 1.5, -1.0), Vec3(0.0, 0.0, -1.0))
        assertEquals(RoomFixture.WINDOW_0, RoomInteractions.pick(movedHandle, opened)?.first)
    }

    @Test fun enlargedNoticeBoardsAreClickableAboveTheDosingEquipment() {
        for ((z, notice) in listOf(.32 to RoomFixture.WASHER_NOTICE, .84 to RoomFixture.DRYER_NOTICE)) {
            assertEquals(notice, RoomInteractions.pick(Ray(Vec3(-1.3, 2.02, z), Vec3(-1.0, 0.0, 0.0)))?.first)
            assertNull("The old dosing-height hit box must no longer capture touches", RoomInteractions.pick(Ray(Vec3(-1.3, 1.50, z), Vec3(-1.0, 0.0, 0.0))))
        }
    }
}
