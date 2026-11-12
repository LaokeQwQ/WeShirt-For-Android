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
            RoomLayout.slots.indices.map(RoomGeometry::garments) +
            RoomLayout.slots.map(RoomGeometry::door) + RoomLayout.slots.map { RoomGeometry.queueBuckets(it, 3) } +
            RoomLayout.slots.filterNot { it.dryer }.map(RoomGeometry::water) +
            (0..3).map(RoomGeometry::windowSash) + RoomGeometry.faucetWater()
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

    @Test fun dosingBottlesMatchTheWebAndNoticesRemainAboveTheEquipment() {
        fun pointsForColor(meshes: Collection<MeshData>, color: Int): List<List<Float>> = meshes.flatMap { mesh ->
            val expected = RoomGeometry.rgb(color).toList()
            (0 until mesh.vertexCount).filter { mesh.colors.slice(it * 4 until it * 4 + 4) == expected }
                .map { mesh.positions.slice(it * 3 until it * 3 + 3) }
        }
        val washer = RoomGeometry.machineBodies(listOf(RoomSlot("washer", 0f, 0f))).values
        val dryer = RoomGeometry.machineBodies(listOf(RoomSlot("dryer", 0f, 0f, dryer = true))).values
        for (color in listOf(0x3F8FD2, 0x3FAE86, 0x14181F)) {
            val points = pointsForColor(washer, color)
            assertTrue("Washer must retain the dosing box and both distinct bottles", points.isNotEmpty())
            assertTrue("Dosing equipment belongs behind the cabinet", points.all { it[2] < -.43f })
            assertTrue("Dryers do not dispense detergent", pointsForColor(dryer, color).isEmpty())
        }
        val noticeBottom = RoomUtilityLayout.noticeCenterY - RoomUtilityLayout.noticeHeight / 2
        val equipmentTop = RoomUtilityLayout.dosingCenterY + RoomUtilityLayout.dosingHeight / 2
        assertTrue("Notice boards must clear the dosing box, including a viewing gap", noticeBottom - equipmentTop >= .13f)
        assertTrue(RoomUtilityLayout.noticeCenterY + RoomUtilityLayout.noticeHeight / 2 < 2.6f)
    }

    @Test fun queueGeometryIsCappedAndEmptyQueuesAllocateNoVertices() {
        val slot = RoomLayout.slots.first()
        assertEquals(0, RoomGeometry.queueBuckets(slot, 0).vertexCount)
        assertEquals(0, RoomGeometry.queueBuckets(slot, -1).vertexCount)
        assertEquals(RoomGeometry.queueBuckets(slot, 3).vertexCount, RoomGeometry.queueBuckets(slot, Int.MAX_VALUE).vertexCount)
    }

    @Test fun pileHasAllThreeWebGarmentColorsAndNonFlatFolds() {
        val mesh = RoomGeometry.garments(0)
        val colors = mesh.colors.toList().chunked(4).map { it.take(3) }.toSet()
        assertEquals(3, colors.size)
        for (color in listOf(0x537BA6, 0xC68A7E, 0xF0EEE4)) assertTrue(RoomGeometry.rgb(color).take(3) in colors)
        assertTrue(mesh.positions.toList().chunked(3).map { it[2] }.toSet().size > 10)
        assertTrue(mesh.normals.toList().chunked(3).all { normal -> normal.sumOf { (it * it).toDouble() } in .99..1.01 })
    }

    @Test fun faultGeometryAddsTheCrossWithoutChangingTheIdleMachineBody() {
        val slot = RoomLayout.slots.first()
        assertTrue(RoomGeometry.status(slot, RoomMachineState.FAULT).vertexCount > RoomGeometry.status(slot, RoomMachineState.IDLE).vertexCount)
    }

    @Test fun anOpenDoorRevealsARecessedDrumInsteadOfTheSolidCabinetFront() {
        for (dryer in listOf(false, true)) {
            val slot = RoomSlot("machine", 0f, 0f, dryer = dryer)
            val meshes = RoomGeometry.machineBodies(listOf(slot)).values
            val cavity = firstSurface(meshes, Ray(Vec3(0.0, .53, 1.2), Vec3(0.0, 0.0, -1.0)))
            val frontPanel = firstSurface(meshes, Ray(Vec3(.29, .53, 1.2), Vec3(0.0, 0.0, -1.0)))
            assertTrue("The drum must be recessed at least 15 cm behind the front panel", cavity - frontPanel > .15)
            assertEquals("The drum back must close the cavity inside the cabinet", 1.089, cavity, .005)
        }
    }

    @Test fun numberLabelsDoNotCreateGeometryFloatingAboveTheFrontOfAMachine() {
        for (dryer in listOf(false, true)) {
            val height = if (dryer) 1.02f else .95f
            val meshes = RoomGeometry.machineBodies(listOf(RoomSlot("D1", 0f, 0f, dryer = dryer))).values
            val frontPoints = meshes.flatMap { it.positions.toList().chunked(3) }.filter { it[2] > .30f }
            assertTrue(frontPoints.isNotEmpty())
            assertTrue("Readable labels belong to native controls rather than clipping into utility pipes",
                frontPoints.all { it[1] <= height + .05f })
        }
    }

    @Test fun drumBackOverlapsTheTubeWithoutExposingTheWhiteCabinetAtItsEdge() {
        for (dryer in listOf(false, true)) {
            val radius = if (dryer) .23 else .205
            val meshes = RoomGeometry.machineBodies(listOf(RoomSlot("machine", 0f, 0f, dryer = dryer))).values
            val edge = firstSurface(meshes, Ray(Vec3(radius - .007, .53, .28), Vec3(0.0, 0.0, -1.0)))
            assertEquals("The edge must hit the metal drum back rather than the outer cabinet", .169, edge, .005)
        }
    }

    private fun firstSurface(meshes: Collection<MeshData>, ray: Ray): Double {
        return meshes.flatMap { mesh ->
            fun vertex(index: Int) = Vec3(mesh.positions[index * 3].toDouble(), mesh.positions[index * 3 + 1].toDouble(), mesh.positions[index * 3 + 2].toDouble())
            mesh.indices.toList().chunked(3).mapNotNull { (ai, bi, ci) ->
                val a = vertex(ai); val b = vertex(bi); val c = vertex(ci)
                val e1 = b - a; val e2 = c - a
                val p = ray.direction.cross(e2)
                val determinant = e1.dot(p)
                if (abs(determinant) < 1e-9) return@mapNotNull null
                val t = ray.origin - a
                val u = t.dot(p) / determinant
                val q = t.cross(e1)
                val v = ray.direction.dot(q) / determinant
                val distance = e2.dot(q) / determinant
                distance.takeIf { u >= -1e-8 && v >= -1e-8 && u + v <= 1.00000001 && it >= 0 }
            }
        }.minOrNull() ?: error("The ray missed the complete appliance")
    }

    @Test fun reviewUsesTheWebOrangeIndicatorInsteadOfUnknownGrayOrFaultCross() {
        val slot = RoomLayout.slots.first()
        val review = RoomGeometry.status(slot, RoomMachineState.REVIEW)
        val orange = RoomGeometry.rgb(0xF97316).toList()
        assertTrue(review.colors.toList().chunked(4).all { it == orange })
        assertEquals(RoomGeometry.status(slot, RoomMachineState.UNKNOWN).vertexCount, review.vertexCount)
        assertTrue(review.vertexCount < RoomGeometry.status(slot, RoomMachineState.FAULT).vertexCount)
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
