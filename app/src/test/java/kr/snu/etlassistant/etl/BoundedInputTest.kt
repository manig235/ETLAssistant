package kr.snu.etlassistant.etl

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun acceptsEmptyBelowAndExactlyAtLimit() {
        for (size in listOf(0, 7, 8)) {
            val bytes = ByteArray(size) { it.toByte() }
            assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBounded(8, "too big"))
        }
    }

    @Test fun rejectsOversizeAfterOnlyOneExtraByte() {
        val stream = ByteArrayInputStream(ByteArray(100))
        try { stream.readBounded(8, "수강편람 응답이 너무 커요"); fail() }
        catch (e: IOException) { assertEquals("수강편람 응답이 너무 커요", e.message) }
        assertEquals(91, stream.available())
    }

    @Test fun handlesShortReadsAndZeroReadsWithoutLosingBytes() {
        val expected = ByteArray(20) { it.toByte() }
        val source = ByteArrayInputStream(expected)
        var zero = true
        val input = object : InputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zero) { zero = false; return 0 }
                return source.read(b, off, minOf(len, 3))
            }
        }
        assertArrayEquals(expected, input.readBounded(20, "too big"))
    }

    @Test fun errorPrefixReadsStopAtTheLimit() {
        val input = ByteArrayInputStream(ByteArray(100))
        assertEquals(8, input.readNBytesCompat(8).size)
        assertEquals(92, input.available())
        assertArrayEquals(byteArrayOf(), input.readNBytesCompat(0))
    }
}
