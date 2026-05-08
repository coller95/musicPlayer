package com.musicplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Triggers a rescan callback when USB is mounted or unmounted.
// Registered dynamically in MainActivity (not in manifest) so it only
// fires while the app is in the foreground — avoids waking the app for
// every USB event on the car unit.
class UsbMountReceiver(
    private val onUsbMounted: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_SCANNER_FINISHED -> onUsbMounted()
            // ACTION_MEDIA_REMOVED / UNMOUNTED: no-op — keep cached songs
        }
    }
}
