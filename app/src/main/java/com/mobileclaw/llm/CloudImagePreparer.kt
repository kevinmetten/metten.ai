package com.mobileclaw.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Shared payload-only preparation; it never modifies the user's stored image. */
internal object CloudImagePreparer {
    fun prepare(dataUri: String): String = try {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return dataUri
        val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return dataUri
        val scale = minOf(1f, 1920f / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(
            original, (original.width * scale).toInt(), (original.height * scale).toInt(), true,
        ) else original
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        dataUri
    }
}
