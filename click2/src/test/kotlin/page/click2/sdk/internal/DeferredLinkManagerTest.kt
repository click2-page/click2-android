package page.click2.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.click2.sdk.Click2Link
import page.click2.sdk.Click2Result
import page.click2.sdk.Click2Result.Failed.Reason

class DeferredLinkManagerTest {
    private val link = "https://acme.click2.page/subs?id=1"
    private val referrer = "utm_source=smartlink&smartlink=https%3A%2F%2Facme.click2.page%2Fsubs%3Fid%3D1"
    private val day = 24 * 60 * 60 * 1000L
    private val installTime = 1_000_000_000L

    private class MemoryStore : DeferredStore {
        var state = DeferredState()
        override fun load() = state
        override fun save(state: DeferredState) {
            this.state = state
        }
    }

    private val store = MemoryStore()
    private var now = installTime + 60_000
    private var firstInstall = installTime
    private var tracking = true
    private var referrerResult: ReferrerResult = ReferrerResult.Available(referrer)
    private var referrerReads = 0
    private val resolved = mutableListOf<String>()
    private var resolveResult: suspend () -> Click2Result = { route() }
    private val installs = mutableListOf<String>()
    private var installStatus: () -> Int? = { 204 }
    private val referrerInstalls = mutableListOf<String>()

    private fun route() = Click2Result.OpenRoute("orders/subs?id=1", Click2Link(link, null, null, null, null, false, false, null, null, null, null, null, null))

    private fun TestScope.manager(scope: CoroutineScope = backgroundScope) = DeferredLinkManager(
        store = store,
        scope = scope,
        now = { now },
        firstInstallTime = { firstInstall },
        maxAgeMillis = 7 * day,
        referrerTimeoutMillis = 10_000,
        readReferrer = { referrerReads++; referrerResult },
        linkFromReferrer = { ReferrerParser.smartLink(it, LinkMatcher(listOf("acme.click2.page"))) },
        resolve = { resolved += it; resolveResult() },
        reportInstall = { installs += it; installStatus() },
        trackingEnabled = { tracking },
        reportReferrerInstall = { referrerInstalls += it; 204 },
    )

    private val networkError = Click2Result.Failed(Reason.NETWORK_ERROR, link)

    @Test
    fun `routes the referrer link once and reports the install`() = runTest {
        val result = manager().check()
        advanceUntilIdle()
        assertTrue(result is Click2Result.OpenRoute)
        assertEquals(listOf(link), resolved)
        assertEquals(listOf(link), installs)
        assertEquals(DeferredState(checkedInstallTime = installTime), store.state)
        // Later calls and later launches get nothing.
        assertNull(manager().check())
        assertEquals(1, referrerReads)
    }

    @Test
    fun `offline first launch keeps the link and succeeds on the next launch`() = runTest {
        resolveResult = { networkError }
        assertEquals(networkError, manager().check())
        assertEquals(link, store.state.pendingLink)

        resolveResult = { route() }
        val next = manager().check()
        assertTrue(next is Click2Result.OpenRoute)
        assertNull(store.state.pendingLink)
        assertEquals(1, referrerReads)
    }

    @Test
    fun `the pending link is saved before resolving`() = runTest {
        val gate = CompletableDeferred<Click2Result>()
        resolveResult = { gate.await() }
        manager().let { m -> backgroundScope.launch { m.check() } }
        runCurrent()
        // Process death here: the link is on disk.
        assertEquals(link, store.state.pendingLink)
        assertEquals(installTime, store.state.checkedInstallTime)
    }

    @Test
    fun `gives up after five attempts`() = runTest {
        resolveResult = { networkError }
        repeat(5) { manager().check() }
        assertNull(store.state.pendingLink)
        assertNull(manager().check())
        assertEquals(5, resolved.size)
    }

    @Test
    fun `gives up on a pending link after seven days`() = runTest {
        resolveResult = { networkError }
        manager().check()
        now = installTime + 8 * day
        assertNull(manager().check())
        assertNull(store.state.pendingLink)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `an unknown link is not retried`() = runTest {
        resolveResult = { Click2Result.Failed(Reason.UNKNOWN_LINK, link) }
        manager().check()
        assertNull(store.state.pendingLink)
    }

    @Test
    fun `a cancelled caller doesn't lose the result`() = runTest {
        val gate = CompletableDeferred<Click2Result>()
        resolveResult = { gate.await() }
        val m = manager()
        val caller = launch { m.check() }
        runCurrent()
        caller.cancel()
        gate.complete(route())
        advanceUntilIdle()
        assertTrue(m.check() is Click2Result.OpenRoute)
        assertNull(m.check())
        assertEquals(1, resolved.size)
    }

    @Test
    fun `concurrent calls resolve once and deliver once`() = runTest {
        val gate = CompletableDeferred<Click2Result>()
        resolveResult = { gate.await() }
        val m = manager()
        val calls = List(3) { async { m.check() } }
        runCurrent()
        gate.complete(route())
        val results = calls.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(1, resolved.size)
        assertEquals(1, referrerReads)
    }

    @Test
    fun `no install report when tracking is disabled`() = runTest {
        tracking = false
        assertNotNull(manager().check())
        advanceUntilIdle()
        assertTrue(installs.isEmpty())
        assertNull(store.state.pendingInstallLink)
    }

    @Test
    fun `a pending install is dropped after opting out`() = runTest {
        installStatus = { null }
        manager().check()
        advanceUntilIdle()
        assertEquals(link, store.state.pendingInstallLink)

        tracking = false
        manager().check()
        advanceUntilIdle()
        assertEquals(1, installs.size)
        assertNull(store.state.pendingInstallLink)
    }

    @Test
    fun `install is retried after a failure and cleared on 2xx`() = runTest {
        installStatus = { 503 }
        manager().check()
        advanceUntilIdle()
        assertEquals(link, store.state.pendingInstallLink)

        installStatus = { null }
        manager().check()
        advanceUntilIdle()
        assertEquals(link, store.state.pendingInstallLink)

        installStatus = { 204 }
        manager().check()
        advanceUntilIdle()
        assertNull(store.state.pendingInstallLink)
        assertEquals(3, installs.size)
        manager().check()
        advanceUntilIdle()
        assertEquals(3, installs.size)
    }

    @Test
    fun `install is retried after 429 and 408`() = runTest {
        for (status in listOf(429, 408)) {
            installStatus = { status }
            manager().check()
            advanceUntilIdle()
            assertEquals("$status", link, store.state.pendingInstallLink)
        }
        installStatus = { 204 }
        manager().check()
        advanceUntilIdle()
        assertNull(store.state.pendingInstallLink)
        assertEquals(3, installs.size)
    }

    @Test
    fun `install is cleared on 4xx`() = runTest {
        installStatus = { 400 }
        manager().check()
        advanceUntilIdle()
        assertNull(store.state.pendingInstallLink)
    }

    @Test
    fun `an old install ignores the referrer`() = runTest {
        now = installTime + 8 * day
        assertNull(manager().check())
        assertEquals(0, referrerReads)
        assertTrue(resolved.isEmpty() && installs.isEmpty())
        assertEquals(installTime, store.state.checkedInstallTime)
    }

    @Test
    fun `a flag restored from another install counts as unchecked`() = runTest {
        store.state = DeferredState(checkedInstallTime = installTime - 30 * day, pendingInstallLink = "https://acme.click2.page/old")
        assertTrue(manager().check() is Click2Result.OpenRoute)
        advanceUntilIdle()
        assertEquals(listOf(link), installs)
        assertEquals(installTime, store.state.checkedInstallTime)
    }

    @Test
    fun `referrer unavailable is tried again next launch`() = runTest {
        referrerResult = ReferrerResult.TryLater
        assertNull(manager().check())
        assertNull(store.state.checkedInstallTime)
        referrerResult = ReferrerResult.Available(referrer)
        assertNotNull(manager().check())
    }

    @Test
    fun `an organic install is checked once`() = runTest {
        referrerResult = ReferrerResult.Available("utm_source=google-play&utm_medium=organic")
        assertNull(manager().check())
        assertNull(manager().check())
        assertEquals(1, referrerReads)
        assertEquals(DeferredState(checkedInstallTime = installTime), store.state)
        advanceUntilIdle()
        assertTrue("organic installs aren't reported", referrerInstalls.isEmpty())
    }

    @Test
    fun `a campaign referrer is reported once, a link for another host is not`() = runTest {
        referrerResult = ReferrerResult.Available("utm_source=smartlink&smartlink=https%3A%2F%2Fglobex.click2.page%2Fx")
        assertNull(manager().check())
        advanceUntilIdle()
        assertTrue(referrerInstalls.isEmpty())

        store.state = DeferredState()
        referrerResult = ReferrerResult.Available("utm_source=tiktok&utm_medium=paid")
        assertNull(manager().check())
        assertNull(manager().check())
        advanceUntilIdle()
        assertEquals(listOf("utm_source=tiktok&utm_medium=paid"), referrerInstalls)
    }

    @Test
    fun `markChecked stops processing`() = runTest {
        manager().markChecked()
        assertNull(manager().check())
        assertEquals(0, referrerReads)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `markChecked drops a pending link`() = runTest {
        resolveResult = { networkError }
        manager().check()
        manager().markChecked()
        resolveResult = { route() }
        assertNull(manager().check())
        assertEquals(1, resolved.size)
    }
}
