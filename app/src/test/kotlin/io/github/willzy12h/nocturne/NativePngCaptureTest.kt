package io.github.willzy12h.nocturne

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NativePngCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writesEveryNativePixelIncludingAlphaAndTheFinalPixel() {
        val pixels = intArrayOf(0xff14171d.toInt(), 0xffced2d8.toInt(), 0xff444b57.toInt(),
            0xffffffff.toInt(), 0x00000000, 0x800000ff.toInt(), 0x40ff0000, 0xc000ff00.toInt(), 0x8000ffff.toInt())
        val file = temporary.newFile("roundtrip.png")
        val bitmap = Bitmap.createBitmap(pixels, 3, 3, Bitmap.Config.ARGB_8888)
        try {
            NativePngCapture.write(bitmap, file)
        } finally { bitmap.recycle() }
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        })!!
        try {
            assertEquals(3, decoded.width)
            assertEquals(3, decoded.height)
            val actual = IntArray(pixels.size)
            decoded.getPixels(actual, 0, 3, 0, 0, 3, 3)
            assertArrayEquals(pixels, actual)
        } finally { decoded.recycle() }
        assertEquals("Only the validated destination is published", listOf("roundtrip.png"), temporary.root.list()!!.toList())
    }

    @Test fun rejectsAnIdatBoundaryTruncationWithValidPresentChunkCrcs() {
        val width = 128
        val random = Random(41)
        val pixels = IntArray(width * width) { random.nextInt() or 0xff000000.toInt() }
        val complete = encode(pixels, width, width)
        val compressed = ByteArrayOutputStream()
        chunks(complete).filter { it.type == "IDAT" }.forEach {
            compressed.write(complete, it.offset + 8, it.length)
        }
        assertTrue("The fixture spans more than one native-sized IDAT", compressed.size() > 8192)
        val prefix = ByteArrayOutputStream().apply {
            write(complete, 0, 33) // Signature and the complete IHDR.
            chunk("sRGB", byteArrayOf(0))
            chunk("sBIT", byteArrayOf(8, 8, 8, 8))
            chunk("IDAT", compressed.toByteArray().copyOf(8192))
        }.toByteArray()
        assertEquals("Matches the observed 62 + N*8204 truncation pattern", 62 + 8204, prefix.size)
        val error = assertThrows(IllegalStateException::class.java) {
            NativePngCapture.validate(prefix, width, width, pixels)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("missing IEND"))
    }

    @Test fun rejectsCorruptIdatPayloadEvenWhenThePngHasAnIend() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt())
        val corrupt = encode(pixels, 2, 1)
        val idat = chunks(corrupt).first { it.type == "IDAT" }
        corrupt[idat.offset + 8] = (corrupt[idat.offset + 8].toInt() xor 1).toByte()
        val error = assertThrows(IllegalStateException::class.java) { NativePngCapture.validate(corrupt, 2, 1, pixels) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("CRC"))
    }

    @Test fun rejectsMissingIendAfterAllPixelDataWasEncoded() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt())
        val complete = encode(pixels, 2, 1)
        val truncated = complete.copyOf(chunks(complete).single { it.type == "IEND" }.offset)
        val error = assertThrows(IllegalStateException::class.java) { NativePngCapture.validate(truncated, 2, 1, pixels) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("missing IEND"))
    }

    @Test fun rejectsACompletePngWhenItsFinalPixelDiffers() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt())
        val complete = encode(pixels, 2, 1)
        NativePngCapture.validate(complete, 2, 1, pixels)
        val expected = pixels.copyOf().apply { this[lastIndex] = this[lastIndex] xor 1 }
        val error = assertThrows(IllegalStateException::class.java) { NativePngCapture.validate(complete, 2, 1, expected) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("pixels differ"))
    }

    private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = temporary.newFile()
        try { NativePngCapture.write(bitmap, file) } finally { bitmap.recycle() }
        return file.readBytes()
    }

    private data class Chunk(val offset: Int, val length: Int, val type: String)
    private fun chunks(png: ByteArray): List<Chunk> = buildList {
        var offset = 8
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            add(Chunk(offset, length, String(png, offset + 4, 4, Charsets.US_ASCII)))
            offset += length + 12
        }
    }

    private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
        val kind = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(kind); update(data) }.value
        DataOutputStream(this).apply { writeInt(data.size); write(kind); write(data); writeInt(crc.toInt()) }
    }
}
