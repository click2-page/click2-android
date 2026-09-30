package page.click2.sdk.internal

/** Logcat logging, off unless Click2Config.logging is set. Falls back to stdout in JVM unit tests. */
internal object Click2Log {
    @Volatile
    var enabled = false

    private const val TAG = "Click2"

    fun d(message: String) {
        if (!enabled) return
        runCatching { android.util.Log.d(TAG, message) }.onFailure { println("$TAG: $message") }
    }

    fun w(message: String, error: Throwable? = null) {
        if (!enabled) return
        runCatching { android.util.Log.w(TAG, message, error) }.onFailure { println("$TAG: $message ${error ?: ""}") }
    }
}
