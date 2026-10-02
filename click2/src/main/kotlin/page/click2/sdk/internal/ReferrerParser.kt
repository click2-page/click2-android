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

    /**
     * Whether a referrer without a click2 link is a Play Store campaign worth reporting (UTM tags or a Google Ads
     * click id; spec/fixtures/campaign-referrer.json). Not for organic Play installs
     * (`utm_source=google-play&utm_medium=organic`, or google-play with nothing else) nor for click2 referrers whose
     * link is for another host.
     */
    fun isCampaign(referrer: String?): Boolean {
        val params = params(referrer) ?: return false
        if ("smartlink" in params || params["utm_source"] == "smartlink") return false
        val keys = params.filter { (k, v) -> v.isNotEmpty() && (k.startsWith("utm_") || k in CLICK_IDS) }.keys.toMutableSet()
        if (params["utm_source"] == "google-play") {
            if (params["utm_medium"] == "organic") return false
            keys -= setOf("utm_source", "utm_medium")
        }
        return keys.isNotEmpty()
    }

    private val CLICK_IDS = setOf("gclid", "gbraid", "wbraid")

    /** Lowercased keys to trimmed, lowercased values; null without any `key=value`. */
    private fun params(referrer: String?): Map<String, String>? {
        if (referrer.isNullOrBlank()) return null
        val text = if ('=' in referrer) referrer else Percent.decode(referrer).takeIf { '=' in it } ?: return null
        return text.split('&')
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .associate { Percent.decode(it[0]).trim().lowercase() to Percent.decode(it[1]).trim().lowercase() }
    }
}
