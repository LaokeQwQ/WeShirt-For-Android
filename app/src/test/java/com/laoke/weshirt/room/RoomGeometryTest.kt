package com.laoke.weshirt.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoomGeometryTest {
    @Test fun eachMachineRetainsItsBusinessSlotThroughRayPicking() {
        RoomLayout.slots.forEach { slot ->
            val origin = slot.point(0f, .53f, 1.2f)
            val ray = Ray(origin, (slot.doorCenter - origin).normalized())
            assertEquals(slot.slot, RoomLayout.pick(ray))
            assertNull(RoomLayout.pick(ray, emptySet()))
        }
        assertEquals(listOf("D1", "1", "2", "3", "4", "D2", "7", "6", "5"), RoomLayout.slots.map { it.slot })
    }

    @Test fun originalRoomMatchesTheWebsOpposingRowsAndDoorFacing() {
        val right = RoomLayout.slots.filter { it.x > 0 }
        val left = RoomLayout.slots.filter { it.x < 0 }
        assertEquals(listOf("D1", "1", "2", "3", "4"), right.map { it.slot })
        assertEquals(listOf("D2", "7", "6", "5"), left.map { it.slot })
        assertEquals(listOf(2.7f, 1.65f, .6f, -.45f, -1.5f), right.map { it.z })
        assertEquals(listOf(2.7f, 1.65f, .6f, -.45f), left.map { it.z })
        RoomLayout.slots.forEach { position ->
            assertEquals(1.42f, abs(position.x), 0f)
            assertTrue("All machine doors must face the same central aisle", abs(position.doorCenter.x) < abs(position.x))
        }
        assertTrue(RoomLayout.isSurveyedLayout(RoomLayout.slots))
    }

    @Test fun repeatedOrUnknownSlotsAcrossFloorsDoNotDropAnyMachine() {
        val machines = (0 until 23).map { index ->
            RoomMachine("machine-$index", if (index % 3 == 0) "unmapped" else "1", "Machine $index", RoomMachineState.IDLE)
        }
        val positions = RoomLayout.forMachines(machines)
        assertEquals(machines.map { it.id }.toSet(), positions.map { it.slot }.toSet())
        assertEquals(machines.size, positions.map { it.x to it.z }.toSet().size)
        assertEquals(positions, RoomLayout.forMachines(machines.reversed()))
        positions.forEach { position ->
            val ray = Ray(Vec3(position.x.toDouble(), 5.0, position.z + .1), Vec3(0.0, -1.0, 0.0))
            assertEquals(position.slot, RoomLayout.pick(ray, positions))
        }
    }

    @Test fun originalNineSlotsKeepTheirOriginalPositionsWithBusinessIds() {
        val machines = RoomLayout.slots.map { RoomMachine("business-${it.slot}", it.slot, it.slot, RoomMachineState.RUNNING) }
        val positions = RoomLayout.forMachines(machines.shuffled(java.util.Random(1)))
        RoomLayout.slots.forEach { original ->
            val position = positions.first { it.slot == "business-${original.slot}" }
            assertEquals(original.x, position.x)
            assertEquals(original.z, position.z)
            assertEquals(original.slot, position.label)
            assertEquals(original.yaw, position.yaw)
        }
    }

    @Test fun parallelAndBehindCameraRaysDoNotSelectMachines() {
        val box = RoomBounds(Vec3(-1.0, -1.0, -1.0), Vec3(1.0, 1.0, 1.0))
        assertNull(box.intersection(Ray(Vec3(2.0, 0.0, 3.0), Vec3(0.0, 0.0, -1.0))))
        assertNull(box.intersection(Ray(Vec3(0.0, 0.0, 3.0), Vec3(0.0, 0.0, 1.0))))
        assertEquals(0.0, box.intersection(Ray(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0)))!!, 1e-9)
    }

    @Test fun screenCenterFollowsTheCameraAndResizingPreservesPicking() {
        val eye = Vec3(4.0, 5.0, 12.0)
        val target = Vec3(0.0, .8, -1.0)
        val expected = (target-eye).normalized()
        for ((width,height) in listOf(400 to 800, 1600 to 800)) {
            val ray = RoomLayout.screenRay(width / 2f, height / 2f, width, height, eye, target, 45.0)
            assertEquals(1.0, ray.direction.dot(expected), 1e-9)
        }
    }

    @Test fun sceneGeometryStaysFiniteIndexedAndWithinAMobileVertexBudget() {
        val meshes = RoomGeometry.environment().values + RoomGeometry.machineBodies().values +
            RoomLayout.slots.map { RoomGeometry.status(it, RoomMachineState.UNKNOWN) } +
            RoomLayout.slots.indices.map(RoomGeometry::garments)
        assertTrue(meshes.sumOf { it.vertexCount } < 120_000)
        meshes.forEach { mesh ->
            assertTrue(mesh.vertexCount > 0)
            assertEquals(mesh.vertexCount * 3, mesh.normals.size)
            assertEquals(mesh.vertexCount * 4, mesh.colors.size)
            assertEquals(0, mesh.indices.size % 3)
            assertTrue(mesh.positions.all(Float::isFinite))
            assertTrue(mesh.normals.all(Float::isFinite))
            assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount })
        }
    }

    @Test fun garmentTriangulationPreservesConcaveLegGap() {
        val builder = MeshBuilder()
        // A U-shaped garment footprint: a triangle fan would incorrectly fill the central gap.
        val outline = listOf(0f to 0f, 3f to 0f, 3f to 3f, 2f to 3f, 2f to 1f, 1f to 1f, 1f to 3f, 0f to 3f)
        builder.polygon(outline, 0f, floatArrayOf(1f, 1f, 1f, 1f))
        val mesh = builder.build()
        var area = 0.0
        for (index in mesh.indices.indices step 3) {
            val a=mesh.indices[index]*3; val b=mesh.indices[index+1]*3; val c=mesh.indices[index+2]*3
            area += abs((mesh.positions[b]-mesh.positions[a])*(mesh.positions[c+1]-mesh.positions[a+1]) -
                (mesh.positions[b+1]-mesh.positions[a+1])*(mesh.positions[c]-mesh.positions[a])) / 2.0
        }
        assertEquals(7.0, area, 1e-6)
    }
}
