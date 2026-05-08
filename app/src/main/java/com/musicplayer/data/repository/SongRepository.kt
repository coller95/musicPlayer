package com.musicplayer.data.repository

import com.musicplayer.data.local.db.SongDao
import com.musicplayer.data.local.db.SongEntity
import com.musicplayer.data.local.mediastore.MediaStoreDataSource
import com.musicplayer.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepository @Inject constructor(
    private val songDao: SongDao,
    private val mediaStoreDataSource: MediaStoreDataSource,
) {
    fun getAllSongs(): Flow<List<Song>> =
        songDao.getAllSongs().map { it.map(SongEntity::toDomain) }

    suspend fun scanDeviceSongs(folderPath: String = "") {
        withContext(Dispatchers.IO) {
            val entities = mediaStoreDataSource.scanSongs(folderPath).map(Song::toEntity)
            songDao.replaceAll(entities)
        }
    }

    suspend fun getCachedCount(): Int = songDao.count()
}

private fun SongEntity.toDomain() = Song(id, fileName, displayName, artist, album, duration, uri, albumArtUri)
private fun Song.toEntity() = SongEntity(id, fileName, displayName, artist, album, duration, uri, albumArtUri)
