package page.click2.sdk.internal

import java.io.ByteArrayOutputStream

/**
 * A lenient split of `scheme://authority/path?query#fragment`. Unlike java.net.URI it accepts what
 * browsers leave unencoded in App Links (`|`, `{}`, bare `%`, `#a#b`). Returns null without `//`.
 */
internal class UrlParts private constructor(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    /** Null without a port; empty for a bare `host:`. */
    val port: String?,
    /** Raw (still percent-encoded) path, possibly empty. */
    val path: String,
) {
    companion object {
        private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

        fun parse(url: String): UrlParts? {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            val scheme = url.substring(0, colon)
            if (!SCHEME.matches(scheme) || !url.startsWith("//", colon + 1)) return null
            val authorityStart = colon + 3
            // Browsers treat '\' like '/' in http(s) URLs.
            val authorityEnd = url.indexOfAny(charArrayOf('/', '\\', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
            val authority = url.substring(authorityStart, authorityEnd)
            val at = authority.lastIndexOf('@')
            val hostPort = authority.substring(at + 1)
            val portStart = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                if (close + 1 < hostPort.length && hostPort[close + 1] != ':') return null
                if (close + 1 < hostPort.length) close + 1 else -1
            } else {
                hostPort.lastIndexOf(':')
            }
            val pathEnd = url.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) url.length else it }
            return UrlParts(
                scheme = scheme,
                userInfo = if (at >= 0) authority.substring(0, at) else null,
                host = if (portStart >= 0) hostPort.substring(0, portStart) else hostPort,
                port = if (portStart >= 0) hostPort.substring(portStart + 1) else null,
                path = url.substring(authorityEnd, pathEnd).replace('\\', '/'),
            )
        }
    }
}

/** Percent-encoding that never throws. */
internal object Percent {
    private const val HEX = "0123456789ABCDEF"

    // RFC 3986 unreserved + reserved characters, except '#' and '%' (handled separately) and '[' ']'.
    private val ALLOWED = BooleanArray(128).also { allowed ->
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" + "-._~:/?@!$&'()*+,;=").forEach { allowed[it.code] = true }
    }

    /** Decodes `%XX` escapes as UTF-8. Invalid escapes stay as they are; `+` stays a plus. */
    fun decode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) out.append(bytes.toByteArray().toString(Charsets.UTF_8)).also { bytes.reset() }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && isEscape(value, i)) {
                bytes.write(hex(value[i + 1]) * 16 + hex(value[i + 2]))
                i += 3
            } else {
                flush()
                out.append(c)
                i++
            }
        }
        flush()
        return out.toString()
    }

    /**
     * Percent-encodes what isn't valid in a URL (spaces, control and non-ASCII characters, `|`,
     * `{}`, a bare `%`, a second `#`) and leaves valid escapes and URL syntax alone.
     */
    fun encodeInvalid(url: String): String = buildString(url.length) {
        var seenHash = false
        var i = 0
        while (i < url.length) {
            val c = url[i]
            when {
                c == '%' && isEscape(url, i) -> append(c)
                c == '#' && !seenHash -> append(c).also { seenHash = true }
                c.code < 128 && ALLOWED[c.code] -> append(c)
                else -> {
                    val end = if (c.isHighSurrogate() && i + 1 < url.length && url[i + 1].isLowSurrogate()) i + 2 else i + 1
                    url.substring(i, end).toByteArray(Charsets.UTF_8).forEach { b ->
                        val v = b.toInt() and 0xFF
                        append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                    }
                    i = end
                    continue
                }
            }
            i++
        }
    }

    private fun isEscape(s: String, i: Int) = i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0

    private fun hex(c: Char): Int = if (c.code < 128) Character.digit(c, 16) else -1
}
