package page.click2.sdk.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import page.click2.sdk.Click2Result
import page.click2.sdk.Click2Result.Failed.Reason

/** What the deferred-link flow remembers across launches. */
internal data class DeferredState(
    /** `firstInstallTime` of the install whose referrer was checked; null = not checked. */
    val checkedInstallTime: Long? = null,
    /** A referrer link not yet resolved (e.g. offline on first launch). */
    val pendingLink: String? = null,
    val pendingAttempts: Int = 0,
    /** A link whose install is not yet reported. */
    val pendingInstallLink: String? = null,
)

internal interface DeferredStore {
    fun load(): DeferredState

    /** Must write synchronously (SharedPreferences.commit), so a pending link survives process death. */
    fun save(state: DeferredState)
}

/**
 * The deferred deep link flow behind Click2.checkDeferredLink. Work runs once per process in the
 * SDK's [scope], so a cancelled caller doesn't cancel it; the result is delivered to one caller.
 */
internal class DeferredLinkManager(
    private val store: DeferredStore,
    private val scope: CoroutineScope,
    /** Wall clock, milliseconds. */
    private val now: () -> Long,
    /** `PackageInfo.firstInstallTime`. */
    private val firstInstallTime: () -> Long,
    private val maxAgeMillis: Long,
    private val referrerTimeoutMillis: Long,
    private val readReferrer: suspend () -> ReferrerResult,
    /** The click2 link in a referrer string, if any. */
    private val linkFromReferrer: (String?) -> String?,
    private val resolve: suspend (String) -> Click2Result,
    /** Blocking; returns the HTTP status or null without an answer. */
    private val reportInstall: (String) -> Int?,
    private val trackingEnabled: () -> Boolean,
    /** Installs from a Play Store campaign (UTM tags / gclid) without a click2 link: the raw referrer; blocking. */
    private val reportReferrerInstall: ((String) -> Int?)? = null,
    /** Whether a referrer without a click2 link is a campaign worth reporting (not an organic Play install). */
    private val isCampaignReferrer: (String) -> Boolean = ReferrerParser::isCampaign,
) {
    private val lock = Any()
    private var work: Deferred<Click2Result?>? = null
    private var delivered = false

    suspend fun check(): Click2Result? {
        val job = synchronized(lock) { work ?: scope.async { runSafely() }.also { work = it } }
        // Throws if this caller is cancelled; the work goes on and the result waits for the next caller.
        val result = job.await()
        return synchronized(lock) {
            if (result == null || delivered) null else result.also { delivered = true }
        }
    }

    /** Marks this install's referrer as handled (apps migrating from their own deferred-link code). */
    fun markChecked() {
        val installTime = firstInstallTime()
        update { it.copy(checkedInstallTime = installTime, pendingLink = null, pendingAttempts = 0) }
    }

    private suspend fun runSafely(): Click2Result? = try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Click2Log.w("deferred link check failed", e)
        null
    }

    private suspend fun run(): Click2Result? {
        val installTime = firstInstallTime()
        val fresh = now() - installTime <= maxAgeMillis
        val state = store.load()

        if (state.checkedInstallTime == installTime) {
            state.pendingInstallLink?.let { if (fresh) sendInstall(it) else update { s -> s.copy(pendingInstallLink = null) } }
            val link = state.pendingLink ?: return null
            if (!fresh || state.pendingAttempts >= MAX_ATTEMPTS) {
                update { it.copy(pendingLink = null, pendingAttempts = 0) }
                return null
            }
            return resolvePending(link)
        }

        // Not checked for this install. A flag from another install (backup restore, new device) doesn't count.
        if (!fresh) {
            // An app update or a restore long after the install: the referrer isn't this user's click.
            Click2Log.d("install older than the deferred link window; skipping the referrer")
            update { DeferredState(checkedInstallTime = installTime) }
            return null
        }
        val referrer = withTimeoutOrNull(referrerTimeoutMillis) { readReferrer() } ?: ReferrerResult.TryLater
        if (referrer is ReferrerResult.TryLater) return null
        val link = (referrer as? ReferrerResult.Available)?.let { linkFromReferrer(it.referrer) }
        val installLink = link?.takeIf { trackingEnabled() }
        update { DeferredState(checkedInstallTime = installTime, pendingLink = link, pendingInstallLink = installLink) }
        installLink?.let(::sendInstall)
        // No click2 link, but maybe a Play Store campaign: click2 reads its UTM tags (once, best effort).
        val raw = (referrer as? ReferrerResult.Available)?.referrer
        if (link == null && raw != null && isCampaignReferrer(raw) && trackingEnabled() && reportReferrerInstall != null) {
            scope.launch { runCatching { reportReferrerInstall.invoke(raw) }.onFailure { Click2Log.w("referrer install report failed", it) } }
        }
        return link?.let { resolvePending(it) }
    }

    private suspend fun resolvePending(link: String): Click2Result {
        Click2Log.d("deferred link $link")
        val attempts = update { it.copy(pendingAttempts = it.pendingAttempts + 1) }.pendingAttempts
        val result = resolve(link)
        val transient = result is Click2Result.Failed && (result.reason == Reason.NETWORK_ERROR || result.reason == Reason.SERVER_ERROR)
        if (!transient || attempts >= MAX_ATTEMPTS) {
            update { if (it.pendingLink == link) it.copy(pendingLink = null, pendingAttempts = 0) else it }
        }
        return result
    }

    private fun sendInstall(link: String) {
        scope.launch {
            if (!trackingEnabled()) {
                update { it.copy(pendingInstallLink = null) }
                return@launch
            }
            val status = try {
                reportInstall(link)
            } catch (e: Exception) {
                Click2Log.w("install report failed", e)
                null
            }
            // 4xx: the server won't take it later either; 408 and 429 mean "try again later".
            if (status != null && isFinal(status)) {
                update { if (it.pendingInstallLink == link) it.copy(pendingInstallLink = null) else it }
            }
        }
    }

    private fun update(change: (DeferredState) -> DeferredState): DeferredState =
        synchronized(lock) { change(store.load()).also(store::save) }

    companion object {
        const val MAX_ATTEMPTS = 5

        /** A final answer to an install report: 2xx, or a 4xx other than 408 (timeout) and 429 (rate limited). */
        fun isFinal(status: Int) = status in 200..499 && status != 408 && status != 429
    }
}
