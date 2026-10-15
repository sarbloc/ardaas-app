package io.sarbloc.ardaas.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One artifact download, abstracted so the installer's state machine can be
 * tested without a network (see `FakeModelFileFetcher` in the test source set)
 * — port of `ModelFileFetching` in `Ardaas/Translation/ModelFileFetcher.swift`.
 *
 * iOS resumes with an opaque in-memory URLSession token; here the resume
 * state *is* the partial file, so it also survives an app kill.
 */
interface ModelFileFetching {
    /**
     * Downloads [url] into [partial], continuing from whatever prefix
     * [partial] already holds. Returns once [partial] holds exactly
     * [expectedBytes]; the caller verifies the hash and moves it into place.
     *
     * A transport failure or cancellation leaves the prefix on disk so the
     * next attempt resumes rather than restarts.
     *
     * @param allowCellular false means unmetered networks only. When the
     *   active network is metered the call fails with
     *   [TranslationError.CellularNotAllowed] before a byte is transferred,
     *   rather than falling back to the user's data plan.
     * @param onProgress total bytes in [partial] so far, from any thread.
     */
    suspend fun fetch(
        url: URL,
        partial: File,
        expectedBytes: Long,
        allowCellular: Boolean,
        onProgress: (Long) -> Unit,
    )
}

/**
 * Opens connections on a network the download may use. The only
 * Android-specific piece of the fetch, split out so the HTTP logic (Range
 * resume, status handling, cancellation) is exercised by plain JVM tests
 * against a local server.
 */
fun interface DownloadNetwork {
    /**
     * Opens [url] on a permitted network, or throws
     * [TranslationError.CellularNotAllowed] when only a metered one is up and
     * [allowCellular] is false.
     */
    fun openConnection(url: URL, allowCellular: Boolean): HttpURLConnection
}

/**
 * The production network gate. "Cellular" is read as *metered*, the thing
 * the guard actually protects: a phone hotspot or a metered Wi-Fi costs the
 * user exactly what mobile data does (iOS guards `expensive` and
 * `constrained` for the same reason).
 *
 * The connection is opened through [android.net.Network.openConnection], so
 * the socket is bound to the network that passed the check: if Wi-Fi drops
 * mid-transfer the transfer fails (and resumes later) instead of silently
 * moving onto mobile data.
 */
class ConnectivityDownloadNetwork(context: Context) : DownloadNetwork {
    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    override fun openConnection(url: URL, allowCellular: Boolean): HttpURLConnection {
        val manager = connectivity ?: throw TranslationError.Download("Connectivity service unavailable.")
        val network = manager.activeNetwork ?: throw TranslationError.Download("No network connection.")
        val unmetered = manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        if (!allowCellular && !unmetered) throw TranslationError.CellularNotAllowed
        return network.openConnection(url) as? HttpURLConnection
            ?: throw TranslationError.Download("Not an HTTP URL: $url")
    }
}

/**
 * The production fetcher, on [HttpURLConnection] so the feature adds no
 * dependency. Streams to disk in 64 KiB chunks with progress per chunk,
 * resumes a partial file with an HTTP `Range` request, and cancels
 * mid-transfer by disconnecting (a blocked read does not otherwise notice
 * coroutine cancellation). Android's OkHttp-backed HttpURLConnection closes
 * the socket on disconnect, so the read ends at once; on any implementation
 * that does not, the read still ends at [readTimeoutMillis] and is reported
 * as the cancellation it is.
 */
class HttpModelFileFetcher(
    private val network: DownloadNetwork,
    private val log: TranslationLogger = TranslationLogger.Android,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 30_000,
    // Between bytes, not for the whole transfer: 359 MB on a slow link takes
    // as long as it takes, but a stalled socket should fail and be resumed.
    private val readTimeoutMillis: Int = 60_000,
) : ModelFileFetching {

    override suspend fun fetch(
        url: URL,
        partial: File,
        expectedBytes: Long,
        allowCellular: Boolean,
        onProgress: (Long) -> Unit,
    ) = withContext(ioDispatcher) {
        val resumeFrom = resumeOffset(partial, expectedBytes)
        if (resumeFrom == expectedBytes) {
            // Already whole (e.g. verified once, then the move failed). Asking
            // for `bytes=N-` here would only earn a 416.
            onProgress(expectedBytes)
            return@withContext
        }
        val connection = network.openConnection(url, allowCellular)
        try {
            disconnectOnCancel(connection) {
                transfer(connection, partial, resumeFrom, expectedBytes, onProgress)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Bytes already on disk worth keeping. A prefix longer than the artifact cannot be one. */
    private fun resumeOffset(partial: File, expectedBytes: Long): Long {
        if (!partial.isFile) return 0
        val length = partial.length()
        if (length <= expectedBytes) return length
        discard(partial, "it is longer than the expected $expectedBytes bytes")
        return 0
    }

    private suspend fun transfer(
        connection: HttpURLConnection,
        partial: File,
        resumeFrom: Long,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        // Transparent gzip would make the Range offset count decompressed
        // bytes; the artifacts are binary and gain nothing from it anyway.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")

        try {
            val append = acceptResponse(connection, partial, resumeFrom)
            val start = if (append) resumeFrom else 0L
            onProgress(start)
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    copy(input, output, partial, start, expectedBytes, onProgress)
                }
            }
        } catch (e: IOException) {
            // A disconnect from the cancellation watchdog surfaces here as a
            // socket error; report it as the cancellation it is.
            currentCoroutineContext().ensureActive()
            throw TranslationError.Download(e.message ?: e.toString())
        }
    }

    /**
     * Decides from the status whether the body continues [partial] (true) or
     * replaces it (false). Anything else is a typed failure: a non-2xx body
     * is an error page, not model bytes.
     */
    private fun acceptResponse(connection: HttpURLConnection, partial: File, resumeFrom: Long): Boolean {
        val code = connection.responseCode
        return when {
            code == HttpURLConnection.HTTP_OK -> false // Range ignored: start over.
            code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0 &&
                contentRangeStart(connection) == resumeFrom -> true
            code == HttpURLConnection.HTTP_PARTIAL -> {
                discard(partial, "the server answered with an unexpected range")
                throw TranslationError.Download("HTTP 206 with unexpected Content-Range")
            }
            code == HTTP_RANGE_NOT_SATISFIABLE -> {
                // The prefix does not fit the file the server has; resuming
                // into it again would fail the same way.
                discard(partial, "the server rejected its range")
                throw TranslationError.Download("HTTP $code")
            }
            else -> throw TranslationError.Download("HTTP $code")
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: FileOutputStream,
        partial: File,
        start: Long,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = start
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (written + count > expectedBytes) {
                // Bound the write: never let a misbehaving server fill the disk.
                discard(partial, "the server sent more than $expectedBytes bytes")
                throw TranslationError.Download("Received more than the expected $expectedBytes bytes")
            }
            try {
                output.write(buffer, 0, count)
            } catch (e: IOException) {
                throw TranslationError.FileSystem("Writing ${partial.name}: ${e.message ?: e}")
            }
            written += count
            onProgress(written)
        }
        if (written != expectedBytes) {
            // Keep the prefix: the next attempt resumes from it.
            throw TranslationError.Download("Connection closed after $written of $expectedBytes bytes")
        }
    }

    private fun discard(partial: File, reason: String) {
        if (partial.exists() && !partial.delete()) {
            log.warn("Could not delete ${partial.name} after $reason", null)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** The first byte offset of a `Content-Range: bytes N-M/T` header, or null. */
        fun contentRangeStart(connection: HttpURLConnection): Long? =
            connection.getHeaderField("Content-Range")
                ?.removePrefix("bytes ")
                ?.substringBefore('-')
                ?.trim()
                ?.toLongOrNull()

        /**
         * Runs blocking IO [block] with a watchdog that disconnects
         * [connection] the moment the coroutine is cancelled, so a read parked
         * on a slow socket fails at once instead of after the read timeout.
         */
        suspend fun <T> disconnectOnCancel(connection: HttpURLConnection, block: suspend () -> T): T =
            coroutineScope {
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    block()
                } finally {
                    watchdog.cancel()
                }
            }
    }
}
