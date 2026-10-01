package page.click2.sdk

/** What the app should do with a click2 link. */
sealed interface Click2Result {

    /** Open this in-app route (no leading slash; query parameters included), e.g. `product/123?src=email`. */
    data class OpenRoute(val path: String, val link: Click2Link) : Click2Result

    /**
     * Open a web page. [inAppBrowser] `false` means the external browser (the link is "web only"),
     * `true` means show it inside the app (e.g. a Custom Tab or WebView).
     */
    data class OpenWeb(val url: String, val inAppBrowser: Boolean, val link: Click2Link) : Click2Result

    /** The link could not be resolved. Usually: stay where you are (or show the home screen). */
    data class Failed(val reason: Reason, val url: String) : Click2Result {
        enum class Reason {
            /** The link doesn't exist or expired. */
            UNKNOWN_LINK,

            /** The server says the link doesn't belong to this app's team. */
            INVALID_LINK,

            /** The server answered with an error or an unexpected response. */
            SERVER_ERROR,

            /** No connection or a timeout. */
            NETWORK_ERROR,
        }
    }

    /** Not a link on the configured hosts; the app should handle it as before. */
    data object NotAClick2Link : Click2Result
}

/** The resolved link, for analytics (campaign, channel, feature) or custom handling. */
data class Click2Link(
    /** The link that was clicked. */
    val url: String,
    val alias: String?,
    val deeplinkPath: String?,
    val iosDeeplinkPath: String?,
    val androidDeeplinkPath: String?,
    val webOnly: Boolean,
    val mobileWebOnly: Boolean,
    /** The web destination (http/https, invalid characters percent-encoded); null when missing or not a web URL. */
    val webUrl: String?,
    /** iOS web destination; null when missing or not a web URL (then [webUrl] is used). */
    val iosUrl: String?,
    /** Android web destination; null when missing or not a web URL (then [webUrl] is used). */
    val androidUrl: String?,
    val campaign: String?,
    val channel: String?,
    val feature: String?,
    /** The click2 link itself, when it differs from [url] (e.g. [url] is an email click-tracking URL). */
    val linkUrl: String? = null,
    /** The link rule (`rule:<id>`) or A/B variant that chose the destination, if the link has rules or a split. */
    val variant: String? = null,
)
