package dev.glorioustr.mtzstudio.miuibackup

import android.content.Context

/**
 * Remembers the one theme the user is mid-way through importing via MIUI Backup, so the app can
 * offer to open it in Themes when they return from the restore. Deliberately holds a single pending
 * import: the flow is one theme at a time, and a stale entry is harmless (the deep link simply opens
 * that theme, or its online page if it was never restored).
 */
internal class PendingMiuiImportStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(localId: String, themeTitle: String, themeId: String) {
        prefs.edit()
            .putString(KEY_LOCAL_ID, localId)
            .putString(KEY_TITLE, themeTitle)
            .putString(KEY_THEME_ID, themeId)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): MiuiBackupImportCoordinator.PendingApply? {
        val localId = prefs.getString(KEY_LOCAL_ID, null) ?: return null
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        val themeId = prefs.getString(KEY_THEME_ID, null).orEmpty()
        // Expire a pending import after a day so a forgotten one does not nag indefinitely.
        if (System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) > EXPIRY_MILLIS) {
            clear()
            return null
        }
        return MiuiBackupImportCoordinator.PendingApply(localId, title, themeId)
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS = "miui-backup-import"
        const val KEY_LOCAL_ID = "local-id"
        const val KEY_TITLE = "title"
        const val KEY_THEME_ID = "theme-id"
        const val KEY_AT = "at"
        const val EXPIRY_MILLIS = 24L * 60 * 60 * 1000
    }
}
