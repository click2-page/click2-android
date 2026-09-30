package page.click2.sdk.internal

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal sealed interface ReferrerResult {
    /** The Play Store answered (the referrer may be empty for organic installs). */
    data class Available(val referrer: String?) : ReferrerResult

    /** Not available on this device (no Play Store): don't ask again. */
    data object NotSupported : ReferrerResult

    /** Temporary problem: try again on the next launch. */
    data object TryLater : ReferrerResult
}

internal suspend fun readInstallReferrer(context: Context): ReferrerResult = suspendCancellableCoroutine { continuation ->
    val client = InstallReferrerClient.newBuilder(context).build()
    fun finish(result: ReferrerResult) {
        runCatching { client.endConnection() }
        if (continuation.isActive) continuation.resume(result)
    }
    continuation.invokeOnCancellation { runCatching { client.endConnection() } }
    try {
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK ->
                        finish(ReferrerResult.Available(runCatching { client.installReferrer.installReferrer }.getOrNull()))
                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> finish(ReferrerResult.NotSupported)
                    else -> finish(ReferrerResult.TryLater)
                }
            }

            override fun onInstallReferrerServiceDisconnected() = finish(ReferrerResult.TryLater)
        })
    } catch (e: RuntimeException) {
        Click2Log.w("install referrer unavailable", e)
        finish(ReferrerResult.TryLater)
    }
}
