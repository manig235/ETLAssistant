package kr.snu.etlassistant.etl

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64

class WorkspaceImageTest {
    @get:Rule val temp = TemporaryFolder()
    private val cleanJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    private class FakeCodec(
        private val size: WorkspaceImage.Bounds = WorkspaceImage.Bounds(200, 100, "image/png"),
        private val encoded: WorkspaceImage.Encoded = WorkspaceImage.Encoded(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), 200, 100),
    ) : WorkspaceImage.Codec {
        var boundsCalls = 0
        var sanitizeCalls = 0
        var sample: Int? = null
        override fun bounds(bytes: ByteArray): WorkspaceImage.Bounds { boundsCalls++; return size }
        override fun sanitize(bytes: ByteArray, sample: Int): WorkspaceImage.Encoded {
            sanitizeCalls++; this.sample = sample; return encoded
        }
    }

    private fun workspace(): File = temp.newFolder().apply { File(this, EtlFiles.DIR).mkdirs() }
    private fun image(root: File, name: String = "page.png", bytes: ByteArray = "untrusted container".toByteArray()): String =
        "${EtlFiles.DIR}/$name".also { File(root, it).writeBytes(bytes) }

    @Test fun cannotReadAbsoluteTraversalPrefixOrSymlinkEscapes() {
        val root = workspace()
        val outside = temp.newFolder()
        val secret = File(outside, "secret.png").apply { writeText("secret-test-value") }
        Files.createSymbolicLink(File(root, "${EtlFiles.DIR}/outside.png").toPath(), secret.toPath())
        File(root, "etl-files-other").mkdirs()
        File(root, "etl-files-other/page.png").writeText("secret-test-value")
        for (path in listOf(secret.path, "../${secret.name}", "etl-files-other/page.png", "etl-files/outside.png", "etl-files/../auth.json")) {
            val codec = FakeCodec()
            val result = WorkspaceImage.view(root, path, codec)
            assertTrue(result.isError)
            assertTrue(result.images.isEmpty())
            assertEquals(0, codec.boundsCalls)
            assertFalse(result.text.contains("secret-test-value"))
            assertFalse(result.text.contains(path))
        }
    }

    @Test fun symbolicCourseworkRootIsRejected() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        File(outside, "secret.png").writeText("secret-test-value")
        Files.createSymbolicLink(File(root, EtlFiles.DIR).toPath(), outside.toPath())
        val codec = FakeCodec()
        assertTrue(WorkspaceImage.view(root, "etl-files/secret.png", codec).isError)
        assertEquals(0, codec.boundsCalls)
    }

    @Test fun oversizedFileIsRejectedBeforeDecode() {
        val root = workspace()
        val path = image(root)
        RandomAccessFile(File(root, path), "rw").use { it.setLength(WorkspaceImage.MAX_FILE_BYTES.toLong() + 1) }
        val codec = FakeCodec()
        assertTrue(WorkspaceImage.view(root, path, codec).isError)
        assertEquals(0, codec.boundsCalls)
    }

    @Test fun invalidFormatAndPixelBombAreRejectedBeforeDecode() {
        val root = workspace()
        val path = image(root)
        for (bounds in listOf(
            WorkspaceImage.Bounds(-1, -1, null), WorkspaceImage.Bounds(1, 1, "text/plain"),
            WorkspaceImage.Bounds(Int.MAX_VALUE, Int.MAX_VALUE, "image/png"),
            WorkspaceImage.Bounds(16_000, 16_000, "image/png"),
        )) {
            val codec = FakeCodec(bounds)
            assertTrue(WorkspaceImage.view(root, path, codec).isError)
            assertEquals(0, codec.sanitizeCalls)
        }
    }

    @Test fun sampleBoundsDecodedPixelsAndLongestEdge() {
        for ((width, height) in listOf(8000 to 6000, 16_000 to 1, 1400 to 2000, 1 to 1)) {
            val sample = WorkspaceImage.sample(WorkspaceImage.Bounds(width, height, "image/jpeg"))
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            assertTrue(w * h <= WorkspaceImage.MAX_DECODE_PIXELS)
            assertTrue(maxOf(w, h) <= WorkspaceImage.MAX_DECODE_EDGE)
        }
    }

    @Test fun responseUsesSanitizedPixelsAndNeverOriginalContainerBytes() {
        val root = workspace()
        val raw = "Exif\u0000GPS-TEST-METADATA".toByteArray()
        val path = image(root, bytes = raw)
        val codec = FakeCodec()
        val result = WorkspaceImage.view(root, path, codec)
        assertFalse(result.isError)
        assertEquals(1, codec.sanitizeCalls)
        assertEquals("image/jpeg", result.images.single().mimeType)
        assertArrayEquals(cleanJpeg, Base64.getDecoder().decode(result.images.single().data))
        assertFalse(result.text.contains("GPS-TEST-METADATA"))
        assertFalse(result.content().toString().contains("GPS-TEST-METADATA"))
    }

    @Test fun oversizedCodecOutputAndUnexpectedDecodedDimensionsAreRejected() {
        val root = workspace()
        val path = image(root)
        val badOutputs = listOf(
            WorkspaceImage.Encoded(ByteArray(WorkspaceImage.MAX_OUTPUT_BYTES + 1), 200, 100),
            WorkspaceImage.Encoded(cleanJpeg, Int.MAX_VALUE, Int.MAX_VALUE),
        )
        for (encoded in badOutputs) {
            assertTrue(WorkspaceImage.view(root, path, FakeCodec(encoded = encoded)).isError)
        }
    }

    @Test fun decoderExceptionsNeverExposeSourceOrSecretDetails() {
        val root = workspace()
        val codec = object : WorkspaceImage.Codec {
            override fun bounds(bytes: ByteArray): WorkspaceImage.Bounds = throw IllegalStateException("test-token-secret")
            override fun sanitize(bytes: ByteArray, sample: Int): WorkspaceImage.Encoded = error("not reached")
        }
        val result = WorkspaceImage.view(root, image(root), codec)
        assertTrue(result.isError)
        assertFalse(result.content().toString().contains("test-token-secret"))
        assertFalse(result.detail.orEmpty().contains("test-token-secret"))
    }
}
