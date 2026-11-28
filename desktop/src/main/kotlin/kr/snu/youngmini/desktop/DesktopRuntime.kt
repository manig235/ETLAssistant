package kr.snu.youngmini.desktop

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Pinned upstream runtime; Windows needs no Android ELF patch or CONNECT proxy. */
object DesktopRuntime {
    const val VERSION = "rust-v0.170.0"
    private data class Asset(val archive: String, val archiveHash: String, val binary: String, val hash: String, val installed: String)
    private val assets = listOf(
        Asset("codex-x86_64-pc-windows-msvc.exe.zip", "b1634415b6ecb87fea80691580dfe49f83fd46f0ad93f128ba029604d7faa158",
            "codex-x86_64-pc-windows-msvc.exe", "8d06dc231dba649c7d48d3eecab239c452a1ac498c41dd6492a6f301b90a22ce", "codex.exe"),
        Asset("codex-code-mode-host-x86_64-pc-windows-msvc.exe.zip", "030b40d53eb7fb86ee45e6655c85cef651165b228307034f5f67f5a1e9d3a778",
            "codex-code-mode-host-x86_64-pc-windows-msvc.exe", "4dd22666894cbad119023ed64c973105050146f59847bacb8094dfe456efbb62", "codex-code-mode-host.exe"),
    )

    fun ensure(paths: AppPaths, progress: (String) -> Unit): File {
        // Explicit development override. Production launchers do not set this variable.
        System.getenv("YOUNGMIN_CODEX")?.let { path ->
            return File(path).canonicalFile.also { if (!it.isFile) throw IOException("개발용 Codex 경로를 확인해 주세요") }
        }
        if (!Platform.windows) throw IOException("배포용 비서는 Windows x64에서 실행해 주세요. 개발 시 YOUNGMIN_CODEX를 지정할 수 있어요.")
        val arch = System.getProperty("os.arch")
        if (arch !in setOf("amd64", "x86_64")) throw IOException("이 첫 버전은 Windows x64용이에요")
        val home = System.getProperty("youngmini.home")?.let(::File)
            ?: System.getProperty("compose.application.resources.dir")?.let(::File)
        val bundled = home?.let { File(it, "runtime") }
        val destination = File(paths.root, "runtime/$VERSION").apply { mkdirs() }
        var binary: File? = null
        for (asset in assets) {
            progress("비서를 준비하고 있어요 · ${if (asset.installed == "codex.exe") "1/2" else "2/2"}")
            val shipped = bundled?.let { File(it, asset.installed) }
            val target = if (shipped?.isFile == true) shipped else File(destination, asset.installed)
            if (!target.isFile || sha256(target) != asset.hash) {
                if (target == shipped) throw IOException("비서 실행 파일의 무결성을 확인하지 못했어요. 배포 파일을 다시 받아 주세요.")
                val archive = File(destination, asset.archive + ".part")
                val extracted = File(destination, asset.installed + ".part")
                try {
                    download("https://github.com/openai/codex/releases/download/$VERSION/${asset.archive}", archive, progress)
                    if (sha256(archive) != asset.archiveHash) throw IOException("비서 다운로드 검사에 실패했어요. 다시 시도해 주세요.")
                    ZipInputStream(archive.inputStream().buffered()).use { zip ->
                        var found = false
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.substringAfterLast('/') == asset.binary) {
                                extracted.outputStream().use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > 600L shl 20) throw IOException("비서 실행 파일이 너무 커요")
                                        out.write(buffer, 0, count)
                                    }
                                }
                                found = true
                                break
                            }
                        }
                        if (!found) throw IOException("압축 파일에서 비서를 찾지 못했어요")
                    }
                    if (sha256(extracted) != asset.hash) throw IOException("비서 실행 파일 검사에 실패했어요")
                    java.nio.file.Files.move(extracted.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { archive.delete(); extracted.delete() }
            }
            if (asset.installed == "codex.exe") binary = target
        }
        return binary!!
    }

    private fun download(address: String, target: File, progress: (String) -> Unit) {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https") throw IOException("안전하지 않은 다운로드 주소예요")
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("다운로드 주소가 없어요")
                    url = URL(url, location)
                    return@repeat
                }
                if (status != 200) throw IOException("비서를 받지 못했어요(HTTP $status). 네트워크를 확인해 주세요.")
                connection.inputStream.use { input -> target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var shown = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("준비를 중단했어요")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 300L shl 20) throw IOException("다운로드가 너무 커요")
                        out.write(buffer, 0, count)
                        if (total - shown > 4L shl 20) { shown = total; progress("처음 실행에 필요한 비서를 받고 있어요 · ${total shr 20} MB") }
                    }
                } }
                return
            } finally { connection.disconnect() }
        }
        throw IOException("비서 다운로드 주소를 확인하지 못했어요")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
