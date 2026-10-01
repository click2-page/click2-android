package page.click2.sdk.internal

import org.json.JSONObject
import page.click2.sdk.Click2Result
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

internal data class HttpResponse(val status: Int, val body: String?)

/** Minimal HTTP so the SDK doesn't pull OkHttp/Ktor into apps that may use other versions. */
internal fun interface HttpTransport {
    /** [timeoutMillis] is the whole budget for this attempt (connect and read). */
    @Throws(IOException::class)
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMillis: Int): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMillis: Int): HttpResponse {
        val start = System.nanoTime()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                connection.doOutput = true
                // A fixed-length body is never silently re-sent by the platform HTTP stack.
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            connection.connect()
            val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
            if (remaining <= 0) throw SocketTimeoutException("timed out after connecting")
            connection.readTimeout = remaining.toInt()
            bytes?.let { b -> connection.outputStream.use { it.write(b) } }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Calls the click2 app API (spec/openapi.yaml). Blocking: call from a background thread.
 * [timeoutMillis] is the total budget per call, retry included; a request is retried once, and only
 * when it certainly never reached the server.
 */
internal class Click2Client(
    private val transport: HttpTransport,
    private val platform: String,
    private val appVersion: String?,
    private val sdkVersion: String,
    private val trackingEnabled: () -> Boolean,
    private val timeoutMillis: Int = 10_000,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun resolve(clickedUrl: String, host: String): Click2Result {
        val query = buildString {
            append("url=").append(encode(clickedUrl))
            append("&platform=").append(platform)
            appVersion?.let { append("&appVersion=").append(encode(it.take(32))) }
        }
        val url = "https://$host/api/v1/resolve?$query"
        val response = send { timeout -> transport.execute("GET", url, headers(), null, timeout) }
            ?: return Click2Result.Failed(Click2Result.Failed.Reason.NETWORK_ERROR, clickedUrl)
        return ResolveMapper.map(clickedUrl, platform, response.status, response.body)
    }

    /** Reports an install. Returns the HTTP status, or null when there was no answer. */
    fun reportInstall(clickedUrl: String, host: String, userId: String? = null): Int? {
        val body = JSONObject()
            .put("type", "install")
            .put("url", clickedUrl)
            .put("platform", platform)
            .apply { appVersion?.let { put("appVersion", it.take(32)) } }
            .apply { userId?.let { put("userId", it) } }
            .toString()
        return send { timeout ->
            transport.execute("POST", "https://$host/api/v1/events", headers() + ("Content-Type" to "application/json"), body, timeout)
        }?.status
    }

    /**
     * Reports an in-app event. Returns the HTTP status, or null when there was no answer.
     * [properties] values must be String, Number or Boolean (others are dropped by the caller).
     */
    fun reportEvent(name: String, revenue: Double?, currency: String?, properties: Map<String, Any>, link: String?, userId: String?, host: String, variant: String? = null): Int? {
        val body = JSONObject()
            .put("type", "event")
            .put("name", name)
            .put("platform", platform)
            .apply {
                revenue?.let { put("revenue", it) }
                currency?.let { put("currency", it) }
                if (properties.isNotEmpty()) put("properties", JSONObject(properties))
                link?.let { put("url", it) }
                variant?.let { put("variant", it) }
                userId?.let { put("userId", it) }
                appVersion?.let { put("appVersion", it.take(32)) }
            }
            .toString()
        return send { timeout ->
            transport.execute("POST", "https://$host/api/v1/events", headers() + ("Content-Type" to "application/json"), body, timeout)
        }?.status
    }

    private fun headers(): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", "click2-$platform/$sdkVersion")
        // For link rules by language (HttpURLConnection sends none by default).
        put("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
        if (!trackingEnabled()) put("X-Tracking-Disabled", "1")
    }

    private inline fun send(call: (timeoutMillis: Int) -> HttpResponse): HttpResponse? {
        val deadline = clock() + timeoutMillis
        for (attempt in 1..MAX_ATTEMPTS) {
            val remaining = deadline - clock()
            if (remaining <= 0) break
            try {
                return call(remaining.toInt())
            } catch (e: IOException) {
                Click2Log.w("request failed (attempt $attempt)", e)
                if (!neverSent(e)) break
            }
        }
        return null
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_ATTEMPTS = 2

        /** Failures before anything reached the server; everything else (e.g. a read timeout) may have been processed. */
        fun neverSent(e: IOException) =
            e is ConnectException || e is UnknownHostException || e is NoRouteToHostException || e is SSLHandshakeException
    }
}
