package com.melody.local.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDatabaseSingletonTest {
    @Test
    fun opensTheRealFileDatabaseOnceAndReusesTheSingleton() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = PlaylistDatabase.getInstance(context)
        val second = PlaylistDatabase.getInstance(context)
        val name = "singleton-test-${System.nanoTime()}"

        assertSame(first, second)
        val id = first.playlistDao().insertPlaylist(PlaylistEntity(name = name))
        assertEquals(id, second.playlistDao().findPlaylistIdByName(name.uppercase()))
        second.playlistDao().deletePlaylist(id)
    }
}

