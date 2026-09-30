package page.click2.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import page.click2.sdk.internal.Click2Client
import page.click2.sdk.internal.Click2Log
import page.click2.sdk.internal.DeferredLinkManager
import page.click2.sdk.internal.LinkMatcher
import page.click2.sdk.internal.PrefsDeferredStore
import page.click2.sdk.internal.ReferrerParser
import page.click2.sdk.internal.UrlConnectionTransport
import page.click2.sdk.internal.readInstallReferrer

/**
 * click2 deep links for Android.
 *
 * ```
 * // Application.onCreate
 * Click2.configure(this, Click2Config(hosts = listOf("acme.click2.page"), appVersion = BuildConfig.VERSION_NAME))
 *
 * // Activity.onCreate / onNewIntent
 * if (Click2.isClick2Link(intent)) lifecycleScope.launch { route(Click2.handle(intent)) }
 *
 * // first screen, once
 * lifecycleScope.launch { Click2.checkDeferredLink()?.let(::route) }
 * ```
 */
object Click2 {
    private const val PLATFORM = "android"
    private const val PREFS = "page.click2.sdk"
    private const val KEY_TRACKING = "tracking_enabled"

    /** Headroom over [Click2Config.timeoutMillis] before a resolve is abandoned regardless of the socket. */
    private const val HARD_CAP_SLACK_MILLIS = 2_000L

    /** SDK-owned: work started here outlives a cancelled caller (rotation, leaving the screen). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Holds only the application context, which lives as long as the process: not a leak.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var state: State? = null

    private class State(
        val config: Click2Config,
        val matcher: LinkMatcher,
        val client: Click2Client,
        val prefs: SharedPreferences,
    ) {
        lateinit var deferred: DeferredLinkManager
    }

    /** Call once, e.g. in `Application.onCreate`. Calling again replaces the configuration. */
    @JvmStatic
    fun configure(context: Context, config: Click2Config) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Click2Log.enabled = config.logging
        val matcher = LinkMatcher(config.hosts)
        val client = Click2Client(
            transport = UrlConnectionTransport(),
            platform = PLATFORM,
            appVersion = config.appVersion,
            sdkVersion = BuildConfig.SDK_VERSION,
            trackingEnabled = { prefs.getBoolean(KEY_TRACKING, true) },
            timeoutMillis = config.timeoutMillis,
        )
        val s = State(config, matcher, client, prefs)
        s.deferred = DeferredLinkManager(
            store = PrefsDeferredStore(prefs),
            scope = scope,
            now = System::currentTimeMillis,
            firstInstallTime = { firstInstallTime(app) },
            maxAgeMillis = config.deferredLinkMaxAgeMillis,
            referrerTimeoutMillis = config.timeoutMillis.toLong(),
            readReferrer = { readInstallReferrer(app) },
            linkFromReferrer = { ReferrerParser.smartLink(it, matcher) },
            resolve = { resolveLink(s, it) },
            reportInstall = { link -> matcher.hostOf(link)?.let { client.reportInstall(link, it) } ?: 400 },
            trackingEnabled = { prefs.getBoolean(KEY_TRACKING, true) },
        )
        state = s
        Click2Log.d("configured for ${config.hosts}")
    }

    /**
     * Whether opens and installs are recorded. Set it from your consent settings (e.g. where you
     * previously called `Branch.disableTracking`). Remembered across launches; default `true`.
     * When `false`, links still resolve, but nothing is recorded, and pending install reports are dropped.
     */
    @JvmStatic
    var isTrackingEnabled: Boolean
        get() = requireState().prefs.getBoolean(KEY_TRACKING, true)
        set(value) = requireState().prefs.edit().putBoolean(KEY_TRACKING, value).apply()

    /** Whether this link is on one of the configured hosts (and not a service URL). */
    @JvmStatic
    fun isClick2Link(uri: Uri?): Boolean = uri != null && requireState().matcher.matches(uri.toString())

    @JvmStatic
    fun isClick2Link(intent: Intent?): Boolean = isClick2Link(intent?.data)

    /**
     * Resolves a click2 link. Returns [Click2Result.NotAClick2Link] for any other URL. Takes at most
     * about [Click2Config.timeoutMillis]; then returns [Click2Result.Failed.Reason.NETWORK_ERROR].
     */
    suspend fun resolve(uri: Uri): Click2Result = resolveLink(requireState(), uri.toString())

    /** Resolves the link an Activity was opened with (App Link click). */
    suspend fun handle(intent: Intent?): Click2Result = intent?.data?.let { resolve(it) } ?: Click2Result.NotAClick2Link

    /**
     * Deferred deep link: on the first launch after an install from a click2 link, returns where the
     * link pointed (via the Play Install Referrer) and records the install. Returns `null` on every
     * later launch, for organic installs, when the Play Store is unavailable, and when the app was
     * installed longer ago than [Click2Config.deferredLinkMaxAgeMillis].
     *
     * The work runs in the SDK, so a cancelled caller doesn't lose the link: the next call in the same
     * process gets it. Concurrent calls share one check and only one of them gets the result. If the
     * link can't be resolved yet (offline, server error), it is kept and retried on later launches
     * (up to 5 times, within the max age).
     */
    suspend fun checkDeferredLink(): Click2Result? = requireState().deferred.check()

    /**
     * Marks the install referrer as handled, so [checkDeferredLink] returns `null` for this install.
     * For apps that handled deferred links with their own code before adopting the SDK.
     * Writes to disk: call it off the main thread if you can.
     */
    @JvmStatic
    fun markDeferredLinkChecked() = requireState().deferred.markChecked()

    // ---------------------------------------------------------------- callbacks (Java / non-coroutine callers)

    /** Not lifecycle-aware: the callback may run after the Activity is gone. Prefer the suspend API with `lifecycleScope`. */
    fun interface Callback {
        fun onResult(result: Click2Result)
    }

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Callback version of [resolve]; the callback runs on the main thread. */
    @JvmStatic
    fun resolve(uri: Uri, callback: Callback) {
        val s = requireState()
        callbackScope.launch { callback.onResult(resolveLink(s, uri.toString())) }
    }

    /** Callback version of [handle]; the callback runs on the main thread. Returns whether it's a click2 link. */
    @JvmStatic
    fun handle(intent: Intent?, callback: Callback): Boolean {
        if (!isClick2Link(intent)) return false
        callbackScope.launch { callback.onResult(handle(intent)) }
        return true
    }

    /** Callback version of [checkDeferredLink]; called only when there is a deferred link. */
    @JvmStatic
    fun checkDeferredLink(callback: Callback) {
        val s = requireState()
        callbackScope.launch { s.deferred.check()?.let(callback::onResult) }
    }

    private suspend fun resolveLink(s: State, url: String): Click2Result {
        val host = s.matcher.hostOf(url) ?: return Click2Result.NotAClick2Link
        // Blocking I/O can't be interrupted, so run it in the SDK scope and stop waiting at the hard cap.
        val work = scope.async {
            runCatching { s.client.resolve(url, host) }
                .onFailure { Click2Log.w("resolve failed", it) }
                .getOrElse { Click2Result.Failed(Click2Result.Failed.Reason.NETWORK_ERROR, url) }
        }
        val result = withTimeoutOrNull(s.config.timeoutMillis + HARD_CAP_SLACK_MILLIS) { work.await() }
            ?: Click2Result.Failed(Click2Result.Failed.Reason.NETWORK_ERROR, url).also { work.cancel() }
        Click2Log.d("resolved $url -> $result")
        return result
    }

    private fun firstInstallTime(context: Context): Long = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.firstInstallTime
    } catch (e: Exception) {
        // Unknown install time: treat the install as old, so no referrer is replayed.
        Click2Log.w("firstInstallTime unavailable", e)
        0L
    }

    private fun requireState(): State =
        state ?: throw IllegalStateException("Call Click2.configure(context, Click2Config(...)) first, e.g. in Application.onCreate")
}
