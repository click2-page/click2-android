package page.click2.sdk.internal

import android.content.SharedPreferences

/** [DeferredStore] in the SDK's SharedPreferences, written with `commit()`. */
internal class PrefsDeferredStore(private val prefs: SharedPreferences) : DeferredStore {
    override fun load() = DeferredState(
        checkedInstallTime = if (prefs.contains(CHECKED)) prefs.getLong(CHECKED, 0) else null,
        pendingLink = prefs.getString(PENDING_LINK, null),
        pendingAttempts = prefs.getInt(PENDING_ATTEMPTS, 0),
        pendingInstallLink = prefs.getString(PENDING_INSTALL, null),
    )

    override fun save(state: DeferredState) {
        val editor = prefs.edit()
        state.checkedInstallTime?.let { editor.putLong(CHECKED, it) } ?: editor.remove(CHECKED)
        editor.putString(PENDING_LINK, state.pendingLink)
        editor.putInt(PENDING_ATTEMPTS, state.pendingAttempts)
        editor.putString(PENDING_INSTALL, state.pendingInstallLink)
        if (!editor.commit()) Click2Log.w("could not save the deferred link state")
    }

    private companion object {
        const val CHECKED = "deferred_checked_install_time"
        const val PENDING_LINK = "deferred_pending_link"
        const val PENDING_ATTEMPTS = "deferred_pending_attempts"
        const val PENDING_INSTALL = "deferred_pending_install"
    }
}
