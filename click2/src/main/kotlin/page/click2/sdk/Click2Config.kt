package page.click2.sdk

/**
 * @param hosts the team's link hosts this app handles, e.g. `listOf("acme.click2.page")` for
 *   the production build and `listOf("acme-test.click2.page")` for staging. The SDK only
 *   treats links on these hosts as click2 links and only ever calls these hosts. Bare host names
 *   only: no scheme, port, path or user info.
 * @param appVersion reported with opens and installs (e.g. `BuildConfig.VERSION_NAME`).
 * @param timeoutMillis total network budget per call (1 to 60 000 ms), including the one retry
 *   made when a request couldn't reach the server.
 * @param logging logs to Logcat under the `Click2` tag (for debug builds). Nothing is logged otherwise.
 * @param deferredLinkMaxAgeMillis a deferred link is only taken from the install referrer when the
 *   app was installed at most this long ago (default 7 days), so app updates and restores don't replay it.
 * @param attributionWindowMillis how long the last click2 link that opened the app gets credit for
 *   [Click2.track] events (default 7 days).
 */
data class Click2Config @JvmOverloads constructor(
    val hosts: List<String>,
    val appVersion: String? = null,
    val timeoutMillis: Int = 10_000,
    val logging: Boolean = false,
    val deferredLinkMaxAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L,
    val attributionWindowMillis: Long = 7 * 24 * 60 * 60 * 1000L,
) {
    init {
        require(hosts.isNotEmpty()) { "Click2Config needs at least one link host" }
        for (host in hosts) {
            require(HOST.matches(host)) {
                "Invalid click2 host \"$host\": use a bare host name like acme.click2.page " +
                    "(letters, digits, dots and hyphens; no scheme, port, path or user info)"
            }
        }
        require(timeoutMillis in 1..60_000) { "timeoutMillis must be between 1 and 60000, was $timeoutMillis" }
        require(deferredLinkMaxAgeMillis > 0) { "deferredLinkMaxAgeMillis must be positive" }
        require(attributionWindowMillis >= 0) { "attributionWindowMillis can't be negative" }
    }

    private companion object {
        private const val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        val HOST = Regex("(?=.{1,253}$)$LABEL(?:\\.$LABEL)*\\.?", RegexOption.IGNORE_CASE)
    }
}
