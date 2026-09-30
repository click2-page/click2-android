package page.click2.sdk.internal

/**
 * Finds the clicked link in a Play Install Referrer string. The click2 fallback page sends
 * Android users to the Play Store with `referrer=utm_source=smartlink&smartlink=<link>`
 * (spec/fixtures/install-referrer.json). Some Play Store versions return it still encoded once.
 */
internal object ReferrerParser {
    private const val PARAM = "smartlink"

    fun smartLink(referrer: String?, matcher: LinkMatcher): String? {
        if (referrer.isNullOrBlank()) return null
        val text = if ('=' in referrer) referrer else Percent.decode(referrer).takeIf { '=' in it } ?: return null
        // Percent-only decoding: a '+' in the link (sent as %2B) must stay a '+'.
        val value = text.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && Percent.decode(it[0]).trim() == PARAM }
            ?.let { Percent.decode(it[1]).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return value.takeIf(matcher::matches)
    }
}
