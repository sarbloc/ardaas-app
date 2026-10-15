package io.sarbloc.ardaas.ui.sources

import java.net.URI
import java.net.URISyntaxException

/** How one entry of a variant's `sources` array is shown on About & Sources. */
sealed interface SourceEntry {
    /** A web address: shown as its host, opening [url] when tapped. */
    data class Link(val url: String, val host: String) : SourceEntry

    /** A description of a source (a book, an app, a borrowed layer): shown as written. */
    data class Prose(val text: String) : SourceEntry

    companion object {
        /**
         * Classifies [source]. Only a string that is wholly an http(s) URL
         * with a host is a [Link] — so a description that merely *begins*
         * with an address ("https://… and /383 — BaniDB …") is shown in full
         * as [Prose] rather than truncated to a host and a broken link.
         */
        fun of(source: String): SourceEntry {
            val uri = try {
                URI(source)
            } catch (_: URISyntaxException) {
                return Prose(source)
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            return if ((scheme == "https" || scheme == "http") && !host.isNullOrEmpty()) {
                Link(url = source, host = host)
            } else {
                Prose(source)
            }
        }
    }
}
