package com.laoke.weshirt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Bounds are checked by prepareMedia before an image is decoded here. Removes metadata. */
internal fun compressPhoto(context: Context, uri: Uri, maxBytes: Int, side: Int): ByteArray {
    val image = prepareMedia(context, uri)
    require(image.second.startsWith("image/")) { "请选择静态照片" }
    var bitmap = BitmapFactory.decodeByteArray(image.first, 0, image.first.size) ?: error("无法读取图片")
    try {
        if (maxOf(bitmap.width, bitmap.height) > side) {
            val ratio = side.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val output = ByteArrayOutputStream()
        repeat(5) {
            for (quality in listOf(85, 70, 55, 40, 25)) {
                output.reset(); bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (output.size() <= maxBytes) return output.toByteArray()
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .75f).toInt().coerceAtLeast(1), (bitmap.height * .75f).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        error("图片过大，请裁剪后重试")
    } finally { bitmap.recycle() }
}
