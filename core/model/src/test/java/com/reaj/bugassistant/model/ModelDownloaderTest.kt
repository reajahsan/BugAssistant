package com.reaj.bugassistant.model

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

class ModelDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = Random(42).nextBytes(300_000)
    private lateinit var server: HttpServer
    private var honorRange = true
    private var lastRangeHeader: String? = null

    private val downloader = ModelDownloader(Dispatchers.IO)

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/model.gguf") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                lastRangeHeader = range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                when {
                    honorRange && from != null && from >= payload.size -> {
                        ex.sendResponseHeaders(416, -1)
                    }
                    honorRange && from != null -> {
                        val body = payload.copyOfRange(from, payload.size)
                        ex.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                        ex.sendResponseHeaders(206, body.size.toLong())
                        ex.responseBody.use { it.write(body) }
                    }
                    else -> {
                        ex.sendResponseHeaders(200, payload.size.toLong())
                        ex.responseBody.use { it.write(payload) }
                    }
                }
                ex.close()
            }
            start()
        }
    }

    @After fun stopServer() = server.stop(0)

    private fun spec(sha: String = sha256(payload)) = ModelSpec(
        id = "test",
        displayName = "Test",
        url = "http://127.0.0.1:${server.address.port}/model.gguf",
        fileName = "model.gguf",
        sizeBytes = payload.size.toLong(),
        sha256 = sha,
    )

    private fun target() = File(tmp.root, "model.gguf")
    private fun part() = File(tmp.root, "model.gguf.part")

    @Test fun `downloads, verifies and reports progress`() = runTest {
        val progress = mutableListOf<Int>()
        val file = downloader.download(spec(), target()) { progress += it }

        assertArrayEquals(payload, file.readBytes())
        assertFalse(part().exists())
        assertNull(lastRangeHeader)
        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test fun `resumes from an existing part file`() = runTest {
        part().writeBytes(payload.copyOfRange(0, 120_000))

        downloader.download(spec(), target())

        assertEquals("bytes=120000-", lastRangeHeader)
        assertArrayEquals(payload, target().readBytes())
    }

    @Test fun `restarts when the server ignores Range`() = runTest {
        honorRange = false
        part().writeBytes(ByteArray(50_000)) // garbage that must not be kept

        downloader.download(spec(), target())

        assertArrayEquals(payload, target().readBytes())
    }

    @Test fun `complete part file is verified without downloading again`() = runTest {
        part().writeBytes(payload)

        downloader.download(spec(), target())

        assertNull("no request expected", lastRangeHeader)
        assertArrayEquals(payload, target().readBytes())
    }

    @Test fun `checksum mismatch deletes the part file and fails`() = runTest {
        try {
            downloader.download(spec(sha = "0".repeat(64)), target())
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Checksum mismatch"))
        }
        assertFalse(target().exists())
        assertFalse(part().exists())
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
