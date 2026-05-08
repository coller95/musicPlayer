package com.musicplayer.data.prefs

import android.content.Context
import androidx.core.content.edit
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE)

    var scanFolder: String
        get() = prefs.getString(KEY_FOLDER, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FOLDER, value) }

    var lastScanTime: Long
        get() = prefs.getLong(KEY_LAST_SCAN, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SCAN, value) }

    fun availableVolumes(): List<StorageVolumeInfo> {
        val results = mutableListOf<StorageVolumeInfo>()
        val sm = context.getSystemService(StorageManager::class.java)

        sm.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .forEach { vol ->
                val path = vol.pathCompat() ?: return@forEach
                val label = when {
                    vol.isPrimary    -> "Internal  $path"
                    vol.isRemovable  -> "USB/SD  $path"
                    else             -> path
                }
                results += StorageVolumeInfo(label, path, vol.isRemovable)
            }

        // Fallback: scan /storage/ for any mounted dirs not already found
        // (some AOSP car ROMs don't surface volumes through StorageManager properly)
        val knownPaths = results.map { it.path }.toSet()
        val storageRoot = File("/storage")
        if (storageRoot.exists()) {
            storageRoot.listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
                ?.forEach { dir ->
                    if (dir.absolutePath !in knownPaths && dir.canRead()) {
                        results += StorageVolumeInfo("USB/SD  ${dir.absolutePath}", dir.absolutePath, true)
                    }
                }
        }

        return results
    }

    private fun StorageVolume.pathCompat(): String? {
        // API 29+: getDirectory() returns File?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            directory?.absolutePath?.let { return it }
        }
        // Reflection fallback for older builds
        return try {
            StorageVolume::class.java.getMethod("getPath").invoke(this) as? String
        } catch (_: Exception) { null }
    }

    data class StorageVolumeInfo(val label: String, val path: String, val isRemovable: Boolean)

    companion object {
        private const val KEY_FOLDER = "scan_folder"
        private const val KEY_LAST_SCAN = "last_scan_time"
    }
}
