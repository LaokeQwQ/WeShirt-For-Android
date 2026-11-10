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

internal data class RoomSlot(val slot: String, val x: Float, val z: Float = -1.0f, val label: String = slot, val dryer: Boolean = label.startsWith("D")) {
    val bounds get() = RoomBounds(Vec3(x - .54, 0.0, z - .45), Vec3(x + .54, 1.82, z + .55))
}

internal object RoomLayout {
    val slots = listOf("D1", "1", "2", "3", "4", "5", "6", "7", "D2")
        .mapIndexed { index, slot -> RoomSlot(slot, (index - 4) * 1.19f) }

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
        }.sortedBy { it.x }
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
            .mapNotNull { slot -> slot.bounds.intersection(ray)?.let { slot.slot to it } }
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
        val floor = rgb(0xDDE6E5)
        paint.box(0f, -.09f, .3f + extraDepth / 2, 12.5f, .18f, 6.7f + extraDepth, floor)
        paint.box(0f, 1.9f, -1.94f, 12.5f, 3.8f, .16f, rgb(0xE5ECE7))
        paint.box(-6.18f, .65f, .35f + extraDepth / 2, .15f, 1.3f, 4.5f + extraDepth, rgb(0xC9D9D3))
        paint.box(6.18f, .65f, .35f + extraDepth / 2, .15f, 1.3f, 4.5f + extraDepth, rgb(0xC9D9D3))
        paint.box(0f, .10f, -1.79f, 12.3f, .20f, .08f, rgb(0x81958D))
        for (x in -6..6) paint.box(x.toFloat(), .009f, .35f + extraDepth / 2, .013f, .006f, 6.5f + extraDepth, rgb(0xC7D4D1))
        for (z in -1..(3 + extraDepth.toInt())) paint.box(0f, .010f, z.toFloat(), 12.3f, .006f, .012f, rgb(0xC7D4D1))
        // Recessed windows with separate frames and handles.
        for (x in listOf(-3.6f, 0f, 3.6f)) {
            metal.box(x, 2.76f, -1.79f, 2.65f, 1.28f, .13f, rgb(0x708C86))
            glow.box(x, 2.76f, -1.70f, 2.46f, 1.10f, .035f, rgb(0xB2D4DF))
            metal.box(x, 2.76f, -1.64f, .055f, 1.12f, .05f, rgb(0xE7F0EC))
            metal.box(x, 2.76f, -1.64f, 2.47f, .055f, .05f, rgb(0xE7F0EC))
            metal.box(x + .12f, 2.65f, -1.58f, .035f, .16f, .04f, rgb(0x5C716C))
            paint.box(x, 2.13f, -1.55f, 2.78f, .09f, .40f, rgb(0xEEF4ED))
        }
        paint.box(0f, .51f, 1.7f + extraDepth, 3.6f, .13f, .65f, rgb(0xBC9671))
        for (x in listOf(-1.45f, 1.45f)) {
            metal.box(x, .24f, 1.7f + extraDepth, .10f, .48f, .48f, rgb(0x65776F))
            paint.box(x, .61f, 1.7f + extraDepth, .025f, .015f, .66f, rgb(0x8E6E51))
        }
        for (x in listOf(-5.6f, 5.6f)) {
            paint.cylinder(x, .31f, 1.6f + extraDepth, .27f, .54f, rgb(0xC2A78D), axisZ = false, segments = 20)
            metal.box(x, .82f, 1.6f + extraDepth, .035f, .65f, .035f, rgb(0x5D8063))
            for (leaf in 0..6) {
                val angle = leaf * 2.4f
                paint.ellipsoid(x + cos(angle) * .19f, .82f + leaf * .045f, 1.6f + extraDepth + sin(angle) * .17f,
                    .15f, .23f, .07f, rgb(if (leaf % 2 == 0) 0x547F60 else 0x6A9671))
            }
        }
        // Floor edge and a quiet teal mat establish the room's scale.
        paint.box(0f, .025f, .1f + extraDepth, 8.8f, .035f, 1.0f, rgb(0x9DB7AB))
        return mapOf(RoomMaterial.PAINT to paint.build(), RoomMaterial.METAL to metal.build(), RoomMaterial.GLOW to glow.build())
    }

    fun machineBodies(positions: List<RoomSlot> = RoomLayout.slots): Map<RoomMaterial, MeshData> {
        val paint = MeshBuilder()
        val metal = MeshBuilder()
        for (slot in positions) {
            val x = slot.x
            val z = slot.z
            val dryer = slot.dryer
            paint.box(x, .89f, z, 1.06f, 1.68f, .88f, rgb(if (dryer) 0xE5D8BE else 0xE7EFEB))
            paint.box(x, 1.73f, z, 1.08f, .065f, .91f, rgb(0xF4F5EC))
            paint.box(x, 1.44f, z + .452f, 1.0f, .32f, .035f, rgb(0xD9E2DC))
            paint.box(x + .16f, 1.46f, z + .48f, .43f, .16f, .025f, rgb(0x334B47))
            metal.cylinder(x - .29f, 1.46f, z + .49f, .075f, .045f, rgb(0xBBC7C1), segments = 20)
            paint.box(x - .29f, 1.48f, z + .521f, .015f, .063f, .009f, rgb(0x536A62))
            // The drum sits in front of the body. The open ring leaves the contents visible.
            metal.cylinder(x, .80f, z + .464f, .385f, .035f, rgb(0x263C39), segments = 36)
            metal.torus(x, .80f, z + .50f, .359f, .066f, rgb(0xA8B9B2))
            metal.torus(x, .80f, z + .542f, .293f, .018f, rgb(0x556D66))
            for (i in 0..20) {
                val a = i * (Math.PI * 2 / 21)
                metal.cylinder(x + (cos(a) * .258).toFloat(), .80f + (sin(a) * .258).toFloat(), z + .487f,
                    .008f, .007f, rgb(0x8CABA2), segments = 5)
            }
            // A small bevel highlight suggests door glass without hiding garment silhouettes.
            metal.torusArc(x, .80f, z + .56f, .325f, .013f, .35f, 1.25f, rgb(0xD0E3DF))
            paint.box(x + .37f, .81f, z + .57f, .06f, .20f, .065f, rgb(0x6F847B))
            paint.box(x, .19f, z + .456f, .90f, .13f, .025f, rgb(0xBACAC0))
            for (i in 0..6) paint.box(x - .28f + i * .095f, .19f, z + .474f, .044f, .008f, .008f, rgb(0x748B81))
            for (dx in listOf(-.39f, .39f)) metal.box(x + dx, .037f, z + .27f, .14f, .07f, .18f, rgb(0x53675E))
            paint.glyph(slot.label.take(3), x, 1.62f, z + .481f, .09f, rgb(0x3F5C52))
        }
        return mapOf(RoomMaterial.PAINT to paint.build(), RoomMaterial.METAL to metal.build())
    }

    fun status(slot: RoomSlot, state: RoomMachineState): MeshData = MeshBuilder().apply {
        val color = when (state) {
            RoomMachineState.IDLE -> 0x61B994
            RoomMachineState.RUNNING -> 0x71A4DB
            RoomMachineState.FINISHING -> 0xE2BD68
            RoomMachineState.AWAITING_PICKUP -> 0xDC9B5D
            RoomMachineState.FAULT -> 0xD66E6B
            RoomMachineState.UNKNOWN -> 0xA3ACA9
        }
        box(slot.x + .16f, 1.46f, slot.z + .50f, .28f, .048f, .012f, rgb(color))
        cylinder(slot.x + .43f, 1.62f, slot.z + .485f, .024f, .016f, rgb(color), segments = 12)
    }.build()

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

    fun build() = MeshData(positions.toFloatArray(),normals.toFloatArray(),colors.toFloatArray(),indices.toIntArray())
}
