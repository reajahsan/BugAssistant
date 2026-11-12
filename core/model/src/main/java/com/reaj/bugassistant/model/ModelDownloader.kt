package com.reaj.bugassistant.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a [ModelSpec] to "<target>.part", verifies its SHA-256, and only then renames it
 * to [target], so a partial or corrupt file is never handed to llama.cpp.
 * An interrupted download resumes from the existing .part file via an HTTP Range request.
 */
class ModelDownloader(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** @param onProgress percentage 0..100, called on the IO thread and only when it changes. */
    suspend fun download(spec: ModelSpec, target: File, onProgress: (Int) -> Unit = {}): File =
        withContext(io) {
            val dir = requireNotNull(target.parentFile) { "target must have a parent directory" }
            dir.mkdirs()
            val part = File(dir, target.name + ".part")
            val existing = if (part.exists()) part.length() else 0L

            if (existing < spec.sizeBytes) {
                fetch(spec, part, existing, dir, onProgress)
            }
            onProgress(100)

            val actual = sha256(part)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("Checksum mismatch for ${spec.fileName} (got $actual)")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
            target
        }

    private suspend fun fetch(spec: ModelSpec, part: File, existing: Long, dir: File, onProgress: (Int) -> Unit) {
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0) return // .part already complete
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) throw IOException("HTTP $code for ${spec.url}")

            val start = if (resuming) existing else 0L // 200 means the server ignored Range: restart
            val total = spec.sizeBytes
            if (total - start > dir.usableSpace) throw IOException("Not enough free storage for ${spec.fileName}")

            conn.inputStream.use { input ->
                FileOutputStream(part, resuming).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var done = start
                    var lastPct = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt().coerceAtMost(99) // 100 = verified
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                    if (done != total) throw IOException("Download incomplete ($done of $total bytes); retry to resume")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val BUFFER_SIZE = 256 * 1024
    }
}
