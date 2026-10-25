package com.melody.local.ui

import android.content.Context
import androidx.core.content.edit

internal class PlaylistPreferences(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    var lastOpenedPlaylistId: Long?
        get() = preferences.getLong(KEY_LAST_OPENED_PLAYLIST, 0L).takeIf { it > 0L }
        set(value) = preferences.edit {
            if (value != null && value > 0L) putLong(KEY_LAST_OPENED_PLAYLIST, value)
            else remove(KEY_LAST_OPENED_PLAYLIST)
        }

    var sort: PlaylistSort
        get() = PlaylistSort.entries.firstOrNull {
            it.name == preferences.getString(KEY_SORT, null)
        } ?: PlaylistSort.ADDED
        set(value) = preferences.edit { putString(KEY_SORT, value.name) }

    companion object {
        const val PREFERENCES_NAME = "playlist_preferences"
        const val KEY_LAST_OPENED_PLAYLIST = "last_opened_playlist_id"
        const val KEY_SORT = "sort"
    }
}
