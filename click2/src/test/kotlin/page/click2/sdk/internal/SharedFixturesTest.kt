package page.click2.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import page.click2.sdk.Click2Result
import page.click2.sdk.internal.Fixtures.cases
import page.click2.sdk.internal.Fixtures.strings

class SharedFixturesTest {

    @Test
    fun linkMatching() {
        val fixture = Fixtures.load("link-matching.json")
        val matcher = LinkMatcher(fixture.getJSONArray("hosts").strings())
        val cases = fixture.cases()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val url = c.getString("url")
            assertEquals(url, c.getBoolean("expected"), matcher.matches(url))
        }
    }

    @Test
    fun resolution() {
        val cases = Fixtures.load("resolution.json").cases()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c.getString("name")
            val result = ResolveMapper.map("https://acme.click2.page/x", c.getString("platform"), c.getInt("status"), c.getJSONObject("body").toString())
            val expected = c.getJSONObject("expected")
            when (expected.getString("action")) {
                "route" -> {
                    val route = result as? Click2Result.OpenRoute ?: throw AssertionError("$name: expected route, got $result")
                    assertEquals(name, expected.getString("path"), route.path)
                }
                "web" -> {
                    val web = result as? Click2Result.OpenWeb ?: throw AssertionError("$name: expected web, got $result")
                    assertEquals(name, expected.getString("url"), web.url)
                    assertEquals(name, expected.getBoolean("inAppBrowser"), web.inAppBrowser)
                }
                "failed" -> {
                    val failed = result as? Click2Result.Failed ?: throw AssertionError("$name: expected failed, got $result")
                    assertEquals(name, expected.getString("reason").uppercase(), failed.reason.name)
                }
                else -> throw AssertionError("unknown action in $name")
            }
        }
    }

    @Test
    fun installReferrer() {
        val fixture = Fixtures.load("install-referrer.json")
        val matcher = LinkMatcher(fixture.getJSONArray("hosts").strings())
        val cases = fixture.cases()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(c.getString("referrer"), expected, ReferrerParser.smartLink(c.getString("referrer"), matcher))
        }
    }
}
