package com.musicplayer.di

import android.content.Context
import androidx.room.Room
import com.musicplayer.data.local.db.MusicDatabase
import com.musicplayer.data.local.db.SongDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMusicDatabase(@ApplicationContext context: Context): MusicDatabase =
        Room.databaseBuilder(context, MusicDatabase::class.java, "music_db")
            .fallbackToDestructiveMigration()  // cache only — safe to wipe on schema change
            .build()

    @Provides
    fun provideSongDao(db: MusicDatabase): SongDao = db.songDao()
}
