package com.musicplayer.data.local.mediastore

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.musicplayer.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Filesystem walk for audio files.
     * folderPath blank → walk every mounted StorageVolume (covers USB on car HU).
     * Else → walk just that path.
     */
    fun scanSongs(folderPath: String = ""): List<Song> {
        val roots: List<File> =
            if (folderPath.isNotBlank()) listOf(File(folderPath))
            else mountedVolumeRoots()

        return roots
            .filter { it.exists() && it.canRead() }
            .flatMap { root ->
                root.walkTopDown()
                    .onEnter { dir -> !dir.isHidden }
                    .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                    .toList()
            }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                Song(
                    id          = hash64(file.absolutePath),
                    fileName    = file.name,
                    displayName = cleanFileName(file.name),
                    artist      = "",
                    album       = file.parentFile?.name ?: "",
                    duration    = 0L,
                    uri         = file.absolutePath,
                    albumArtUri = null
                )
            }
    }

    private fun mountedVolumeRoots(): List<File> {
        val sm = context.getSystemService(StorageManager::class.java)
        val fromSm: List<File> = sm?.storageVolumes
            ?.filter { it.state == Environment.MEDIA_MOUNTED }
            ?.mapNotNull { vol ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory
                else @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
            }
            ?: emptyList()

        // Some AOSP car ROMs do not surface USB through StorageManager — also
        // include any readable directory under /storage as a fallback.
        val fromFs: List<File> = File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
            ?: emptyList()

        val all = (fromSm + fromFs).distinctBy { it.absolutePath }
        return all.ifEmpty {
            @Suppress("DEPRECATION")
            listOfNotNull(Environment.getExternalStorageDirectory())
        }
    }

    private fun cleanFileName(name: String): String {
        val noExt = name.substringBeforeLast('.')
        val noTrack = noExt.replace(Regex("^\\d+[\\s._\\-]+"), "")
        return noTrack
            .replace(Regex("[_\\-]+"), " ")
            .trim()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .ifBlank { noExt }
    }

    // FNV-1a 64-bit. Stable across runs, 63-bit positive output for Room PRIMARY KEY.
    private fun hash64(s: String): Long {
        var h = -3750763034362895579L  // FNV offset basis
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L         // FNV prime
        }
        return h and Long.MAX_VALUE
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "mp4", "wma", "flac", "m4a", "ogg", "wav", "aac", "opus"
        )
    }
}
