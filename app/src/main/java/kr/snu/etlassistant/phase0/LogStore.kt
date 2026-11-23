package kr.snu.etlassistant.phase0

import android.os.Handler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 프로세스 수명 동안 유지되는 로그 버퍼. 액티비티가 재생성돼도 내용이 남는다.
 * 어느 스레드에서든 [line]을 부를 수 있고, UI 갱신은 메인 스레드에서 묶어서 한다.
 */
class LogStore(private val main: Handler) {

    interface Listener {
        fun onAppend(text: String)
        fun onReset(full: String)
    }

    private val lock = Any()
    private val all = StringBuilder()
    private val pending = StringBuilder()
    private var resetPending = false
    private var flushPosted = false
    private var listener: Listener? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val flush = Runnable {
        val (reset, text) = synchronized(lock) {
            flushPosted = false
            val r = resetPending
            resetPending = false
            val t = if (r) all.toString() else pending.toString()
            pending.setLength(0)
            r to t
        }
        val l = listener ?: return@Runnable
        if (reset) l.onReset(text) else if (text.isNotEmpty()) l.onAppend(text)
    }

    /**
     * 디버그용: 설정하면 로그를 이 파일(앱 내부 저장소)에도 이어 쓴다. `adb run-as`로 읽을 수 있다.
     * [MAX_FILE_BYTES]를 넘으면 `이름.1`로 한 번만 돌려 둔다(파일 두 개, 합쳐 약 2MB까지).
     */
    var mirror: java.io.File? = null
        set(v) = synchronized(lock) {
            field = v
            mirrorBytes = v?.length() ?: 0L
            if (mirrorBytes > MAX_FILE_BYTES) rotate()
        }
    private var mirrorBytes = 0L

    /** 지금 파일을 `.1`로 옮기고(이전 `.1`은 지움) 새로 시작한다. [lock]을 잡고 부른다. */
    private fun rotate() {
        val f = mirror ?: return
        val old = java.io.File(f.path + ".1")
        old.delete()
        if (!f.renameTo(old)) f.delete()
        mirrorBytes = 0L
    }

    fun line(raw: String) {
        val msg = LogRedact.redact(raw)
        val stamped = synchronized(timeFmt) { timeFmt.format(Date()) } + " " + msg + "\n"
        synchronized(lock) {
            mirror?.let { f ->
                try {
                    val bytes = stamped.toByteArray(Charsets.UTF_8)
                    if (mirrorBytes + bytes.size > MAX_FILE_BYTES) rotate()
                    java.io.FileOutputStream(f, true).use { it.write(bytes) }
                    mirrorBytes += bytes.size
                } catch (_: java.io.IOException) {
                }
            }
            all.append(stamped)
            pending.append(stamped)
            if (all.length > MAX_CHARS) {
                all.delete(0, all.length - TRIM_TO)
                resetPending = true
            }
            if (!flushPosted) {
                flushPosted = true
                main.postDelayed(flush, 50)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            mirror?.let { f ->
                try {
                    f.writeText("")
                } catch (_: java.io.IOException) {
                }
                java.io.File(f.path + ".1").delete()
                mirrorBytes = 0L
            }
            all.setLength(0)
            pending.setLength(0)
            resetPending = true
            if (!flushPosted) {
                flushPosted = true
                main.post(flush)
            }
        }
    }

    fun snapshot(): String = synchronized(lock) { all.toString() }

    /** 메인 스레드에서 호출. 등록 즉시 전체 내용을 onReset으로 넘긴다. */
    fun setListener(l: Listener?) {
        listener = l
        if (l != null) {
            val full = synchronized(lock) {
                pending.setLength(0)
                resetPending = false
                all.toString()
            }
            l.onReset(full)
        }
    }

    companion object {
        private const val MAX_CHARS = 400_000
        private const val TRIM_TO = 300_000
        /** 로그 파일 하나의 상한(1MB). */
        const val MAX_FILE_BYTES = 1_000_000L
    }
}
