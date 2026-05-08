package com.musicplayer.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicplayer.R
import com.musicplayer.databinding.ActivityMainBinding
import com.musicplayer.presentation.player.AlbumArtGenerator
import com.musicplayer.presentation.player.PlayerState
import com.musicplayer.presentation.player.PlayerViewModel
import com.musicplayer.presentation.player.QueueItem
import com.musicplayer.presentation.player.RepeatMode
import com.musicplayer.presentation.player.SongListAdapter
import com.musicplayer.presentation.player.UpNextAdapter
import com.musicplayer.presentation.player.UpNextItem
import com.musicplayer.receiver.UsbMountReceiver
import com.musicplayer.service.VlcService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: PlayerViewModel by viewModels()

    private var vlcService: VlcService? = null
    private var serviceBound = false

    private var restorePending = true

    private val usbReceiver = UsbMountReceiver { vm.forceScan() }

    private lateinit var songListAdapter: SongListAdapter
    private lateinit var upNextAdapter: UpNextAdapter

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private val progressHandler = Handler(Looper.getMainLooper())

    private val clockTicker = object : Runnable {
        override fun run() {
            val now = Date()
            binding.tvClock.text = clockFormat.format(now)
            binding.tvDate.text = dateFormat.format(now)
            progressHandler.postDelayed(this, 60_000)
        }
    }

    private var positionSaveTick = 0
    private val progressTicker = object : Runnable {
        override fun run() {
            vlcService?.let { svc ->
                if (svc.isPlaying) {
                    vm.updatePosition(svc.currentTimeMs)
                    updateScrubbers(svc.currentTimeMs, svc.durationMs)

                    positionSaveTick++
                    if (positionSaveTick >= 10) {
                        positionSaveTick = 0
                        vm.persistPosition()
                    }
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vlcService = (binder as VlcService.VlcBinder).service
            serviceBound = true

            applyVolumeToVlc(vm.state.value.volume)

            if (!restorePending) {
                vm.currentSong?.let { playCurrentSong() }
            }

            progressHandler.post(progressTicker)

            vlcService?.mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    org.videolan.libvlc.MediaPlayer.Event.Playing ->
                        vm.persistCurrentSong()
                    org.videolan.libvlc.MediaPlayer.Event.EndReached ->
                        runOnUiThread { vm.onTrackEnded(); playCurrentSong() }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vlcService?.mediaPlayer?.setEventListener(null)
            vlcService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.loadOrScan() }

    private val audioPermission get() = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ->
            Manifest.permission.READ_MEDIA_AUDIO
        else ->
            Manifest.permission.READ_EXTERNAL_STORAGE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSongList()
        setupUpNext()
        setupTransportControls()
        setupSettings()
        observeState()
        observeRestoreEvent()
        requestPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val now = Date()
        binding.tvClock.text = clockFormat.format(now)
        binding.tvDate.text = dateFormat.format(now)
        progressHandler.postDelayed(clockTicker, 60_000)

        val intent = Intent(this, VlcService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        vm.persistPosition()

        progressHandler.removeCallbacks(progressTicker)
        progressHandler.removeCallbacks(clockTicker)
        unregisterReceiver(usbReceiver)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    // ——— Restore last session ———

    private fun observeRestoreEvent() {
        lifecycleScope.launch {
            vm.restoreEvent.collectLatest { session ->
                restorePending = false
                val svc = vlcService
                if (svc != null) {
                    playCurrentSong()
                    if (session.positionMs > 0L) {
                        progressHandler.postDelayed({
                            svc.seekTo(session.positionMs)
                            vm.seekTo(session.positionMs)
                        }, 600)
                    }
                }
            }
        }
    }

    // ——— Permission ———

    private fun requestPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            vm.loadOrScan()
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    // ——— VLC playback ———

    private fun playCurrentSong() {
        val song = vm.currentSong ?: return
        vlcService?.requestAudioFocusAndPlay(song.uri)
        applyVolumeToVlc(vm.state.value.volume)
    }

    private fun applyVolumeToVlc(volume: Float) {
        vlcService?.mediaPlayer?.volume = (volume * 100f).toInt().coerceIn(0, 100)
    }

    // ——— UI setup ———

    private fun setupSongList() {
        songListAdapter = SongListAdapter { index ->
            vm.selectTrack(index)
            playCurrentSong()
        }

        binding.rvQueue.apply {
            adapter = songListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(false)
            setItemViewCacheSize(30)
            recycledViewPool.setMaxRecycledViews(0, 20)
        }
    }

    private fun setupUpNext() {
        upNextAdapter = UpNextAdapter { index ->
            vm.selectTrack(index)
            playCurrentSong()
        }
        binding.rvUpNext.apply {
            adapter = upNextAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
        }
    }

    private fun setupSettings() {
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnSettingsClose.setOnClickListener { binding.settingsSheet.visibility = View.GONE }
        binding.btnScanNow.setOnClickListener {
            binding.settingsSheet.visibility = View.GONE
            vm.forceScan()
        }
    }

    private fun openSettings() {
        val prefs = vm.scanPreferences
        val folder = prefs.scanFolder
        binding.tvCurrentFolder.text = if (folder.isBlank()) "All storage (no folder set)" else folder

        val count = vm.state.value.songs.size
        val lastScan = prefs.lastScanTime
        binding.tvScanStats.text = if (lastScan == 0L) "Not scanned yet · $count songs cached"
        else {
            val time = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(lastScan))
            "$count songs · Last scan: $time"
        }

        binding.llVolumeButtons.removeAllViews()
        val volumes = prefs.availableVolumes()
        volumes.forEach { vol ->
            val btn = Button(this).apply {
                text = if (vol.isRemovable) "USB: ${vol.path}" else "Internal: ${vol.path}"
                textSize = 12f
                isAllCaps = false
                val isSelected = vol.path == prefs.scanFolder
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_storage_option_selected
                    else R.drawable.bg_storage_option
                )
                setTextColor(
                    if (isSelected) getColor(R.color.accent)
                    else getColor(R.color.text_secondary)
                )
                setPadding(32, 16, 32, 16)
                stateListAnimator = null
                setOnClickListener {
                    prefs.scanFolder = vol.path
                    openSettings()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 12.dpToPx() }
            binding.llVolumeButtons.addView(btn, lp)
        }
        val allBtn = Button(this).apply {
            text = getString(R.string.storage_all)
            textSize = 12f
            isAllCaps = false
            val isSelected = prefs.scanFolder.isBlank()
            setBackgroundResource(
                if (isSelected) R.drawable.bg_storage_option_selected
                else R.drawable.bg_storage_option
            )
            setTextColor(
                if (isSelected) getColor(R.color.accent)
                else getColor(R.color.text_secondary)
            )
            setPadding(32, 16, 32, 16)
            stateListAnimator = null
            setOnClickListener {
                prefs.scanFolder = ""
                openSettings()
            }
        }
        binding.llVolumeButtons.addView(allBtn)

        // Recently played
        binding.llRecentlyPlayed.removeAllViews()
        val history = vm.state.value.playHistory.reversed()
        val songs = vm.state.value.songs
        if (history.isEmpty()) {
            val empty = android.widget.TextView(this).apply {
                text = getString(R.string.history_empty)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            }
            binding.llRecentlyPlayed.addView(empty)
        } else {
            history.forEachIndexed { pos, idx ->
                val song = songs.getOrNull(idx) ?: return@forEachIndexed
                val row = android.widget.TextView(this).apply {
                    text = getString(R.string.history_item, pos + 1, song.displayName.ifBlank { song.fileName })
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(0, 6, 0, 6)
                    setOnClickListener {
                        binding.settingsSheet.visibility = View.GONE
                        vm.selectTrack(idx)
                        playCurrentSong()
                    }
                }
                binding.llRecentlyPlayed.addView(row)
            }
        }

        binding.settingsSheet.visibility = View.VISIBLE
    }

    private fun setupTransportControls() {
        binding.btnPlayPause.setOnClickListener {
            vm.togglePlayPause()
            vlcService?.togglePause()
            vm.persistPosition()
        }
        binding.btnNext.setOnClickListener {
            vm.skipNext()
            playCurrentSong()
        }
        binding.btnPrev.setOnClickListener {
            vm.skipPrev()
            playCurrentSong()
        }
        binding.btnShuffle.setOnClickListener { vm.toggleShuffle() }
        binding.btnRepeat.setOnClickListener { vm.cycleRepeat() }
        binding.seekbarProgress.setOnSeekBarChangeListener(seekListener { progress ->
            val dur = vlcService?.durationMs ?: return@seekListener
            val posMs = (progress / 100f * dur).toLong()
            vlcService?.seekTo(posMs)
            vm.seekTo(posMs)
        })
    }

    // ——— State observation ———

    private fun observeState() {
        lifecycleScope.launch {
            vm.state
                .map { it.copy(positionMs = 0L) }
                .distinctUntilChanged()
                .collectLatest { state ->
                    renderTransportBar(state)
                    renderSongList(state)
                    renderUpNext(state)
                    binding.btnScanNow.isEnabled = !state.isScanning
                    binding.btnScanNow.text = if (state.isScanning) "Scanning…" else "Scan Now"
                    val count = state.songs.size
                    binding.tvTrackCount.text = if (count > 0) "$count tracks" else ""
                    applyVolumeToVlc(state.volume)
                }
        }
    }

    private fun renderTransportBar(state: PlayerState) {
        val song = state.songs.getOrNull(state.currentIndex) ?: return

        // Album art (64dp)
        val artPx = (64 * resources.displayMetrics.density).toInt()
        binding.ivMiniAlbumArt.setImageBitmap(AlbumArtGenerator.generate(song, artPx))
        binding.tvMiniTitle.text  = song.displayName.ifBlank { song.fileName }
        binding.tvMiniArtist.text = song.artist.ifBlank { "Unknown Artist" }

        // Play/Pause icon
        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        val accentColor   = getColor(R.color.accent)
        val inactiveColor = getColor(R.color.icon_inactive)

        // Shuffle button
        if (state.shuffle) {
            binding.btnShuffle.setColorFilter(accentColor)
            binding.btnShuffle.setBackgroundResource(R.drawable.bg_btn_active)
            binding.tvShuffleOn.visibility = View.VISIBLE
        } else {
            binding.btnShuffle.setColorFilter(inactiveColor)
            binding.btnShuffle.setBackgroundResource(R.drawable.bg_dim_button)
            binding.tvShuffleOn.visibility = View.GONE
        }

        // Repeat button
        val repeatOn = state.repeatMode != RepeatMode.OFF
        if (repeatOn) {
            binding.btnRepeat.setColorFilter(accentColor)
            binding.btnRepeat.setBackgroundResource(R.drawable.bg_btn_active)
            binding.tvRepeatOn.text = when (state.repeatMode) {
                RepeatMode.ONE -> "1"
                RepeatMode.ALL -> "ALL"
                RepeatMode.OFF -> ""
            }
            binding.tvRepeatOn.visibility = View.VISIBLE
        } else {
            binding.btnRepeat.setColorFilter(inactiveColor)
            binding.btnRepeat.setBackgroundResource(R.drawable.bg_dim_button)
            binding.tvRepeatOn.visibility = View.GONE
        }

        // Prev/Next icon tint
        binding.btnPrev.setColorFilter(getColor(R.color.text_primary))
        binding.btnNext.setColorFilter(getColor(R.color.text_primary))
    }

    private fun updateScrubbers(posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        val pct = (posMs * 100 / durMs).toInt().coerceIn(0, 100)
        binding.seekbarProgress.progress = pct
        val elapsed   = SongListAdapter.fmtTime(posMs / 1000)
        val remaining = "-${SongListAdapter.fmtTime((durMs - posMs).coerceAtLeast(0) / 1000)}"
        binding.tvTimeElapsed.text   = elapsed
        binding.tvTimeRemaining.text = remaining
    }

    private fun renderSongList(state: PlayerState) {
        val items = state.songs.mapIndexed { idx, song ->
            QueueItem(song, idx, idx == state.currentIndex)
        }
        songListAdapter.submitList(items) {
            val lm = binding.rvQueue.layoutManager as? LinearLayoutManager ?: return@submitList
            val first = lm.findFirstVisibleItemPosition()
            val last  = lm.findLastVisibleItemPosition()
            val target = state.currentIndex
            if (target !in first..last) {
                if (target in (first - 50)..(last + 50)) {
                    binding.rvQueue.smoothScrollToPosition(target)
                } else {
                    binding.rvQueue.scrollToPosition(target)
                }
            }
        }
    }

    private fun renderUpNext(state: PlayerState) {
        val items = state.upNextIndices.mapIndexed { pos, idx ->
            val song = state.songs.getOrNull(idx) ?: return@mapIndexed null
            UpNextItem(song = song, globalIndex = idx, position = pos + 1)
        }.filterNotNull()
        upNextAdapter.submitList(items)
    }

    // ——— Helpers ———

    private fun seekListener(onUser: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onUser(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()
}
