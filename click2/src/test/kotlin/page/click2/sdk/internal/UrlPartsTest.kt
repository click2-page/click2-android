package page.click2.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPartsTest {

    @Test
    fun `percent decoding is safe and keeps plus`() {
        assertEquals("a+b c", Percent.decode("a+b%20c"))
        assertEquals("50%off", Percent.decode("50%off"))
        assertEquals("100%", Percent.decode("100%"))
        assertEquals("%zz/é", Percent.decode("%zz/%C3%A9"))
        assertEquals("api", Percent.decode("%61pi"))
    }

    @Test
    fun `encodes only what isn't valid in a URL`() {
        assertEquals("https://x.com/weekly%20ad?q=%7Cé", Percent.encodeInvalid("https://x.com/weekly ad?q=|é").replace("%C3%A9", "é"))
        assertEquals("https://x.com/a%2Fb?d=50%25off#a%23b", Percent.encodeInvalid("https://x.com/a%2Fb?d=50%off#a#b"))
    }

    @Test
    fun `web URLs must be http or https`() {
        assertEquals("https://www.acme.com/weekly%20ad", ResolveMapper.webUrl(" https://www.acme.com/weekly ad "))
        assertEquals("http://x.com", ResolveMapper.webUrl("http://x.com"))
        assertNull(ResolveMapper.webUrl("javascript:alert(1)"))
        assertNull(ResolveMapper.webUrl("intent://evil#Intent;end"))
        assertNull(ResolveMapper.webUrl("https:///nohost"))
        assertNull(ResolveMapper.webUrl(""))
        assertNull(ResolveMapper.webUrl(null))
    }

    @Test
    fun `hostOf uses the same rules as matching`() {
        val matcher = LinkMatcher(listOf("acme.click2.page"))
        assertEquals("acme.click2.page", matcher.hostOf("https://Acme.click2.page.:443/x|y"))
        assertNull(matcher.hostOf("https://evil.com@acme.click2.page/x"))
        assertNull(matcher.hostOf("https://acme.click2.page/api/v1/resolve"))
        assertNull(matcher.hostOf("https://acme.click2.page\\@evil.com/x".replace("\\@evil.com", "\\api")))
    }
}
