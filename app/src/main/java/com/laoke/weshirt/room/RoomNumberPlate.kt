package com.laoke.weshirt.room

/** Static front decals, merged into the existing paint mesh: no textures or extra draw calls. */
internal object RoomNumberPlate {
    const val inkColor = 0x163247
    private val glyphs = mapOf(
        '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
        '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111",
        '7' to "111001010010010", 'D' to "110101101101110",
    )

    fun mesh(slot: RoomSlot): MeshData {
        val builder = MeshBuilder()
        val label = RoomLayout.normalizeSlot(slot.label)
        if (label !in setOf("1", "2", "3", "4", "5", "6", "7", "D1", "D2")) return builder.build()
        val centerY = (if (slot.dryer) 1.02f else .95f) - .102f
        val centerX = -.215f
        // The drawer front ends at z=.369; the plaque and ink are in front of it.
        // A decal needs only its visible face; hidden bevels and side walls
        // would cost more vertices than the glyph itself on nine cabinets.
        builder.face(Vec3((centerX - .0875f).toDouble(), (centerY - .051f).toDouble(), .381),
            Vec3((centerX + .0875f).toDouble(), (centerY - .051f).toDouble(), .381),
            Vec3((centerX + .0875f).toDouble(), (centerY + .051f).toDouble(), .381),
            Vec3((centerX - .0875f).toDouble(), (centerY + .051f).toDouble(), .381), RoomGeometry.rgb(0xFAFCF8))
        val cell = .014f
        val width = (label.length * 4 - 1) * cell
        label.forEachIndexed { letter, character ->
            glyphs.getValue(character).forEachIndexed { pixel, value ->
                if (value == '1') {
                    val x = centerX - width / 2 + (letter * 4 + pixel % 3) * cell
                    val y = centerY + 2.5f * cell - (pixel / 3 + 1) * cell
                    val z = .383
                    builder.face(Vec3(x.toDouble(), y.toDouble(), z), Vec3((x + cell).toDouble(), y.toDouble(), z),
                        Vec3((x + cell).toDouble(), (y + cell).toDouble(), z), Vec3(x.toDouble(), (y + cell).toDouble(), z), RoomGeometry.rgb(inkColor))
                }
            }
        }
        return builder.build()
    }
}
