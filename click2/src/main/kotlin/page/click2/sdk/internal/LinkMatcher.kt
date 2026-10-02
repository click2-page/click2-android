package page.click2.sdk.internal

import page.click2.sdk.Click2Config

/**
 * Decides which URLs are click2 links (spec/fixtures/link-matching.json). Parses leniently with
 * [UrlParts]: App Links often carry characters java.net.URI rejects.
 */
internal class LinkMatcher(hosts: List<String>) {
    private val hosts = hosts.map(Click2Config::normalizeHost).toSet()

    fun matches(url: String): Boolean = hostOf(url) != null

    /** The configured host this link will be resolved against, or null when it isn't a click2 link. */
    fun hostOf(url: String): String? {
        val parts = UrlParts.parse(url) ?: return null
        if (!parts.scheme.equals("https", ignoreCase = true) || parts.userInfo != null) return null
        if (parts.port != null && parts.port.isNotEmpty() && (parts.port.any { it !in '0'..'9' } || parts.port.toIntOrNull() != 443)) return null
        val host = Click2Config.normalizeHost(parts.host)
        if (host !in hosts || !isLinkPath(Percent.decode(parts.path))) return null
        return host
    }

    private fun isLinkPath(decodedPath: String): Boolean {
        val path = decodedPath.trim('/')
        if (path.isEmpty()) return false
        val first = path.substringBefore('/')
        if (first.lowercase() in SERVICE_PATHS) return false
        // /p/<route> passthrough links need a route.
        return first != "p" || path.substringAfter('/', "").trim('/').isNotEmpty()
    }

    private companion object {
        val SERVICE_PATHS = setOf("api", "hooks", ".well-known", "robots.txt", "favicon.ico", "apple-app-site-association")
    }
}
