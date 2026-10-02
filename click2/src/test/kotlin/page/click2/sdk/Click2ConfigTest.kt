package page.click2.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Click2ConfigTest {

    @Test
    fun `accepts bare host names`() {
        val config = Click2Config(listOf("acme.click2.page", "Acme-Test.click2.page", "acme.click2.page."))
        assertEquals(10_000, config.timeoutMillis)
        assertEquals(7 * 24 * 60 * 60 * 1000L, config.deferredLinkMaxAgeMillis)
    }

    @Test
    fun `rejects anything that isn't a bare host name`() {
        val invalid = listOf(
            "https://acme.click2.page",
            "acme.click2.page/",
            "acme.click2.page/x",
            "acme.click2.page:443",
            "user@acme.click2.page",
            "acme_shop.click2.page",
            "acme.click2.page?x",
            "acme..click2.page",
            "-acme.click2.page",
            "gïanteagle.click2.page",
            "",
        )
        for (host in invalid) {
            val error = assertThrows(host, IllegalArgumentException::class.java) { Click2Config(listOf(host)) }
            assert(error.message!!.contains("bare host name")) { error.message!! }
        }
        assertThrows(IllegalArgumentException::class.java) { Click2Config(emptyList()) }
    }

    @Test
    fun `hosts are normalized once`() {
        val config = Click2Config(listOf(" Acme.Click2.Page. ", "acme.click2.page", "acme-test.click2.page\n"))
        assertEquals(listOf("acme.click2.page", "acme-test.click2.page"), config.linkHosts)
    }

    @Test
    fun `timeout must be 1 to 60000 ms`() {
        Click2Config(listOf("acme.click2.page"), timeoutMillis = 1)
        Click2Config(listOf("acme.click2.page"), timeoutMillis = 60_000)
        assertThrows(IllegalArgumentException::class.java) { Click2Config(listOf("acme.click2.page"), timeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { Click2Config(listOf("acme.click2.page"), timeoutMillis = 60_001) }
        assertThrows(IllegalArgumentException::class.java) { Click2Config(listOf("acme.click2.page"), deferredLinkMaxAgeMillis = 0) }
    }
}
