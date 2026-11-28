package kr.snu.etlassistant.codex

import kr.snu.etlassistant.chat.ChatModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class GeneratedImagesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun item() = JSONObject().put("type", "imageGeneration").put("status", "completed").put("id", "exec-1")
    private fun png() = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)

    @Test fun savedImageAttachesEvenWithoutInlineResultAndSurvivesHistory() {
        val root = folder.newFolder("generated_images")
        val file = File(root, "thread/exec-1.png").apply { parentFile!!.mkdirs(); writeBytes(png()) }
        val a = GeneratedImages.attachment(item().put("savedPath", file.path).put("result", ""), listOf(root))!!
        assertTrue(a.isImage)
        assertFalse(a.referenceOnly)
        val history = File(folder.root, "chat.json")
        ChatModel(history).addFiles(listOf(a))
        val restored = ChatModel(history).apply { load() }
        assertEquals(a, restored.messages.single().attachments.single())
    }

    @Test fun oldEngineInlineImageIsSavedAndCanBeImportedAgain() {
        val root = File(folder.root, "generated_images")
        val event = item().put("result", Base64.getEncoder().encodeToString(png()))
        val first = GeneratedImages.attachment(event, listOf(root))!!
        val second = GeneratedImages.attachment(event, listOf(root))!!
        assertEquals(first.path, second.path)
        assertArrayEquals(png(), File(first.path!!).readBytes())
    }

    @Test fun imagesAreCopiedIntoTheShareableManagedAttachmentDirectory() {
        val source = folder.newFolder("codex-generated")
        val image = File(source, "exec-1.png").apply { writeBytes(png()) }
        val managed = File(folder.root, "workspace/etl-files/generated_images")
        val a = GeneratedImages.attachment(item().put("savedPath", image.path), listOf(source), managed)!!
        assertEquals(managed.canonicalPath, File(a.path!!).parentFile!!.canonicalPath)
        assertArrayEquals(png(), File(a.path).readBytes())
        assertTrue(image.isFile)
        val inline = GeneratedImages.attachment(item().put("id", "exec-2").put("result", Base64.getEncoder().encodeToString(png())), listOf(source), managed)!!
        assertEquals(managed.canonicalPath, File(inline.path!!).parentFile!!.canonicalPath)
    }

    @Test fun rejectsFailedIncompleteAndNonImageEvents() {
        val root = folder.newFolder()
        for (status in listOf("in_progress", "failed", "")) {
            assertNull(GeneratedImages.attachment(item().put("status", status), listOf(root)))
        }
        assertNull(GeneratedImages.attachment(item().put("type", "agentMessage"), listOf(root)))
        assertNull(GeneratedImages.attachment(item().put("result", "not base64"), listOf(root)))
        assertNull(GeneratedImages.attachment(item().put("result", "aGVsbG8="), listOf(root)))
    }

    @Test fun rejectsOutsidePathsAndSymlinkEscapes() {
        val root = folder.newFolder("generated_images")
        val outside = File(folder.root, "secret.png").apply { writeBytes(png()) }
        assertNull(GeneratedImages.attachment(item().put("savedPath", outside.path), listOf(root)))
        val link = File(root, "escape.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(GeneratedImages.attachment(item().put("savedPath", link.path), listOf(root)))
    }

    @Test fun rejectsOversizedArtifactsAndSanitizesInlineId() {
        val root = folder.newFolder("generated_images")
        val large = File(root, "large.png")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(GeneratedImages.MAX_BYTES + 1L) }
        assertNull(GeneratedImages.attachment(item().put("savedPath", large.path), listOf(root)))
        val a = GeneratedImages.attachment(item().put("id", "../../escape").put("result", Base64.getEncoder().encodeToString(png())), listOf(root))!!
        assertEquals(root.canonicalPath, File(a.path!!).parentFile!!.canonicalPath)
    }

    @Test fun restoresLegacyMarkdownBareAndFileLinksOnce() {
        val source = folder.newFolder("generated_images")
        val image = File(source, "exec-image.png").apply { writeBytes(png()) }
        val managed = File(folder.root, "workspace/etl-files/generated_images")
        val text = "[이미지](${image.path})\n`${image.path}`\n![원](file://${image.path})"
        val files = GeneratedImages.fromText(text, listOf(source), managed)
        assertEquals(1, files.size)
        assertArrayEquals(png(), File(files.single().path!!).readBytes())
        assertTrue(GeneratedImages.fromText(text, listOf(source), managed, setOf(files.single().path!!)).isEmpty())
    }

    @Test fun legacyRecoveryNeverFetchesRemoteLinksOrImportsOutsideGeneratedRoots() {
        val source = folder.newFolder("generated_images")
        val image = File(source, "exec-image.png").apply { writeBytes(png()) }
        val outside = File(folder.root, "private.png").apply { writeBytes(png()) }
        val managed = File(folder.root, "managed")
        for (text in listOf("![x](https://example.com${image.path})", "[x](${outside.path})", "[x](${source.path}/missing.png)")) {
            assertTrue(text, GeneratedImages.fromText(text, listOf(source), managed).isEmpty())
        }
        val link = File(source, "escape.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(GeneratedImages.fromText(link.path, listOf(source), managed).isEmpty())
    }

    @Test fun restoresEncodedPathAndGeneratedDirectoryAlias() {
        val source = folder.newFolder("generated images")
        val image = File(source, "exec+image.png").apply { writeBytes(png()) }
        val alias = File(folder.root, "alias")
        Files.createSymbolicLink(alias.toPath(), source.toPath())
        val managed = File(folder.root, "managed")
        val files = GeneratedImages.fromText("[원](file://${alias.path}/exec+image.png)", listOf(source), managed)
        assertEquals(1, files.size)
        val encoded = image.path.replace(" ", "%20")
        assertEquals(1, GeneratedImages.fromText("[원]($encoded)", listOf(source), managed).size)
    }
}
