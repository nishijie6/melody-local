package com.melody.local.lyrics

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit

data class LyricsAutomationSettings(
    val searchAuthorizedFolders: Boolean = true,
    val readEmbeddedLyrics: Boolean = true,
    /** Online lookup is opt-in because it sends title/artist/album metadata to the provider. */
    val automaticOnlineLookup: Boolean = false,
    val folderUris: Set<String> = emptySet(),
)

class LyricsAutomationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun get(): LyricsAutomationSettings = LyricsAutomationSettings(
        searchAuthorizedFolders = preferences.getBoolean(KEY_LOCAL, true),
        readEmbeddedLyrics = preferences.getBoolean(KEY_EMBEDDED, true),
        automaticOnlineLookup = preferences.getBoolean(KEY_ONLINE, false),
        folderUris = preferences.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toSet(),
    )

    fun update(transform: (LyricsAutomationSettings) -> LyricsAutomationSettings): LyricsAutomationSettings {
        val updated = transform(get())
        preferences.edit {
            putBoolean(KEY_LOCAL, updated.searchAuthorizedFolders)
            putBoolean(KEY_EMBEDDED, updated.readEmbeddedLyrics)
            putBoolean(KEY_ONLINE, updated.automaticOnlineLookup)
            putStringSet(KEY_FOLDERS, updated.folderUris)
        }
        return updated
    }

    fun addFolder(uri: Uri): LyricsAutomationSettings = update { current ->
        current.copy(folderUris = current.folderUris + uri.toString())
    }

    fun clearFolders(): LyricsAutomationSettings = update { it.copy(folderUris = emptySet()) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFERENCES_NAME = "lyrics_automation_preferences"
        const val KEY_LOCAL = "search_authorized_folders"
        const val KEY_EMBEDDED = "read_embedded_lyrics"
        const val KEY_ONLINE = "automatic_online_lookup"
        const val KEY_FOLDERS = "authorized_folder_uris"
    }
}
