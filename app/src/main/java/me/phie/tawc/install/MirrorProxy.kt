package me.phie.tawc.install

import java.net.URL

/**
 * Dev-time URL rewriter that funnels distro-mirror fetches through a
 * host-side caching reverse proxy. See `notes/cache-proxy.md`.
 *
 * Wraps an upstream URL `<scheme>://<host>/<path>?<query>` as
 * `<base>/<scheme>/<host>/<path>?<query>` so a single nginx with the
 * `^/proxy/(https?)/([^/]+)/(.*)$` location block can route to any
 * upstream and key the cache off the upstream URL. The query string
 * follows `proxy_pass` upstream but the cache key strips it (one cache
 * entry per path, regardless of signed-redirect tokens).
 *
 * Verification URLs (PGP `.sig`, GitHub Releases REST API, Void's
 * `sha256sum.txt`) **also** flow through this in dev builds. The
 * alternative — tarball cached, digests fetched fresh — produces a
 * permanent verification mismatch the moment upstream rotates the
 * artifact, with no in-band recovery. Proxying a `.sig` is safe (it is
 * still checked against a key in the APK, so tampering fails closed);
 * proxying a bare digest is only as good as the proxy, which is why
 * release builds never construct a [MirrorProxy].
 *
 * Construction is intentionally light — pass the bare base URL the
 * user supplied via `--es mirrorProxy` and we'll cope with a missing
 * trailing slash.
 */
class MirrorProxy(baseUrl: String) {
    /** Always normalised to end with exactly one `/`. */
    val base: String = baseUrl.trimEnd('/') + "/"

    /**
     * Rewrite [url] to traverse this proxy. Throws on non-http(s) URLs;
     * we never need to proxy a `file://` or git ref, so a non-supported
     * scheme is a programming error worth surfacing.
     */
    fun wrap(url: String): String {
        val u = URL(url)
        val scheme = u.protocol
        require(scheme == "http" || scheme == "https") {
            "MirrorProxy.wrap: unsupported scheme '$scheme' in $url"
        }
        // URL.authority is host[:port]; URL.file is path[?query]. Both
        // are pre-encoded forms suitable for direct concatenation.
        val authority = u.authority
        val pathAndQuery = u.file.removePrefix("/")
        return "$base$scheme/$authority/$pathAndQuery"
    }
}
