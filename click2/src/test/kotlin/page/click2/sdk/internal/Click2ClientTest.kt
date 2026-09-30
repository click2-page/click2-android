package page.click2.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.click2.sdk.Click2Result
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.net.URLDecoder

class Click2ClientTest {
    private data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?, val timeout: Int)

    private val requests = mutableListOf<Request>()
    private var tracking = true
    private var now = 0L

    private fun client(vararg responses: () -> HttpResponse): Click2Client {
        val queue = ArrayDeque(responses.toList())
        return Click2Client(
            transport = { method, url, headers, body, timeout ->
                requests += Request(method, url, headers, body, timeout)
                (queue.removeFirstOrNull() ?: { HttpResponse(500, null) })()
            },
            platform = "android",
            appVersion = "5.1.0",
            sdkVersion = "0.1.0",
            trackingEnabled = { tracking },
            timeoutMillis = 10_000,
            clock = { now },
        )
    }

    private val ok = HttpResponse(
        200,
        """{"alias":"subs","deeplinkPath":"orders/subs?id=1","androidDeeplinkPath":"orders/subs?id=1","webOnly":false,"mobileWebOnly":false,
           "webUrl":"https://www.acme.com","iosUrl":"https://www.acme.com","androidUrl":"https://www.acme.com","campaign":"sms"}""",
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun `resolves against the link's own host with platform and version`() {
        val result = client({ ok }).resolve("https://acme.click2.page/subs?id=1", "acme.click2.page")

        val route = result as Click2Result.OpenRoute
        assertEquals("orders/subs?id=1", route.path)
        assertEquals("sms", route.link.campaign)
        assertEquals("https://acme.click2.page/subs?id=1", route.link.url)

        val request = requests.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("https://acme.click2.page/api/v1/resolve?"))
        assertEquals(mapOf("url" to "https://acme.click2.page/subs?id=1", "platform" to "android", "appVersion" to "5.1.0"), query(request.url))
        assertEquals("click2-android/0.1.0", request.headers["User-Agent"])
        assertFalse("X-Tracking-Disabled" in request.headers)
    }

    @Test
    fun `sends the opt-out header when tracking is disabled`() {
        tracking = false
        client({ ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals("1", requests.single().headers["X-Tracking-Disabled"])
    }

    @Test
    fun `retries once when the connection failed`() {
        val result = client({ throw ConnectException("refused") }, { ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertTrue(result is Click2Result.OpenRoute)
        assertEquals(2, requests.size)
    }

    @Test
    fun `retries once when the host was not found`() {
        val result = client({ throw UnknownHostException("offline") }, { ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertTrue(result is Click2Result.OpenRoute)
        assertEquals(2, requests.size)
    }

    @Test
    fun `reports a network error after the retry`() {
        val fail = { throw ConnectException("offline") }
        val result = client(fail, fail, { ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals(Click2Result.Failed.Reason.NETWORK_ERROR, (result as Click2Result.Failed).reason)
        assertEquals(2, requests.size)
    }

    @Test
    fun `does not retry after a read timeout`() {
        val result = client({ throw SocketTimeoutException("read timed out") }, { ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals(Click2Result.Failed.Reason.NETWORK_ERROR, (result as Click2Result.Failed).reason)
        assertEquals(1, requests.size)
    }

    @Test
    fun `does not retry other errors after the request was sent`() {
        val result = client({ throw IOException("connection reset") }, { ok }).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertTrue(result is Click2Result.Failed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `never re-sends an install report once written`() {
        val status = client({ throw IOException("reset after write") }, { HttpResponse(204, null) })
            .reportInstall("https://acme.click2.page/subs", "acme.click2.page")
        assertNull(status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `install report is retried when it never connected`() {
        val status = client({ throw ConnectException("refused") }, { HttpResponse(204, null) })
            .reportInstall("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals(204, status)
        assertEquals(2, requests.size)
    }

    @Test
    fun `the timeout is the total budget across attempts`() {
        val slowFail = { now += 3_000; throw ConnectException("refused") }
        client(slowFail, slowFail).resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals(listOf(10_000, 7_000), requests.map { it.timeout })
    }

    @Test
    fun `no retry once the budget is spent`() {
        val result = client({ now += 10_000; throw ConnectException("refused") }, { ok })
            .resolve("https://acme.click2.page/subs", "acme.click2.page")
        assertTrue(result is Click2Result.Failed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `reports installs as JSON`() {
        val status = client({ HttpResponse(204, null) }).reportInstall("https://acme.click2.page/subs", "acme.click2.page")
        assertEquals(204, status)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://acme.click2.page/api/v1/events", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        val body = JSONObject(request.body!!)
        assertEquals("install", body.getString("type"))
        assertEquals("https://acme.click2.page/subs", body.getString("url"))
        assertEquals("android", body.getString("platform"))
        assertEquals("5.1.0", body.getString("appVersion"))
    }
}
