package io.github.topiautio.blemeshctl.library

import android.content.Context

/**
 * Keeps the light library in its own app-private SharedPreferences file.
 *
 * The file is excluded from cloud backup and device transfer like every other
 * preference file, and it never holds mesh credentials.
 */
class SharedPreferencesLibraryStorage(context: Context) : LibraryStorage {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<String, String> =
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun save(entries: Map<String, String>) {
        val editor = preferences.edit().clear()
        entries.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    private companion object {
        const val FILE_NAME = "blemeshctl_library"
    }
}
