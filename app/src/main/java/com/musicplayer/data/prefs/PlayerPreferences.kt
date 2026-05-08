package com.musicplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(name = "player_prefs")

@Singleton
class PlayerPreferences @Inject constructor(
    @ApplicationContext context: Context
) {

    private val store: DataStore<Preferences> = context.playerDataStore

    private object Keys {
        val LAST_SONG_ID  = longPreferencesKey("last_song_id")
        val LAST_POSITION = longPreferencesKey("last_position_ms")
        val REPEAT_MODE   = intPreferencesKey("repeat_mode")
        val SHUFFLE       = intPreferencesKey("shuffle")
        val VOLUME        = floatPreferencesKey("volume")
    }

    data class PlayerPrefs(
        val lastSongId: Long = -1L,
        val lastPositionMs: Long = 0L,
        val repeatMode: Int = 0,
        val shuffle: Boolean = false,
        val volume: Float = 0.6f,
    )

    val playerPrefsFlow: Flow<PlayerPrefs> = store.data.map { prefs ->
        PlayerPrefs(
            lastSongId     = prefs[Keys.LAST_SONG_ID]  ?: -1L,
            lastPositionMs = prefs[Keys.LAST_POSITION] ?: 0L,
            repeatMode     = prefs[Keys.REPEAT_MODE]   ?: 0,
            shuffle        = (prefs[Keys.SHUFFLE]      ?: 0) != 0,
            volume         = prefs[Keys.VOLUME]        ?: 0.6f,
        )
    }

    suspend fun saveLastSong(songId: Long, positionMs: Long) {
        store.edit { prefs ->
            prefs[Keys.LAST_SONG_ID]  = songId
            prefs[Keys.LAST_POSITION] = positionMs
        }
    }

    suspend fun saveSettings(repeatMode: Int, shuffle: Boolean, volume: Float) {
        store.edit { prefs ->
            prefs[Keys.REPEAT_MODE] = repeatMode
            prefs[Keys.SHUFFLE]     = if (shuffle) 1 else 0
            prefs[Keys.VOLUME]      = volume
        }
    }
}
