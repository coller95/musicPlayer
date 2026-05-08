package com.musicplayer.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.data.prefs.PlayerPreferences
import com.musicplayer.data.prefs.ScanPreferences
import com.musicplayer.data.repository.SongRepository
import com.musicplayer.domain.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerState(
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val volume: Float = 0.6f,
    val isScanning: Boolean = false,
    /** Indices of recently played tracks, most recent last. Max 10 entries. */
    val playHistory: List<Int> = emptyList(),
    /** 3 random indices to show in the Up Next panel, regenerated on each track change. */
    val upNextIndices: List<Int> = emptyList(),
)

enum class RepeatMode { OFF, ALL, ONE }

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val songRepository: SongRepository,
    val scanPreferences: ScanPreferences,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val currentSong: Song? get() = state.value.run { songs.getOrNull(currentIndex) }

    private val _restoreEvent = MutableSharedFlow<RestoredSession>(replay = 0, extraBufferCapacity = 1)
    val restoreEvent: SharedFlow<RestoredSession> = _restoreEvent.asSharedFlow()

    data class RestoredSession(val positionMs: Long)

    init {
        viewModelScope.launch {
            val saved = playerPreferences.playerPrefsFlow.first()
            _state.update { it.copy(
                shuffle    = saved.shuffle,
                repeatMode = RepeatMode.entries[saved.repeatMode.coerceIn(0, 2)],
                volume     = saved.volume,
            ) }
        }

        var sessionRestored = false
        viewModelScope.launch {
            songRepository.getAllSongs().collect { songs ->
                _state.update { it.copy(songs = songs) }

                if (!sessionRestored && songs.isNotEmpty()) {
                    sessionRestored = true
                    applySavedSession(songs)
                }
            }
        }
    }

    private suspend fun applySavedSession(songs: List<Song>) {
        val saved = playerPreferences.playerPrefsFlow.first()
        val idx = if (saved.lastSongId >= 0) {
            songs.indexOfFirst { it.id == saved.lastSongId }.takeIf { it >= 0 } ?: 0
        } else 0

        _state.update { it.copy(
            currentIndex  = idx,
            positionMs    = saved.lastPositionMs,
            upNextIndices = randomUpNext(songs, idx),
        ) }

        _restoreEvent.emit(RestoredSession(saved.lastPositionMs))
    }

    private fun persistSettings() {
        val s = _state.value
        viewModelScope.launch {
            playerPreferences.saveSettings(
                repeatMode = s.repeatMode.ordinal,
                shuffle    = s.shuffle,
                volume     = s.volume,
            )
        }
    }

    fun persistPosition() {
        val s = _state.value
        val song = s.songs.getOrNull(s.currentIndex) ?: return
        viewModelScope.launch {
            playerPreferences.saveLastSong(song.id, s.positionMs)
        }
    }

    fun persistCurrentSong() {
        val s = _state.value
        val song = s.songs.getOrNull(s.currentIndex) ?: return
        viewModelScope.launch {
            playerPreferences.saveLastSong(song.id, 0L)
        }
    }

    fun loadOrScan() = viewModelScope.launch {
        if (songRepository.getCachedCount() == 0) {
            forceScan()
        }
    }

    fun forceScan() = viewModelScope.launch {
        _state.update { it.copy(isScanning = true) }
        songRepository.scanDeviceSongs(scanPreferences.scanFolder)
        scanPreferences.lastScanTime = System.currentTimeMillis()
        _state.update { it.copy(isScanning = false) }
    }

    private fun PlayerState.withHistory(fromIndex: Int): List<Int> =
        (playHistory + fromIndex).takeLast(10)

    private fun randomUpNext(songs: List<Song>, currentIndex: Int): List<Int> {
        if (songs.size <= 1) return emptyList()
        val pool = songs.indices.filter { it != currentIndex }
        return pool.shuffled().take(3)
    }

    fun selectTrack(index: Int) {
        _state.update { it.copy(currentIndex = index, positionMs = 0L, isPlaying = true, playHistory = it.withHistory(it.currentIndex), upNextIndices = randomUpNext(it.songs, index)) }
        persistSettings()
        val s = _state.value
        val song = s.songs.getOrNull(index) ?: return
        viewModelScope.launch { playerPreferences.saveLastSong(song.id, 0L) }
    }

    fun togglePlayPause() = _state.update { it.copy(isPlaying = !it.isPlaying) }

    fun skipNext() {
        _state.update {
            if (it.songs.isEmpty()) return@update it
            val next = if (it.shuffle) (0 until it.songs.size).random()
            else (it.currentIndex + 1) % it.songs.size
            it.copy(currentIndex = next, positionMs = 0L, isPlaying = true, playHistory = it.withHistory(it.currentIndex), upNextIndices = randomUpNext(it.songs, next))
        }
        val s = _state.value
        val song = s.songs.getOrNull(s.currentIndex) ?: return
        viewModelScope.launch { playerPreferences.saveLastSong(song.id, 0L) }
    }

    fun skipPrev() {
        _state.update {
            if (it.songs.isEmpty()) return@update it
            when {
                it.positionMs > 3000L -> it.copy(positionMs = 0L)
                it.playHistory.isNotEmpty() -> {
                    val prev = it.playHistory.last()
                    it.copy(currentIndex = prev, positionMs = 0L, isPlaying = true, playHistory = it.playHistory.dropLast(1), upNextIndices = randomUpNext(it.songs, prev))
                }
                else -> {
                    val prev = (it.currentIndex - 1 + it.songs.size) % it.songs.size
                    it.copy(currentIndex = prev, positionMs = 0L, isPlaying = true, upNextIndices = randomUpNext(it.songs, prev))
                }
            }
        }
        val s = _state.value
        val song = s.songs.getOrNull(s.currentIndex) ?: return
        viewModelScope.launch { playerPreferences.saveLastSong(song.id, 0L) }
    }

    fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
        persistSettings()
    }

    fun cycleRepeat() {
        _state.update {
            val next = when (it.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            it.copy(repeatMode = next)
        }
        persistSettings()
    }

    fun updatePosition(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }

    fun onTrackEnded() {
        _state.update {
            if (it.songs.isEmpty()) return@update it
            when (it.repeatMode) {
                RepeatMode.ONE -> it.copy(positionMs = 0L)
                else -> {
                    val next = if (it.shuffle) (0 until it.songs.size).random()
                    else (it.currentIndex + 1) % it.songs.size
                    it.copy(currentIndex = next, positionMs = 0L, playHistory = it.withHistory(it.currentIndex), upNextIndices = randomUpNext(it.songs, next))
                }
            }
        }
        val s = _state.value
        val song = s.songs.getOrNull(s.currentIndex) ?: return
        viewModelScope.launch { playerPreferences.saveLastSong(song.id, 0L) }
    }
}
