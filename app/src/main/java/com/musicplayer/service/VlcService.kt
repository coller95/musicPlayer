package com.musicplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.musicplayer.R
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class VlcService : Service() {

    inner class VlcBinder : Binder() {
        val service: VlcService get() = this@VlcService
    }

    private val binder = VlcBinder()
    lateinit var libVLC: LibVLC
        private set
    lateinit var mediaPlayer: MediaPlayer
        private set

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // True while we voluntarily paused due to transient focus loss (e.g. nav prompt)
    private var pausedForFocus = false

    // Path queued while waiting for AUDIOFOCUS_REQUEST_DELAYED to be granted.
    private var pendingPlayPath: String? = null

    private val afListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer.volume = 100
                val pending = pendingPlayPath
                if (pending != null) {
                    pendingPlayPath = null
                    play(pending)
                } else if (pausedForFocus) {
                    mediaPlayer.play()
                    pausedForFocus = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocus = false
                pendingPlayPath = null
                mediaPlayer.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    pausedForFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer.volume = 30
            }
        }
    }

    private fun createLibVLC(): LibVLC {
        val hwOptions = arrayListOf(
            "--no-video",
            "--file-caching=300",
            "--network-caching=2000",
            "--disc-caching=300",
            "--live-caching=1000",
            "--avcodec-hw=any",
            "--aout=opensles",
            "--audio-resampler=soxr",
            "--opensles-audio-output-buffer-size=100",
            "--no-stats",
            "--no-snapshot-preview",
        )
        return try {
            LibVLC(this, hwOptions)
        } catch (_: Exception) {
            LibVLC(this, arrayListOf("--no-video", "--file-caching=300", "--no-stats"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        libVLC = createLibVLC()
        mediaPlayer = MediaPlayer(libVLC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * Request audio focus then start playback.
     * Granted: play immediately. Delayed: queue path; play from AUDIOFOCUS_GAIN. Failed: do not interrupt.
     */
    fun requestAudioFocusAndPlay(path: String) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(afListener)
            .build()
        audioFocusRequest = req
        when (audioManager.requestAudioFocus(req)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                pendingPlayPath = null
                play(path)
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                pendingPlayPath = path
            }
            else -> {
                pendingPlayPath = null
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    fun play(path: String) {
        val uri = if (path.startsWith("/")) "file://$path".toUri()
                  else path.toUri()
        val media = Media(libVLC, uri)
        // Async parse — duration/codec resolved off the calling thread; avoids
        // ANR when scanning files on slow USB drives.
        media.parseAsync()
        mediaPlayer.media = media
        mediaPlayer.play()
        media.release()
    }

    fun togglePause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    fun seekTo(timeMs: Long) {
        mediaPlayer.time = timeMs
    }

    val isPlaying: Boolean get() = mediaPlayer.isPlaying
    val currentTimeMs: Long get() = mediaPlayer.time
    val durationMs: Long get() = mediaPlayer.length

    override fun onDestroy() {
        abandonAudioFocus()
        if (mediaPlayer.isPlaying) mediaPlayer.stop()
        mediaPlayer.release()
        libVLC.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "playback"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notification_channel_playback),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
