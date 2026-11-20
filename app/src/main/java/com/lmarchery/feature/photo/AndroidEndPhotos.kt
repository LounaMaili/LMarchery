package com.lmarchery.feature.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal class AndroidEndPhotos(private val context: Context) {
    private val store = EndPhotoStore(context.filesDir.toPath().resolve("end-photos"))
    private val captures = File(context.cacheDir, "end-photo-capture")

    fun load(key: EndPhotoKey) = store.load(key)
    fun save(key: EndPhotoKey, photo: EndPhoto) = store.save(key, photo)

    fun newCapture(): String {
        if (!captures.isDirectory && !captures.mkdirs()) throw IOException("Cannot create capture directory")
        return File.createTempFile("target-", ".jpg", captures).name
    }

    fun captureUri(name: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.end-photo-capture", captureFile(name))

    fun removeCapture(name: String) {
        captureFile(name).delete()
    }

    private fun captureFile(name: String): File {
        require(name.matches(Regex("target-[0-9]+[.]jpg")))
        return File(captures, name)
    }

    fun importImage(uri: Uri): EndPhoto {
        require(uri.scheme == "content")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length == -1) break
                if (output.size() + length > 24 * 1024 * 1024) throw IOException("Photo too large")
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        } ?: throw IOException("Photo unavailable")
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
        if (size.outWidth <= 0 || size.outHeight <= 0 ||
            size.outWidth.toLong() * size.outHeight > 160_000_000L
        ) throw IOException("Unsupported photo dimensions")
        var sample = 1
        while (maxOf(size.outWidth, size.outHeight).toLong() > MAX_PHOTO_SIDE.toLong() * sample) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        if (size.outMimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/avif")) {
            throw IOException("Unsupported image format")
        }
        val exif = if (size.outMimeType == "image/jpeg") ExifInterface(bytes.inputStream()) else null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Invalid image")
        try {
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) setScale(-1f, 1f)
                postRotate(exif?.rotationDegrees?.toFloat() ?: 0f)
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                val output = ByteArrayOutputStream()
                if (!upright.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw IOException("Photo encoding failed")
                return EndPhoto(PhotoImage(upright.width, upright.height, output.toByteArray()))
            } finally {
                if (upright !== bitmap) upright.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
