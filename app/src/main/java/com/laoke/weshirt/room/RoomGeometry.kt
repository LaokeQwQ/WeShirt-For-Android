package com.laoke.weshirt.room

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun normalized(): Vec3 {
        val length = sqrt(dot(this))
        return if (length > 1e-9) this * (1.0 / length) else Vec3(0.0, 0.0, -1.0)
    }
}

internal data class Ray(val origin: Vec3, val direction: Vec3)
internal data class RoomBounds(val minimum: Vec3, val maximum: Vec3) {
    fun intersection(ray: Ray): Double? {
        var near = 0.0
        var far = Double.POSITIVE_INFINITY
        val origins = doubleArrayOf(ray.origin.x, ray.origin.y, ray.origin.z)
        val directions = doubleArrayOf(ray.direction.x, ray.direction.y, ray.direction.z)
        val mins = doubleArrayOf(minimum.x, minimum.y, minimum.z)
        val maxs = doubleArrayOf(maximum.x, maximum.y, maximum.z)
        for (axis in 0..2) {
            if (abs(directions[axis]) < 1e-9) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return null
            } else {
                val a = (mins[axis] - origins[axis]) / directions[axis]
                val b = (maxs[axis] - origins[axis]) / directions[axis]
                near = max(near, min(a, b))
                far = min(far, max(a, b))
                if (near > far) return null
            }
        }
        return near.takeIf { far >= 0 }
    }
}

internal data class RoomSlot(
    val slot: String, val x: Float, val z: Float = -1.0f,
    val label: String = slot, val dryer: Boolean = label.startsWith("D"), val yaw: Float = 0f,
) {
    fun point(localX: Float, localY: Float, localZ: Float): Vec3 = Vec3(
        (x + localX * cos(yaw) + localZ * sin(yaw)).toDouble(), localY.toDouble(),
        (z - localX * sin(yaw) + localZ * cos(yaw)).toDouble(),
    )
    val doorCenter get() = point(0f, .53f, .39f)
    fun intersection(ray: Ray): Double? {
        // Intersect in the machine's local coordinates, so facing doors and their
        // hit boxes stay aligned on both sides of the same central aisle.
        val relative = ray.origin - Vec3(x.toDouble(), 0.0, z.toDouble())
        fun local(v: Vec3) = Vec3(v.x * cos(yaw) - v.z * sin(yaw), v.y, v.x * sin(yaw) + v.z * cos(yaw))
        return RoomBounds(Vec3(-.35, 0.0, -.33), Vec3(.35, 1.11, .43))
            .intersection(Ray(local(relative), local(ray.direction)))
    }
}

internal object RoomLayout {
    // Same surveyed layout and numbering as the Web Room3D.vue SLOTS. Looking
    // inward from the door: right D1,1–4; left boiler,D2,7,6,5.
    val slots = listOf(
        RoomSlot("D1", 1.42f, 2.7f, yaw = (-Math.PI / 2).toFloat()),
        RoomSlot("1", 1.42f, 1.65f, yaw = (-Math.PI / 2).toFloat()),
        RoomSlot("2", 1.42f, .6f, yaw = (-Math.PI / 2).toFloat()),
        RoomSlot("3", 1.42f, -.45f, yaw = (-Math.PI / 2).toFloat()),
        RoomSlot("4", 1.42f, -1.5f, yaw = (-Math.PI / 2).toFloat()),
        RoomSlot("D2", -1.42f, 2.7f, yaw = (Math.PI / 2).toFloat()),
        RoomSlot("7", -1.42f, 1.65f, yaw = (Math.PI / 2).toFloat()),
        RoomSlot("6", -1.42f, .6f, yaw = (Math.PI / 2).toFloat()),
        RoomSlot("5", -1.42f, -.45f, yaw = (Math.PI / 2).toFloat()),
    )

    fun isSurveyedLayout(positions: List<RoomSlot>): Boolean = positions.all { position ->
        slots.any { it.label == position.label && it.x == position.x && it.z == position.z && it.yaw == position.yaw }
    }

    fun normalizeSlot(value: String): String = value.trim().uppercase().removePrefix("#")
        .replace("号机", "").replace("号", "").removePrefix("W")

    /** Business ids, not upstream display slots, uniquely identify a machine across floors. */
    fun forMachines(machines: List<RoomMachine>): List<RoomSlot> {
        val unique = machines.distinctBy { it.id }
        val namedSlots = unique.map { normalizeSlot(it.slot) }
        val known = namedSlots.distinct().size == unique.size && namedSlots.all { name -> slots.any { it.slot == name } }
        if (known) return unique.map { machine ->
            val position = slots.first { it.slot == normalizeSlot(machine.slot) }
            position.copy(slot = machine.id, label = position.slot, dryer = machine.kind == "dryer" || position.dryer)
        }.sortedBy { position -> slots.indexOfFirst { it.slot == position.label } }
        val sorted = unique.sortedWith(compareBy<RoomMachine> { normalizeSlot(it.slot).toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { normalizeSlot(it.slot) }.thenBy { it.id })
        val columns = min(7, max(1, kotlin.math.ceil(sqrt(sorted.size * 1.8)).toInt()))
        return sorted.mapIndexed { index, machine ->
            RoomSlot(machine.id, (index % columns - (columns - 1) / 2f) * 1.28f,
                -1f + (index / columns) * 1.85f, normalizeSlot(machine.slot).ifBlank { (index + 1).toString() }, machine.kind == "dryer")
        }
    }

    fun pick(ray: Ray, availableSlots: Set<String> = slots.map { it.slot }.toSet()): String? =
        pick(ray, slots.filter { it.slot in availableSlots })

    fun pick(ray: Ray, positions: List<RoomSlot>): String? =
        positions.asSequence()
            .mapNotNull { slot -> slot.intersection(ray)?.let { slot.slot to it } }
            .minByOrNull { it.second }?.first

    fun screenRay(x: Float, y: Float, width: Int, height: Int, eye: Vec3, target: Vec3, fovDegrees: Double): Ray {
        val forward = (target - eye).normalized()
        val right = forward.cross(Vec3(0.0, 1.0, 0.0)).normalized()
        val up = right.cross(forward).normalized()
        val halfHeight = tan(Math.toRadians(fovDegrees) / 2)
        val aspect = width.toDouble() / max(height, 1)
        val nx = (2 * x / max(width, 1) - 1) * halfHeight * aspect
        val ny = (1 - 2 * y / max(height, 1)) * halfHeight
        return Ray(eye, (forward + right * nx + up * ny).normalized())
    }
}

internal enum class RoomMaterial { PAINT, ENAMEL, METAL, CLOTH, GLOW }
internal data class MeshData(val positions: FloatArray, val normals: FloatArray, val colors: FloatArray, val indices: IntArray) {
    val vertexCount get() = positions.size / 3
}

/** Shared geometry is merged by material. No network models or per-frame mesh allocations. */
internal object RoomGeometry {
    fun environment(extraDepth: Float = 0f): Map<RoomMaterial, MeshData> {
        val paint = MeshBuilder()
        val metal = MeshBuilder()
        val glow = MeshBuilder()
        val wall = rgb(0xBCAC88)
        val tile = rgb(0xAC966E)
        val frame = rgb(0x101418)
        val pipe = rgb(0x8A98A5)
        val end = 4.42f + extraDepth
        val depth = 6.52f + extraDepth
        val center = 1.16f + extraDepth / 2
        paint.box(0f, -.055f, center, 4.1f, .11f, depth, tile)
        for (i in -4..4) paint.box(i * .5f, .004f, center, .008f, .005f, depth, rgb(0x786949))
        for (i in 0..(depth / .5f).toInt()) paint.box(0f, .005f, -2.1f + i * .5f, 4.1f, .005f, .008f, rgb(0x786949))
        // Tile walls with a cutaway toward the entrance. The aisle remains visible
        // from all allowed camera angles; no transparent full-room overdraw.
        for (side in listOf(-1f, 1f)) {
            val x = side * 2.05
            val points = listOf(Vec3(x, 0.0, -2.1), Vec3(x, 0.0, end.toDouble()),
                Vec3(x, 2.6, end.toDouble()), Vec3(x, 2.6, -2.1))
            val ordered = if (side < 0) points.reversed() else points
            paint.face(ordered[0], ordered[1], ordered[2], ordered[3], wall)
            for (i in 0..(depth / .3f).toInt()) paint.box(side * 2.011f, 1.3f, -2.1f + i * .3f, .008f, 2.6f, .006f, rgb(0x8C8068))
            for (row in 1..8) paint.box(side * 2.009f, row * .3f, center, .008f, .006f, depth, rgb(0x8C8068))
            metal.box(side * 1.97f, .16f, center, .025f, .06f, depth, pipe)
            metal.box(side * 1.97f, 1.2f, .55f, .025f, .035f, 5.15f, pipe)
        }
        // Ceiling and the two warm-white grid lamps use exactly the Web positions.
        paint.face(Vec3(-2.05, 2.7, -2.1), Vec3(2.05, 2.7, -2.1),
            Vec3(2.05, 2.7, end.toDouble()), Vec3(-2.05, 2.7, end.toDouble()), rgb(0x4A4E56))
        for (x in listOf(-.85f, .85f)) {
            metal.box(x, 2.63f, .6f, .36f, .06f, 1.3f, rgb(0x3A4048))
            glow.box(x, 2.595f, .6f, .29f, .02f, 1.2f, rgb(0xFFF4E2))
        }
        // Rear wall assembled around two 1.35 × 1.3m windows, not a row of
        // arbitrary display windows. The recess and handles remain real geometry.
        paint.box(0f, .425f, -2.1f, 4.1f, .85f, .07f, wall)
        paint.box(0f, 2.375f, -2.1f, 4.1f, .45f, .07f, wall)
        paint.box(0f, 1.5f, -2.1f, .34f, 1.3f, .07f, wall)
        for (side in listOf(-1f, 1f)) {
            val x = side * .845f
            paint.box(side * 1.785f, 1.5f, -2.1f, .53f, 1.3f, .07f, wall)
            glow.box(x, 1.5f, -2.115f, 1.29f, 1.22f, .025f, rgb(0xA7C7DA))
            for (dy in listOf(-.65f, .65f)) metal.box(x, 1.5f + dy, -2.032f, 1.35f, .06f, .06f, frame)
            for (dx in listOf(-.645f, .645f)) metal.box(x + dx, 1.5f, -2.03f, .045f, 1.3f, .06f, frame)
        }
        paint.box(0f, .79f, -1.99f, 3.28f, .06f, .14f, rgb(0x726452))
        // Open door, entrance piers, breaker and the opposite hall doorway.
        for (side in listOf(-1f, 1f)) {
            paint.box(side * 1.6f, 1.3f, end - .07f, .9f, 2.6f, .10f, wall)
            metal.box(side * .84f, 1.2f, end - .07f, .06f, 2.4f, .08f, rgb(0xB4A697))
        }
        metal.box(0f, 2.36f, end - .07f, 1.74f, .08f, .1f, rgb(0xB4A697))
        paint.box(-1.38f, 1.15f, end + .40f, 1.12f, 2.26f, .06f, rgb(0x806044))
        // One-sided hall backdrop stays visible looking outward, never from
        // behind the doorway camera on a narrow portrait display.
        paint.face(Vec3(1.05, 0.0, end + .95), Vec3(-1.05, 0.0, end + .95),
            Vec3(-1.05, 2.6, end + .95), Vec3(1.05, 2.6, end + .95), rgb(0xD9D6CF))
        paint.face(Vec3(.45, 0.0, end + .91), Vec3(-.45, 0.0, end + .91),
            Vec3(-.45, 2.2, end + .91), Vec3(.45, 2.2, end + .91), rgb(0x775A3D))
        glow.box(0f, .011f, end - .12f, 2.6f, .012f, .08f, rgb(0x22C55E))
        paint.box(1.62f, 1.5f, 3.95f, .56f, .24f, .1f, rgb(0xB4BAC2))
        for (i in 0..4) metal.box(1.42f + i * .1f, 1.5f, 3.88f, .055f, .10f, .035f, rgb(0x252C32))
        // Water boiler by the left entrance, display/card reader, dispensing recess.
        paint.box(-1.55f, .76f, 4.02f, .42f, 1.52f, .38f, rgb(0x252B32))
        glow.box(-1.33f, 1.42f, 4.02f, .02f, .13f, .23f, rgb(0x84C1D7))
        paint.box(-1.325f, 1.2f, 4.02f, .025f, .16f, .15f, rgb(0x536270))
        paint.box(-1.325f, .69f, 4.02f, .025f, .42f, .29f, rgb(0x11171D))
        metal.box(-1.295f, .89f, 4.02f, .12f, .027f, .035f, rgb(0xB7BEC5))
        metal.box(-1.29f, .43f, 4.02f, .14f, .035f, .32f, rgb(0x9DA6B0))
        metal.box(-1.96f, 1.03f, 3.15f, .12f, .035f, .045f, rgb(0xAAB7C0))
        // Small corner sink and waste bin complete the same entrance facilities.
        paint.box(-1.77f, .73f, -1.43f, .45f, .09f, .48f, rgb(0xD8DCDD))
        metal.box(-1.89f, .91f, -1.43f, .025f, .27f, .025f, pipe)
        metal.box(-1.82f, 1.04f, -1.43f, .17f, .025f, .025f, pipe)
        metal.cylinder(-1.02f, .17f, 3.66f, .13f, .34f, rgb(0x37414A), axisZ = false, segments = 16)
        // Long notice text belongs to the native detail sheet, not a 3D texture.
        for (z in listOf(.39f, .77f)) {
            metal.box(-1.99f, 1.55f, z, .035f, .43f, .33f, rgb(0x3B4148))
            paint.box(-1.964f, 1.55f, z, .012f, .37f, .27f, rgb(0xE7E5DC))
            for (line in 0..4) metal.box(-1.952f, 1.66f - line * .053f, z, .008f, .008f, .20f - line * .018f, rgb(0x66717A))
        }
        for (x in listOf(1.36f, 1.88f)) for (z in listOf(3.56f, 3.88f)) metal.box(x, .39f, z, .025f, .78f, .025f, pipe)
        for (y in listOf(.13f, .39f, .65f)) for (i in 0..4) metal.box(1.62f, y, 3.56f + i * .08f, .55f, .016f, .018f, pipe)
        for (i in 0..3) paint.ellipsoid(1.44f + (i % 2) * .24f, .19f + (i / 2) * .26f, 3.70f, .085f, .045f, .14f, rgb(if (i % 2 == 0) 0xC9C5B8 else 0x4C5966))
        metal.box(-1.87f, .62f, -1.35f, .025f, 1.22f, .025f, rgb(0x9BA7A8))
        paint.box(-1.78f, .035f, -1.35f, .32f, .05f, .15f, rgb(0x6C7976))
        return mapOf(RoomMaterial.PAINT to paint.build(), RoomMaterial.METAL to metal.build(), RoomMaterial.GLOW to glow.build())
    }

    fun machineBodies(positions: List<RoomSlot> = RoomLayout.slots): Map<RoomMaterial, MeshData> {
        val paint = MeshBuilder()
        val metal = MeshBuilder()
        for (slot in positions) {
            val body = MeshBuilder()
            val detail = MeshBuilder()
            val height = if (slot.dryer) 1.02f else .95f
            val radius = if (slot.dryer) .23f else .205f
            val enamel = rgb(if (slot.dryer) 0xE1E4E3 else 0xF0F2F0)
            // The cabinet is genuinely hollow at the door. A solid white box
            // behind the old ring made open machines look like a flat appliance icon.
            body.beveledBox(0f, height / 2 + .025f, 0f, .68f, height - .05f, .65f, .018f, enamel, front = false)
            body.beveledBox(0f, height + .006f, -.005f, .69f, .045f, .67f, .012f, rgb(0xF5F6F3))
            body.panelWithHole(-.315f, .315f, .17f, height - .155f, 0f, .53f, radius + .026f, .327f, enamel)
            body.beveledBox(0f, .105f, .326f, .63f, .13f, .035f, .008f, rgb(0xE1E5E4))
            body.beveledBox(0f, height - .102f, .327f, .635f, .154f, .045f, .009f, rgb(if (slot.dryer) 0xBFC8CB else 0xE5E9E8))

            // Recognisable controls: detergent drawer, rotary program dial,
            // recessed display and a separate start button. No invented countdown.
            body.beveledBox(-.218f, height - .102f, .356f, .155f, .066f, .019f, .005f, rgb(0xC9D0D1))
            body.box(-.218f, height - .105f, .369f, .123f, .004f, .006f, rgb(0x74838B))
            detail.cylinder(-.045f, height - .102f, .364f, .044f, .031f, rgb(0xAEBAC1), segments = 24)
            body.cylinder(-.045f, height - .102f, .383f, .034f, .007f, rgb(0xE4E8E7), segments = 24)
            body.box(-.045f, height - .077f, .389f, .004f, .014f, .006f, rgb(0x44515C))
            body.beveledBox(.123f, height - .101f, .356f, .191f, .072f, .017f, .005f, rgb(0x182A34))
            body.cylinder(.267f, height - .101f, .361f, .021f, .018f, rgb(0x697C85), segments = 16)

            // Deep stainless drum, rubber gasket, and perforations remain behind
            // the opening, so moving clothes are inside the cabinet instead of on it.
            // Overlap the tube's inner edge: a smaller cap leaked the white rear
            // cabinet through a bright crescent around the bottom of the drum.
            detail.cylinder(0f, .53f, .105f, radius - .004f, .012f, rgb(0x536570), segments = 40)
            detail.tube(0f, .53f, .21f, radius - .006f, .20f, rgb(0x8A9CA4), segments = 40)
            detail.annulus(0f, .53f, .319f, radius - .008f, radius + .025f, rgb(0x26363D), segments = 40)
            detail.annulus(0f, .53f, .334f, radius + .004f, radius + .019f, rgb(0x53656D), segments = 40)
            for (ring in 0..1) for (i in 0 until 16) {
                val a = (i + ring * .5) * Math.PI * 2 / 16
                val r = radius * if (ring == 0) .66 else .86
                detail.cylinder((cos(a) * r).toFloat(), .53f + (sin(a) * r).toFloat(), .114f,
                    .005f, .003f, rgb(0x253D48), segments = 5)
            }
            // Feet, inset plinth and washer filter cap / dryer lint ventilation.
            body.box(0f, .037f, .015f, .59f, .035f, .57f, rgb(0x26363E))
            for (dx in listOf(-.26f, .26f)) for (dz in listOf(-.22f, .22f))
                detail.cylinder(dx, .026f, dz, .025f, .046f, rgb(0x36434C), axisZ = false, segments = 8)
            if (slot.dryer) {
                for (row in 0..3) for (column in 0..7)
                    body.box(-.245f + column * .07f, .075f + row * .022f, .349f, .047f, .005f, .006f, rgb(0x687C85))
            } else {
                body.cylinder(.231f, .105f, .349f, .037f, .008f, rgb(0xAFBEC3), segments = 24)
                body.cylinder(.231f, .105f, .356f, .028f, .006f, enamel, segments = 24)
                body.box(-.078f, .105f, .35f, .28f, .005f, .005f, rgb(0xBCC7C9))
            }
            // Machine numbers are native screen-space controls. World-space
            // seven-segment letters clipped into these pipes and vanished at oblique angles.
            // The Web room places dosing equipment and utility lines behind each
            // washer. Keep these static details batched in the same two materials.
            if (!slot.dryer) {
                body.box(0f, 1.52f, -.43f, .36f, .28f, .12f, rgb(0xD5DEE5))
                body.box(-.07f, 1.30f, -.43f, .10f, .15f, .085f, rgb(0xB0CADB))
                body.box(.07f, 1.30f, -.43f, .10f, .15f, .085f, rgb(0xD0C8E0))
                detail.box(0f, 1.15f, -.47f, .018f, .55f, .018f, rgb(0x8B99A5))
                detail.box(.23f, 1.44f, -.45f, .09f, .12f, .04f, rgb(0xE6E9EA))
            } else {
                for (i in 0..4) detail.box(-.14f + i * .07f, height + .048f, -.13f, .025f, .012f, .20f, rgb(0x6D7A83))
                detail.box(0f, .55f, -.42f, .07f, .7f, .07f, rgb(0x8694A1))
            }
            paint.append(body.build(), slot)
            metal.append(detail.build(), slot)
        }
        return mapOf(RoomMaterial.ENAMEL to paint.build(), RoomMaterial.METAL to metal.build())
    }

    fun status(slot: RoomSlot, state: RoomMachineState): MeshData {
        val color = when (state) {
            RoomMachineState.IDLE -> 0x22C55E
            RoomMachineState.RUNNING -> 0xF59E0B
            RoomMachineState.FINISHING -> 0xE2BD68
            RoomMachineState.REVIEW, RoomMachineState.AWAITING_PICKUP -> 0xF97316
            RoomMachineState.FAULT -> 0xEF4444
            RoomMachineState.UNKNOWN -> 0x6B7280
        }
        val height = if (slot.dryer) 1.02f else .95f
        val local = MeshBuilder().apply {
            box(.123f, height - .101f, .369f, .133f, .019f, .006f, rgb(color))
            cylinder(.267f, height - .101f, .374f, .010f, .006f, rgb(color), segments = 10)
            if (state == RoomMachineState.FAULT) for (angle in listOf(-.7854f, .7854f)) {
                polygon(listOf(-.14f to -.015f, .14f to -.015f, .14f to .015f, -.14f to .015f)
                    .map { (x, y) -> (x * cos(angle) - y * sin(angle)) to (.53f + x * sin(angle) + y * cos(angle)) }, .402f, rgb(color))
            }
        }.build()
        return MeshBuilder().apply { append(local, slot) }.build()
    }

    /** Same three outlines, folds, colors and pile transforms as Web room/garments.js. */
    @Suppress("UNUSED_PARAMETER")
    fun garments(slotIndex: Int): MeshData {
        val shirt = listOf(-.27f to .53f, -.58f to .42f, -.79f to .12f, -.55f to -.04f,
            -.4f to .16f, -.4f to -.53f, .4f to -.53f, .4f to .16f, .55f to -.04f,
            .79f to .12f, .58f to .42f, .27f to .53f, .18f to .35f, 0f to .29f, -.18f to .35f)
        val trousers = listOf(-.39f to .53f, -.44f to -.5f, -.1f to -.54f, 0f to .1f,
            .1f to -.54f, .44f to -.5f, .39f to .53f)
        val pile = MeshBuilder()
        fun piece(outline: List<Pair<Float, Float>>, color: Int, x: Float, y: Float, z: Float,
                  sx: Float, sy: Float, sz: Float, angle: Float) {
            val flat = MeshBuilder().apply { polygon(outline, 0f, rgb(color)) }.build()
            fun point(index: Int): Vec3 {
                val ox = flat.positions[index * 3]; val oy = flat.positions[index * 3 + 1]
                val px = ox * sx; val py = oy * sy
                return Vec3((x + px * cos(angle) - py * sin(angle)).toDouble(),
                    (y + px * sin(angle) + py * cos(angle)).toDouble(),
                    (z + (sin(ox * 5) * .035f + sin(oy * 4) * .02f) * sz).toDouble())
            }
            for (i in flat.indices.indices step 3) pile.triangle(point(flat.indices[i]), point(flat.indices[i + 1]), point(flat.indices[i + 2]), rgb(color))
        }
        piece(trousers, 0x537BA6, -.31f, -.31f, -.035f, .7f, .85f, .65f, -.35f)
        piece(shirt, 0xC68A7E, .4f, -.1f, -.018f, .6f, .7f, .7f, .45f)
        piece(shirt, 0xF0EEE4, 0f, .12f, .045f, 1.05f, 1.05f, .75f, -.12f)
        return pile.build()
    }

    /** Vertices relative to the hinge; opening changes only a native transform. */
    fun door(slot: RoomSlot): MeshData = MeshBuilder().apply {
        val radius = if (slot.dryer) .23f else .205f
        val x = radius * .95f
        torus(x, 0f, 0f, radius + .012f, .027f, rgb(0xC0CBD1))
        annulus(x, 0f, .028f, radius - .028f, radius + .006f, rgb(0x233740), segments = 40)
        torusArc(x, 0f, .031f, radius - .025f, .009f, 0f, (Math.PI * 2).toFloat(), rgb(0x596E79), segments = 40)
        // Curved highlights suggest the glass without an opaque centre or a
        // full transparent shell that would obscure clothes and add layered overdraw.
        torusArc(x, 0f, .040f, radius * .70f, .007f, .48f, 1.28f, rgb(0xACC5CF), segments = 12)
        torusArc(x, 0f, .043f, radius * .64f, .0035f, .64f, 1.12f, rgb(0xE3EFF1), segments = 10)
        beveledBox(x + radius + .012f, 0f, .024f, .043f, .112f, .052f, .008f, rgb(0x354C59))
        box(-.017f, -.052f, -.006f, .028f, .055f, .049f, rgb(0x8C9EA6))
        box(-.017f, .052f, -.006f, .028f, .055f, .049f, rgb(0x8C9EA6))
    }.build()

    fun queueBuckets(slot: RoomSlot, count: Int): MeshData = MeshBuilder().apply {
        for (i in 0 until count.coerceIn(0, 3)) {
            val x = -.28f + i * .24f
            cylinder(x, .055f, .62f, .085f, .11f, rgb(listOf(0x3F8FD2, 0xD26A8A, 0x4FAE86)[i]), axisZ = false, segments = 14)
            cylinder(x, .11f, .62f, .087f, .008f, rgb(0xE8EDF3), axisZ = false, segments = 20)
            ellipsoid(x, .12f, .62f, .067f, .028f, .08f, rgb(if (i % 2 == 0) 0xE6E2D9 else 0x859CAE))
        }
    }.build().let { local -> MeshBuilder().apply { append(local, slot) }.build() }

    fun water(slot: RoomSlot): MeshData = MeshBuilder().apply {
        val radius = .205f * .82f
        val outline = (0..20).map { i ->
            val angle = Math.PI + i * Math.PI / 20
            (cos(angle) * radius).toFloat() to (.53f + sin(angle).toFloat() * radius)
        }
        polygon(outline, .29f, rgb(0x4E8EA4))
    }.build().let { local -> MeshBuilder().apply { append(local, slot) }.build() }

    fun windowSash(index: Int): MeshData = MeshBuilder().apply {
        val direction = if (index % 2 == 0) 1f else -1f
        val x = direction * .315f
        for (y in listOf(-.61f, .61f)) box(x, y, 0f, .63f, .035f, .035f, rgb(0x272F36))
        for (dx in listOf(0f, direction * .63f)) box(dx, 0f, 0f, .028f, 1.22f, .035f, rgb(0x272F36))
        box(direction * .56f, 0f, .035f, .025f, .15f, .03f, rgb(0xD5DEE8))
        box(x, .15f, -.012f, .58f, .012f, .012f, rgb(0xA7C7DA))
    }.build()

    fun faucetWater(): MeshData = MeshBuilder().apply {
        cylinder(-1.89f, .48f, 3.15f, .012f, .92f, rgb(0x98CAD9), axisZ = false, segments = 8)
        cylinder(-1.89f, .015f, 3.15f, .075f, .009f, rgb(0x79AFBC), axisZ = false, segments = 16)
    }.build()

    internal fun rgb(color: Int) = floatArrayOf(
        ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f, 1f,
    )
}

internal class MeshBuilder {
    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val colors = ArrayList<Float>()
    private val indices = ArrayList<Int>()

    private fun vertex(point: Vec3, normal: Vec3, color: FloatArray): Int {
        val index = positions.size / 3
        positions.add(point.x.toFloat()); positions.add(point.y.toFloat()); positions.add(point.z.toFloat())
        normals.add(normal.x.toFloat()); normals.add(normal.y.toFloat()); normals.add(normal.z.toFloat())
        color.forEach(colors::add)
        return index
    }

    private fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: FloatArray) {
        val normal = (b - a).cross(c - a).normalized()
        val base = vertex(a, normal, color)
        vertex(b, normal, color); vertex(c, normal, color); vertex(d, normal, color)
        indices.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
    }

    fun face(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: FloatArray) = quad(a, b, c, d, color)

    fun box(x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float, color: FloatArray) {
        val a = Vec3((x-width/2).toDouble(), (y-height/2).toDouble(), (z-depth/2).toDouble())
        val b = Vec3((x+width/2).toDouble(), (y+height/2).toDouble(), (z+depth/2).toDouble())
        quad(Vec3(a.x,a.y,b.z),Vec3(b.x,a.y,b.z),Vec3(b.x,b.y,b.z),Vec3(a.x,b.y,b.z),color)
        quad(Vec3(b.x,a.y,a.z),Vec3(a.x,a.y,a.z),Vec3(a.x,b.y,a.z),Vec3(b.x,b.y,a.z),color)
        quad(Vec3(a.x,b.y,b.z),Vec3(b.x,b.y,b.z),Vec3(b.x,b.y,a.z),Vec3(a.x,b.y,a.z),color)
        quad(Vec3(a.x,a.y,a.z),Vec3(b.x,a.y,a.z),Vec3(b.x,a.y,b.z),Vec3(a.x,a.y,b.z),color)
        quad(Vec3(b.x,a.y,b.z),Vec3(b.x,a.y,a.z),Vec3(b.x,b.y,a.z),Vec3(b.x,b.y,b.z),color)
        quad(Vec3(a.x,a.y,a.z),Vec3(a.x,a.y,b.z),Vec3(a.x,b.y,b.z),Vec3(a.x,b.y,a.z),color)
    }

    /** Shallow rounded-corner chamfers give the cabinet a manufactured edge. */
    fun beveledBox(x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float,
                   bevel: Float, color: FloatArray, front: Boolean = true) {
        val edge = min(bevel, min(width, height) / 4).coerceAtLeast(.001f)
        fun outline(inset: Float): List<Pair<Double, Double>> {
            val halfWidth = width / 2.0 - inset
            val halfHeight = height / 2.0 - inset
            val radius = (edge - inset).coerceAtLeast(.001f).toDouble()
            return (0..3).flatMap { corner ->
                val angle = corner * Math.PI / 2
                val cx = if (corner == 0 || corner == 3) halfWidth - radius else -halfWidth + radius
                val cy = if (corner < 2) halfHeight - radius else -halfHeight + radius
                (0..2).map { segment ->
                    val a = angle + segment * Math.PI / 4
                    (x + cx + cos(a) * radius) to (y + cy + sin(a) * radius)
                }
            }
        }
        val outer = outline(0f)
        val inner = outline(edge * .42f)
        val back = z - depth / 2.0
        val shoulder = z + depth / 2.0 - min(edge * .42f, depth * .3f)
        val nose = z + depth / 2.0
        fun point(p: Pair<Double, Double>, at: Double) = Vec3(p.first, p.second, at)
        for (index in outer.indices) {
            val next = (index + 1) % outer.size
            quad(point(outer[index], back), point(outer[next], back), point(outer[next], shoulder), point(outer[index], shoulder), color)
            quad(point(outer[index], shoulder), point(outer[next], shoulder), point(inner[next], nose), point(inner[index], nose), color)
            triangle(Vec3(x.toDouble(), y.toDouble(), back), point(outer[next], back), point(outer[index], back), color)
            if (front) triangle(Vec3(x.toDouble(), y.toDouble(), nose), point(inner[index], nose), point(inner[next], nose), color)
        }
    }

    /** Front sheet metal follows the circular opening rather than covering it. */
    fun panelWithHole(left: Float, right: Float, bottom: Float, top: Float, x: Float, y: Float,
                      radius: Float, z: Float, color: FloatArray, segments: Int = 48) {
        fun point(angle: Double, outer: Boolean): Vec3 {
            val dx = cos(angle); val dy = sin(angle)
            val distance = if (!outer) radius.toDouble() else min(
                if (abs(dx) < 1e-9) Double.POSITIVE_INFINITY else ((if (dx > 0) right else left) - x) / dx,
                if (abs(dy) < 1e-9) Double.POSITIVE_INFINITY else ((if (dy > 0) top else bottom) - y) / dy,
            )
            return Vec3(x + dx * distance, y + dy * distance, z.toDouble())
        }
        // Include rectangle-corner rays as well as evenly spaced circle samples.
        // Without these four vertices the outer rectangle would have diagonal gaps.
        val angles = ((0 until segments).map { it * Math.PI * 2 / segments } + listOf(
            kotlin.math.atan2((top-y).toDouble(), (right-x).toDouble()),
            kotlin.math.atan2((top-y).toDouble(), (left-x).toDouble()),
            kotlin.math.atan2((bottom-y).toDouble(), (left-x).toDouble()),
            kotlin.math.atan2((bottom-y).toDouble(), (right-x).toDouble()),
        ).map { if (it < 0) it + Math.PI * 2 else it }).distinct().sorted()
        for (i in angles.indices) {
            val a = angles[i]; val b = angles[(i + 1) % angles.size]
            quad(point(a, false), point(a, true), point(b, true), point(b, false), color)
        }
    }

    /** Open tube, used for a real drum cavity without front/back caps. */
    fun tube(x: Float, y: Float, z: Float, radius: Float, depth: Float, color: FloatArray, segments: Int = 40) {
        fun point(angle: Double, dz: Double) = Vec3(x + cos(angle) * radius, y + sin(angle) * radius, z + dz)
        for (i in 0 until segments) {
            val a = i * Math.PI * 2 / segments; val b = (i + 1) * Math.PI * 2 / segments
            quad(point(a, -depth / 2.0), point(a, depth / 2.0), point(b, depth / 2.0), point(b, -depth / 2.0), color)
        }
    }

    fun annulus(x: Float, y: Float, z: Float, inner: Float, outer: Float, color: FloatArray, segments: Int = 40) {
        fun point(angle: Double, radius: Float) = Vec3(x + cos(angle) * radius, y + sin(angle) * radius, z.toDouble())
        for (i in 0 until segments) {
            val a = i * Math.PI * 2 / segments; val b = (i + 1) * Math.PI * 2 / segments
            quad(point(a, inner), point(a, outer), point(b, outer), point(b, inner), color)
        }
    }

    fun cylinder(x: Float, y: Float, z: Float, radius: Float, depth: Float, color: FloatArray, axisZ: Boolean = true, segments: Int = 28) {
        fun point(angle: Double, h: Double): Vec3 = if (axisZ) Vec3(x + cos(angle)*radius,y + sin(angle)*radius,z+h)
            else Vec3(x + cos(angle)*radius,y+h,z - sin(angle)*radius)
        val frontCenter = if (axisZ) Vec3(x.toDouble(),y.toDouble(),z+depth/2.0) else Vec3(x.toDouble(),y+depth/2.0,z.toDouble())
        val backCenter = if (axisZ) Vec3(x.toDouble(),y.toDouble(),z-depth/2.0) else Vec3(x.toDouble(),y-depth/2.0,z.toDouble())
        for (i in 0 until segments) {
            val a = i * Math.PI * 2 / segments
            val b = (i+1) * Math.PI * 2 / segments
            val fa = point(a,depth/2.0); val fb = point(b,depth/2.0)
            val ba = point(a,-depth/2.0); val bb = point(b,-depth/2.0)
            triangle(frontCenter,fa,fb,color); triangle(backCenter,bb,ba,color)
            quad(ba,bb,fb,fa,color)
        }
    }

    fun torus(x: Float, y: Float, z: Float, radius: Float, tube: Float, color: FloatArray) =
        torusArc(x,y,z,radius,tube,0f,(Math.PI*2).toFloat(),color,48)

    fun torusArc(x: Float, y: Float, z: Float, radius: Float, tube: Float, start: Float, end: Float, color: FloatArray, segments: Int = 14) {
        fun point(a: Double, b: Double) = Vec3(x+(radius+tube*cos(b))*cos(a),y+(radius+tube*cos(b))*sin(a),z+tube*sin(b))
        for (i in 0 until segments) for (j in 0 until 8) {
            val a = start+(end-start)*i.toDouble()/segments; val an = start+(end-start)*(i+1).toDouble()/segments
            val b = j*Math.PI*2/8; val bn = (j+1)*Math.PI*2/8
            quad(point(a,b),point(an,b),point(an,bn),point(a,bn),color)
        }
    }

    fun ellipsoid(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, color: FloatArray) {
        fun point(a: Double,b: Double) = Vec3(x+rx*cos(a)*sin(b),y+ry*cos(b),z+rz*sin(a)*sin(b))
        for (i in 0 until 10) for (j in 0 until 7) {
            val a=i*Math.PI*2/10; val an=(i+1)*Math.PI*2/10
            val b=j*Math.PI/7; val bn=(j+1)*Math.PI/7
            quad(point(a,b),point(an,b),point(an,bn),point(a,bn),color)
        }
    }

    fun triangle(a: Vec3, b: Vec3, c: Vec3, color: FloatArray) {
        val normal = (b-a).cross(c-a).normalized()
        indices.add(vertex(a,normal,color)); indices.add(vertex(b,normal,color)); indices.add(vertex(c,normal,color))
    }

    fun polygon(points: List<Pair<Float,Float>>, z: Float, color: FloatArray, rotation: Float = 0f) {
        val vertices = points.map { (x,y) -> Vec3((x*cos(rotation)-y*sin(rotation)).toDouble(),(x*sin(rotation)+y*cos(rotation)).toDouble(),z.toDouble()) }
        val remaining = vertices.indices.toMutableList()
        val area = vertices.indices.sumOf { i -> val a=vertices[i]; val b=vertices[(i+1)%vertices.size]; a.x*b.y-b.x*a.y }
        if (area < 0) remaining.reverse()
        var guard = vertices.size * vertices.size
        while (remaining.size > 2 && guard-- > 0) {
            var clipped = false
            for (i in remaining.indices) {
                val a=remaining[(i+remaining.size-1)%remaining.size]; val b=remaining[i]; val c=remaining[(i+1)%remaining.size]
                if ((vertices[b]-vertices[a]).cross(vertices[c]-vertices[b]).z <= 1e-9) continue
                if (remaining.any { it!=a && it!=b && it!=c && insideTriangle(vertices[it],vertices[a],vertices[b],vertices[c]) }) continue
                triangle(vertices[a],vertices[b],vertices[c],color)
                remaining.removeAt(i); clipped=true; break
            }
            if (!clipped) break
        }
    }

    private fun insideTriangle(p: Vec3,a: Vec3,b: Vec3,c: Vec3): Boolean =
        (b-a).cross(p-a).z >= -1e-9 && (c-b).cross(p-b).z >= -1e-9 && (a-c).cross(p-c).z >= -1e-9

    fun append(mesh: MeshData, slot: RoomSlot) {
        val base = positions.size / 3
        for (index in 0 until mesh.vertexCount) {
            val offset = index * 3
            val point = slot.point(mesh.positions[offset], mesh.positions[offset + 1], mesh.positions[offset + 2])
            val nx = mesh.normals[offset]; val ny = mesh.normals[offset + 1]; val nz = mesh.normals[offset + 2]
            vertex(point, Vec3((nx * cos(slot.yaw) + nz * sin(slot.yaw)).toDouble(), ny.toDouble(),
                (-nx * sin(slot.yaw) + nz * cos(slot.yaw)).toDouble()), mesh.colors.copyOfRange(index * 4, index * 4 + 4))
        }
        mesh.indices.forEach { indices.add(base + it) }
    }

    fun build() = MeshData(positions.toFloatArray(),normals.toFloatArray(),colors.toFloatArray(),indices.toIntArray())
}
