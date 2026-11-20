package com.lmarchery.feature.photo

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class EndPhotoTest {
    @TempDir lateinit var directory: Path

    private val calibration = PhotoCalibration(
        PhotoPoint(.25f, .5f), PhotoPoint(.5f, .125f), PhotoPoint(.75f, .5f), PhotoPoint(.5f, .875f),
    )
    private val key = EndPhotoKey(id(1), id(2))

    @Test fun `calibration uses upright image pixels and keeps screen y down`() {
        val circle = assertNotNull(calibration.circle(900, 600))
        assertEquals(225f, circle.radius)
        val point = assertNotNull(circle.normalize(PhotoPoint(.625f, .6875f), 900, 600))
        assertEquals(.5f, point.x)
        assertEquals(.5f, point.y)
        assertNull(circle.normalize(PhotoPoint(.9f, .5f), 900, 600))
    }

    @Test fun `partial off frame and distorted alignments are insufficient`() {
        assertNull(calibration.copy(left = null).circle(900, 600))
        assertNull(calibration.copy(left = PhotoPoint(0f, .5f), right = PhotoPoint(.5f, .5f),
            top = PhotoPoint(.25f, .125f), bottom = PhotoPoint(.25f, .875f)).circle(900, 600))
        assertNull(calibration.circle(900, 450))
        assertNull(calibration.copy(top = PhotoPoint(.75f, .125f)).circle(900, 600))
        assertFailsWith<IllegalArgumentException> { PhotoPoint(Float.NaN, .2f) }
    }

    @Test fun `media and partial annotations reopen with exact identity and Float bits`() {
        val image = PhotoImage(900, 600, byteArrayOf(5, 3, 2))
        val mark = PhotoMark(id(3), PhotoPoint(Float.fromBits(0x3efdddf1), 0f))
        val partial = calibration.copy(bottom = null)
        EndPhotoStore(directory).save(key, EndPhoto(image, partial, listOf(mark)))
        val reopened = assertNotNull(EndPhotoStore(directory).load(key))
        assertContentEquals(image.jpeg, reopened.image.jpeg)
        assertEquals(partial, reopened.calibration)
        assertEquals(mark.uuid, reopened.marks.single().uuid)
        assertEquals(mark.point.x.toRawBits(), reopened.marks.single().point.x.toRawBits())
        assertNull(reopened.projectedMarks(emptySet()))
        assertNull(EndPhotoStore(directory).load(EndPhotoKey(id(9), key.endUuid)))
        val persisted = Files.list(directory).use { it.toList().single() }
        val renamed = directory.resolve("${id(9)}-${key.endUuid}.photo")
        Files.copy(persisted, renamed)
        assertFailsWith<IOException> { EndPhotoStore(directory).load(EndPhotoKey(id(9), key.endUuid)) }
        Files.write(persisted, byteArrayOf(0))
        assertFailsWith<IOException> { EndPhotoStore(directory).load(key) }
    }

    @Test fun `all uncommitted marks must be reliable and committed identities are excluded`() {
        val outside = PhotoMark(id(3), PhotoPoint(.99f, .5f))
        val inside = PhotoMark(id(4), PhotoPoint(.5f, .5f))
        val photo = EndPhoto(PhotoImage(900, 600, byteArrayOf(1)), calibration, listOf(outside, inside))
        assertNull(photo.projectedMarks(emptySet()))
        val remaining = assertNotNull(photo.projectedMarks(setOf(outside.uuid)))
        assertEquals(listOf(inside.uuid), remaining.keys.toList())
        assertEquals(0f, remaining.getValue(inside.uuid).x)
        assertFailsWith<IllegalArgumentException> { photo.copy(marks = listOf(inside, inside)) }
    }

    private fun id(value: Int) = "00000000-0000-4000-8000-%012d".format(value)
}
