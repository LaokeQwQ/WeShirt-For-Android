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

internal enum class RoomMaterial { PAINT, METAL, CLOTH, GLOW }
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
            for (dx in listOf(-.645f, 0f, .645f)) metal.box(x + dx, 1.5f, -2.03f, .045f, 1.3f, .06f, frame)
            for (dx in listOf(-.12f, .12f)) metal.box(x + dx, 1.5f, -1.98f, .026f, .15f, .032f, rgb(0xD5DEE8))
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
            body.box(0f, height / 2, 0f, .66f, height, .62f, rgb(if (slot.dryer) 0xD9D4CB else 0xDDE1E2))
            body.box(0f, height + .022f, 0f, .68f, .044f, .64f, rgb(0xE8EBE9))
            body.box(0f, height - .11f, .315f, .62f, .16f, .024f, rgb(0xC5CDD1))
            body.box(.105f, height - .10f, .334f, .24f, .09f, .02f, rgb(0x263840))
            detail.cylinder(-.20f, height - .10f, .34f, .043f, .025f, rgb(0xBCC5CB), segments = 16)
            detail.cylinder(0f, .53f, .328f, radius + .038f, .025f, rgb(0x26343C), segments = 28)
            detail.torus(0f, .53f, .36f, radius, .038f, rgb(0xACB6BC))
            detail.torus(0f, .53f, .389f, radius - .03f, .012f, rgb(0x5C6C76))
            detail.torusArc(0f, .53f, .40f, radius + .008f, .008f, .35f, 1.25f, rgb(0xDAE5E8))
            for (i in 0..15) {
                val a = i * (Math.PI * 2 / 16)
                detail.cylinder((cos(a) * (radius - .05f)).toFloat(), .53f + (sin(a) * (radius - .05f)).toFloat(), .342f,
                    .005f, .005f, rgb(0x8B9DA5), segments = 5)
            }
            body.box(radius + .035f, .53f, .411f, .038f, .13f, .04f, rgb(0x6C7A82))
            body.box(0f, .12f, .32f, .55f, .10f, .018f, rgb(0xB5C0C5))
            for (i in 0..6) body.box(-.20f + i * .066f, .12f, .334f, .035f, .006f, .006f, rgb(0x75868E))
            for (dx in listOf(-.25f, .25f)) detail.box(dx, .023f, .20f, .07f, .046f, .10f, rgb(0x4F5962))
            body.glyph(slot.label.take(3), 0f, height + .15f, .35f, .105f, rgb(0xE5EAF0))
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
        return mapOf(RoomMaterial.PAINT to paint.build(), RoomMaterial.METAL to metal.build())
    }

    fun status(slot: RoomSlot, state: RoomMachineState): MeshData {
        val color = when (state) {
            RoomMachineState.IDLE -> 0x22C55E
            RoomMachineState.RUNNING -> 0xF59E0B
            RoomMachineState.FINISHING -> 0xE2BD68
            RoomMachineState.AWAITING_PICKUP -> 0xF97316
            RoomMachineState.FAULT -> 0xEF4444
            RoomMachineState.UNKNOWN -> 0x6B7280
        }
        val height = if (slot.dryer) 1.02f else .95f
        val local = MeshBuilder().apply {
            box(.105f, height - .10f, .35f, .16f, .028f, .01f, rgb(color))
            cylinder(.27f, height - .045f, .34f, .017f, .012f, rgb(color), segments = 10)
            box(0f, height + .065f, .26f, .54f, .018f, .035f, rgb(color))
        }.build()
        return MeshBuilder().apply { append(local, slot) }.build()
    }

    /** Local coordinates allow rotation by a transform, with no vertex uploads per frame. */
    fun garments(slotIndex: Int): MeshData = MeshBuilder().apply {
        val shirt = listOf(-.12f to .13f, -.055f to .18f, .055f to .18f, .12f to .13f,
            .19f to .075f, .135f to .005f, .095f to .045f, .095f to -.15f,
            -.095f to -.15f, -.095f to .045f, -.135f to .005f, -.19f to .075f)
        polygon(shirt.map { (x, y) -> (x - .075f) to (y + .06f) }, .009f,
            rgb(if (slotIndex % 2 == 0) 0x91B9D1 else 0xCEABA4), rotation = -.35f)
        val trousers = listOf(-.11f to .14f, .11f to .14f, .13f to -.15f, .025f to -.16f,
            0f to -.025f, -.025f to -.16f, -.13f to -.15f)
        polygon(trousers.map { (x, y) -> (x + .105f) to (y - .065f) }, .032f,
            rgb(0x526F83), rotation = .45f)
        // Cuffs and a collar are distinct folds rather than spherical fabric blobs.
        box(-.08f, .175f, .024f, .065f, .025f, .018f, rgb(0xD1DFE1))
        box(.15f, -.17f, .047f, .085f, .026f, .018f, rgb(0x7E96A3))
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

    fun glyph(text: String,x: Float,y: Float,z: Float,size: Float,color: FloatArray) {
        val segments = mapOf('0' to "ABCDEF",'1' to "BC",'2' to "ABGED",'3' to "ABGCD",'4' to "FGBC",'5' to "AFGCD",'6' to "AFGECD",'7' to "ABC",'8' to "ABCDEFG",'9' to "ABCGFD",'D' to "FEBCD",'-' to "G")
        text.forEachIndexed { i,c ->
            val px=x+(i-(text.length-1)/2f)*size*.85f
            for (segment in segments[c].orEmpty()) when (segment) {
                'A' -> box(px,y+size*.55f,z,size*.53f,size*.13f,.009f,color)
                'G' -> box(px,y,z,size*.53f,size*.13f,.009f,color)
                'D' -> box(px,y-size*.55f,z,size*.53f,size*.13f,.009f,color)
                'F' -> box(px-size*.3f,y+size*.27f,z,size*.13f,size*.5f,.009f,color)
                'E' -> box(px-size*.3f,y-size*.27f,z,size*.13f,size*.5f,.009f,color)
                'B' -> box(px+size*.3f,y+size*.27f,z,size*.13f,size*.5f,.009f,color)
                'C' -> box(px+size*.3f,y-size*.27f,z,size*.13f,size*.5f,.009f,color)
            }
        }
    }

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
