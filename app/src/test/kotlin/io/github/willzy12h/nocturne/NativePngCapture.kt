package io.github.willzy12h.nocturne

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Native rendering stays in Bitmap; host encoding must preserve every pixel before publication. */
internal object NativePngCapture {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun write(bitmap: Bitmap, file: File) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Native Bitmap.compress returned true for PNGs ending at an IDAT boundary without IEND.
        // Finish the host zlib stream and the complete PNG byte array before opening a file.
        val encoded = encode(width, height, pixels)
        val destination = file.toPath().toAbsolutePath()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${file.name}-", ".tmp")
        try {
            Files.write(temporary, encoded)
            validate(Files.readAllBytes(temporary), width, height, pixels)
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** PNG's fixed RGBA8 format: one filter-0 scanline at a time, with no desktop-image dependency. */
    private fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(width * 4 + 1) // First byte stays zero: no PNG scanline filter.
            for (y in 0 until height) {
                var offset = 1
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    row[offset++] = (color ushr 16).toByte()
                    row[offset++] = (color ushr 8).toByte()
                    row[offset++] = color.toByte()
                    row[offset++] = (color ushr 24).toByte()
                }
                stream.write(row)
            }
        } // close() finishes the zlib stream synchronously.
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { stream ->
            stream.write(signature)
            stream.chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(byteArrayOf(8, 6, 0, 0, 0)).array())
            stream.chunk("sRGB", byteArrayOf(0))
            stream.chunk("sBIT", byteArrayOf(8, 8, 8, 8))
            stream.chunk("IDAT", compressed.toByteArray())
            stream.chunk("IEND", byteArrayOf())
        }
        return result.toByteArray()
    }

    private fun DataOutputStream.chunk(type: String, data: ByteArray) {
        val kind = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(kind); update(data) }.value
        writeInt(data.size)
        write(kind)
        write(data)
        writeInt(crc.toInt())
    }

    /** A successful encoder return alone is insufficient: reject incomplete, corrupt or changed output. */
    fun validate(png: ByteArray, width: Int, height: Int, pixels: IntArray) {
        check(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        check(png.size >= signature.size && png.copyOfRange(0, signature.size).contentEquals(signature)) {
            "Invalid PNG signature"
        }
        var offset = signature.size
        var hasIdat = false
        var hasIend = false
        while (offset < png.size) {
            check(png.size - offset >= 12) { "Incomplete PNG chunk header or CRC at $offset" }
            val length = intAt(png, offset)
            check(length >= 0 && length <= png.size - offset - 12) { "Incomplete PNG chunk at $offset" }
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val end = offset + length + 12
            val crc = CRC32().apply { update(png, offset + 4, length + 4) }.value
            check(crc == (intAt(png, end - 4).toLong() and 0xffffffffL)) { "Invalid PNG $type CRC at $offset" }
            if (offset == signature.size || type == "IHDR") {
                check(offset == signature.size && type == "IHDR" && length == 13) { "Invalid PNG IHDR" }
                check(intAt(png, offset + 8) == width && intAt(png, offset + 12) == height) { "PNG dimensions changed" }
            }
            if (type == "IDAT") hasIdat = true
            if (type == "IEND") {
                check(length == 0 && end == png.size) { "Invalid PNG IEND or trailing bytes" }
                hasIend = true
            }
            offset = end
        }
        check(hasIdat) { "PNG is missing IDAT" }
        check(hasIend) { "PNG is missing IEND" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size, options)
        check(decoded != null) { "PNG cannot be fully decoded" }
        try {
            check(decoded.width == width && decoded.height == height) { "Decoded PNG dimensions changed" }
            val actual = IntArray(pixels.size)
            decoded.getPixels(actual, 0, width, 0, 0, width, height)
            check(pixels.contentEquals(actual)) { "PNG pixels differ from the native bitmap" }
        } finally {
            decoded.recycle()
        }
    }

    private fun intAt(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).int
}
