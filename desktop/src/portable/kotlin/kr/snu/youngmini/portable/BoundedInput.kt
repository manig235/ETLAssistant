package kr.snu.youngmini.portable

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Android 26에서도 동작하는 상한 읽기. 호출자가 스트림을 닫는다. */
internal fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    require(limit >= 0)
    val out = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192))
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            out.write(byte)
        } else out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun InputStream.readBounded(limit: Int, message: String): ByteArray {
    require(limit in 0 until Int.MAX_VALUE)
    val bytes = readNBytesCompat(limit + 1)
    if (bytes.size > limit) throw IOException(message)
    return bytes
}
