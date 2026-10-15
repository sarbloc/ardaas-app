package io.sarbloc.ardaas.translation

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

/**
 * The real HTTP fetcher against a loopback server: Range resume, the
 * server-ignored-Range restart, status handling, the byte bound, the cellular
 * gate and mid-transfer cancellation. iOS cannot test its transport like this;
 * only the Android network check ([ConnectivityDownloadNetwork]) is left out.
 */
class HttpModelFileFetcherTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val body = ByteArray(300_000) { (it % 251).toByte() }

    @Volatile private var honoursRange = true
    @Volatile private var status = 200
    @Volatile private var trailingBytes = 0
    @Volatile private var lastRange: String? = null
    @Volatile private var requests = 0
    @Volatile private var stall: CountDownLatch? = null

    /** Plain JVM connection: the seam the Android network gate plugs into. */
    private val direct = DownloadNetwork { url, _ -> url.openConnection() as HttpURLConnection }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin", ::serve)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun tearDown() {
        stall?.countDown()
        server.stop(0)
    }

    private fun serve(exchange: HttpExchange) {
        try {
            requests++
            val range = exchange.requestHeaders.getFirst("Range")
            lastRange = range
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1)
                return
            }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            val payload = body + ByteArray(trailingBytes)
            if (start != null && honoursRange) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, (payload.size - start).toLong())
                exchange.responseBody.write(payload, start, payload.size - start)
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                val latch = stall
                if (latch != null) {
                    exchange.responseBody.write(payload, 0, 1024)
                    exchange.responseBody.flush()
                    latch.await(10, TimeUnit.SECONDS)
                }
                exchange.responseBody.write(payload)
            }
        } finally {
            exchange.close()
        }
    }

    private val url get() = URL("http://127.0.0.1:${server.address.port}/model.bin")

    private fun fetcher(network: DownloadNetwork = direct) =
        HttpModelFileFetcher(network, RecordingLogger(), readTimeoutMillis = 5_000)

    private fun partialFile() = File(temp.root, "model.bin.part")

    @Test
    fun aFreshDownloadStreamsTheWholeBodyWithProgress() = runBlocking {
        val partial = partialFile()
        val progress = mutableListOf<Long>()

        fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) { progress += it }

        assertArrayEquals(body, partial.readBytes())
        assertNull("a fresh download must not send a Range", lastRange)
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun aPartialFileIsResumedWithARangeRequest() = runBlocking {
        val partial = partialFile().apply { writeBytes(body.copyOf(100_000)) }
        val progress = mutableListOf<Long>()

        fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) { progress += it }

        assertEquals("bytes=100000-", lastRange)
        assertArrayEquals(body, partial.readBytes())
        assertEquals("progress counts the bytes already held", 100_000L, progress.first())
    }

    @Test
    fun aServerThatIgnoresRangeRestartsFromZero() = runBlocking {
        honoursRange = false
        val partial = partialFile().apply { writeBytes(body.copyOf(100_000)) }

        fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) {}

        assertArrayEquals("the 200 body replaces the prefix, never appends to it", body, partial.readBytes())
    }

    @Test
    fun aCompletePartialIsNotRefetched() = runBlocking {
        val partial = partialFile().apply { writeBytes(body) }
        fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) {}
        assertEquals("no request should have been made", 0, requests)
        assertArrayEquals(body, partial.readBytes())
    }

    @Test
    fun aNon2xxStatusIsATypedDownloadError() = runBlocking {
        status = 404
        val partial = partialFile()
        try {
            fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) {}
            fail("expected a Download error")
        } catch (e: TranslationError.Download) {
            assertEquals("HTTP 404", e.detail)
        }
        assertFalse("an error page must never be written as model bytes", partial.exists() && partial.length() > 0)
    }

    @Test
    fun moreBytesThanPinnedAreRefusedAndDiscarded() = runBlocking {
        trailingBytes = 10
        val partial = partialFile()
        try {
            fetcher().fetch(url, partial, body.size.toLong(), allowCellular = false) {}
            fail("expected a Download error")
        } catch (e: TranslationError.Download) {
            assertTrue(e.detail.contains("more than"))
        }
        assertFalse(partial.exists())
    }

    @Test
    fun aShortBodyFailsButKeepsThePrefixForResume() = runBlocking {
        val partial = partialFile()
        try {
            fetcher().fetch(url, partial, body.size + 50L, allowCellular = false) {}
            fail("expected a Download error")
        } catch (e: TranslationError.Download) {
            assertTrue(e.detail.contains("Connection closed"))
        }
        assertEquals(body.size.toLong(), partial.length())
    }

    @Test
    fun theCellularGateRefusesBeforeAnyRequest() = runBlocking {
        val meteredOnly = DownloadNetwork { _, allowCellular ->
            if (!allowCellular) throw TranslationError.CellularNotAllowed
            direct.openConnection(url, true)
        }
        try {
            fetcher(meteredOnly).fetch(url, partialFile(), body.size.toLong(), allowCellular = false) {}
            fail("expected CellularNotAllowed")
        } catch (e: TranslationError.CellularNotAllowed) {
            // Expected.
        }
        assertEquals(0, requests)
        assertFalse(partialFile().exists())

        fetcher(meteredOnly).fetch(url, partialFile(), body.size.toLong(), allowCellular = true) {}
        assertArrayEquals(body, partialFile().readBytes())
    }

    /**
     * Cancelling a transfer parked on a stalled socket must surface as
     * cancellation — not as a Download failure — and keep the prefix.
     *
     * How fast it lands depends on the platform: Android's OkHttp-backed
     * HttpURLConnection closes the socket on disconnect(), so the read ends at
     * once; the JDK's implementation serialises disconnect() behind the
     * blocked read, so here it lands at the read timeout. The bound asserted
     * is therefore "about one read timeout", not "immediately".
     */
    @Test
    fun cancellingAStalledTransferIsCancellationAndKeepsThePrefix() = runBlocking {
        val latch = CountDownLatch(1).also { stall = it }
        val partial = partialFile()
        val started = CountDownLatch(1)
        val stalledFetcher = HttpModelFileFetcher(direct, RecordingLogger(), readTimeoutMillis = 1_000)

        val fetch = async(Dispatchers.Default) {
            stalledFetcher.fetch(url, partial, body.size.toLong(), allowCellular = false) {
                if (it > 0) started.countDown()
            }
        }
        assertTrue("the transfer never started", started.await(5, TimeUnit.SECONDS))
        val cancelledAt = System.nanoTime()
        fetch.cancel()
        try {
            fetch.await()
            fail("a cancelled fetch must not complete")
        } catch (e: CancellationException) {
            // Expected: cancellation, not TranslationError.Download.
        }
        val elapsedMillis = (System.nanoTime() - cancelledAt) / 1_000_000
        latch.countDown()
        assertTrue("cancel took ${elapsedMillis}ms", elapsedMillis < 4_000)
        assertEquals("the prefix stays for resume", 1024L, partial.length())
    }
}
