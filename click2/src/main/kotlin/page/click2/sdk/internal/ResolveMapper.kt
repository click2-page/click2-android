package page.click2.sdk.internal

import org.json.JSONException
import org.json.JSONObject
import page.click2.sdk.Click2Link
import page.click2.sdk.Click2Result
import page.click2.sdk.Click2Result.Failed.Reason

/** Turns a /api/v1/resolve response into what the app should do (spec/fixtures/resolution.json). */
internal object ResolveMapper {

    fun map(clickedUrl: String, platform: String, status: Int, body: String?): Click2Result = when (status) {
        200 -> parse(clickedUrl, body)?.let { toAction(it, platform) } ?: Click2Result.Failed(Reason.SERVER_ERROR, clickedUrl)
        404 -> Click2Result.Failed(Reason.UNKNOWN_LINK, clickedUrl)
        400 -> Click2Result.Failed(Reason.INVALID_LINK, clickedUrl)
        else -> Click2Result.Failed(Reason.SERVER_ERROR, clickedUrl)
    }

    /** An http(s) URL with invalid characters percent-encoded, or null for anything else. */
    fun webUrl(value: String?): String? {
        val url = Percent.encodeInvalid(value?.trim()?.takeIf { it.isNotEmpty() } ?: return null)
        val parts = UrlParts.parse(url) ?: return null
        val http = parts.scheme.equals("https", ignoreCase = true) || parts.scheme.equals("http", ignoreCase = true)
        return url.takeIf { http && parts.host.isNotEmpty() }
    }

    private fun toAction(link: Click2Link, platform: String): Click2Result? {
        val platformUrl = (if (platform == "ios") link.iosUrl else link.androidUrl) ?: link.webUrl
        val platformRoute = if (platform == "ios") link.iosDeeplinkPath else link.androidDeeplinkPath
        val route = listOf(platformRoute, link.deeplinkPath)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.trimStart('/')
            ?.takeIf { it.isNotEmpty() }
        return when {
            link.webOnly -> platformUrl?.let { Click2Result.OpenWeb(it, inAppBrowser = false, link = link) }
            link.mobileWebOnly -> platformUrl?.let { Click2Result.OpenWeb(it, inAppBrowser = true, link = link) }
            route != null -> Click2Result.OpenRoute(route, link)
            else -> platformUrl?.let { Click2Result.OpenWeb(it, inAppBrowser = true, link = link) }
        }
    }

    private fun parse(clickedUrl: String, body: String?): Click2Link? = try {
        val json = JSONObject(body ?: return null)
        Click2Link(
            url = clickedUrl,
            alias = json.stringOrNull("alias"),
            deeplinkPath = json.stringOrNull("deeplinkPath"),
            iosDeeplinkPath = json.stringOrNull("iosDeeplinkPath"),
            androidDeeplinkPath = json.stringOrNull("androidDeeplinkPath"),
            webOnly = json.flag("webOnly"),
            mobileWebOnly = json.flag("mobileWebOnly"),
            webUrl = webUrl(json.stringOrNull("webUrl")),
            iosUrl = webUrl(json.stringOrNull("iosUrl")),
            androidUrl = webUrl(json.stringOrNull("androidUrl")),
            campaign = json.stringOrNull("campaign"),
            channel = json.stringOrNull("channel"),
            feature = json.stringOrNull("feature"),
        )
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    /** `true`/`false`, but also 1/0 and "true"/"1" (org.json's optBoolean ignores numbers). */
    private fun JSONObject.flag(key: String): Boolean = when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.trim().let { it.equals("true", ignoreCase = true) || it == "1" }
        else -> false
    }
}
