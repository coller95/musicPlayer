package com.musicplayer.domain.model

data class Song(
    val id: Long,
    val fileName: String,       // raw filename e.g. "01_midnight_city.mp3"
    val displayName: String,    // cleaned e.g. "Midnight City"
    val artist: String,         // from tag, or empty if untagged
    val album: String,
    val duration: Long,
    val uri: String,
    val albumArtUri: String?
)
