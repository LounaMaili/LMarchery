package com.lmarchery.feature.photo

import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal const val MAX_PHOTO_SIDE = 2048
internal const val MAX_PHOTO_BYTES = 12 * 1024 * 1024
internal const val MAX_PHOTO_MARKS = 64

private fun requireUuid(value: String) {
    val id = UUID.fromString(value)
    require(id.version() == 4 && id.variant() == 2 && id.toString() == value) { "Invalid photo identity" }
}

internal data class EndPhotoKey(val sessionUuid: String, val endUuid: String) {
    init {
        requireUuid(sessionUuid)
        requireUuid(endUuid)
    }
}

internal data class PhotoMark(val uuid: String, val point: PhotoPoint) {
    init {
        requireUuid(uuid)
    }
}

internal class PhotoImage(val width: Int, val height: Int, val jpeg: ByteArray) {
    init {
        require(width in 1..MAX_PHOTO_SIDE && height in 1..MAX_PHOTO_SIDE && jpeg.size in 1..MAX_PHOTO_BYTES)
    }
}

internal data class EndPhoto(
    val image: PhotoImage,
    val calibration: PhotoCalibration = PhotoCalibration(),
    val marks: List<PhotoMark> = emptyList(),
) {
    init {
        require(marks.size <= MAX_PHOTO_MARKS && marks.distinctBy { it.uuid }.size == marks.size)
    }

    fun projectedMarks(exclude: Set<String>): Map<String, com.lmarchery.core.model.NormalizedPoint>? {
        val circle = calibration.circle(image.width, image.height) ?: return null
        return buildMap {
            for (mark in marks) {
                if (mark.uuid in exclude) continue
                put(mark.uuid, circle.normalize(mark.point, image.width, image.height) ?: return null)
            }
        }
    }
}

/** A private record binds the photograph and its annotations to both owner IDs. */
internal class EndPhotoStore(private val directory: Path) {
    fun load(key: EndPhotoKey): EndPhoto? {
        val file = file(key)
        if (Files.notExists(file)) return null
        return DataInputStream(Files.newInputStream(file).buffered()).use { input ->
            if (input.readInt() != 1 || input.readUTF() != key.sessionUuid || input.readUTF() != key.endUuid) {
                throw IOException("Invalid photo version or owner")
            }
            val width = input.readInt()
            val height = input.readInt()
            val image = PhotoImage(width, height, ByteArray(input.readCount(MAX_PHOTO_BYTES)).also(input::readFully))
            val calibration = PhotoCalibration(
                input.readOptionalPoint(), input.readOptionalPoint(), input.readOptionalPoint(), input.readOptionalPoint(),
            )
            val marks = List(input.readCount(MAX_PHOTO_MARKS)) { PhotoMark(input.readUTF(), input.readPoint()) }
            if (input.read() != -1) throw IOException("Unexpected photo data")
            EndPhoto(image, calibration, marks)
        }
    }

    fun save(key: EndPhotoKey, photo: EndPhoto) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".end-photo-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { file ->
                val out = DataOutputStream(file.buffered())
                out.writeInt(1)
                out.writeUTF(key.sessionUuid)
                out.writeUTF(key.endUuid)
                out.writeInt(photo.image.width)
                out.writeInt(photo.image.height)
                out.writeInt(photo.image.jpeg.size)
                out.write(photo.image.jpeg)
                for (anchor in photo.calibration.anchors()) {
                    out.writeBoolean(anchor != null)
                    if (anchor != null) out.writePoint(anchor)
                }
                out.writeInt(photo.marks.size)
                for (mark in photo.marks) {
                    out.writeUTF(mark.uuid)
                    out.writePoint(mark.point)
                }
                out.flush()
                file.fd.sync()
            }
            Files.move(temporary, file(key), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun file(key: EndPhotoKey) = directory.resolve("${key.sessionUuid}-${key.endUuid}.photo")

    private fun DataInput.readCount(maximum: Int): Int = readInt().also {
        if (it !in 0..maximum) throw IOException("Invalid photo record size")
    }
    private fun DataInput.readPoint() = PhotoPoint(readFloat(), readFloat())
    private fun DataInput.readOptionalPoint() = if (readBoolean()) readPoint() else null
    private fun DataOutput.writePoint(point: PhotoPoint) {
        writeFloat(point.x)
        writeFloat(point.y)
    }
}
