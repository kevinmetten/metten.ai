package com.mobileclaw.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Shared payload-only preparation; it never modifies the user's stored image. */
internal object CloudImagePreparer {
    fun prepare(dataUri: String): String {
        var original: Bitmap? = null
        var prepared: Bitmap? = null
        return try {
            val comma = dataUri.indexOf(',')
            if (comma < 0) return dataUri
            val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return dataUri
            original = source
            val scale = minOf(1f, 1920f / maxOf(source.width, source.height))
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt(), (source.height * scale).toInt(), true,
            ) else source
            prepared = bitmap
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            dataUri
        } finally {
            prepared?.takeIf { it !== original }?.recycle()
            original?.recycle()
        }
    }
}
