package com.musicplayer.presentation.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.toColorInt
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.R
import com.musicplayer.domain.model.Song

data class QueueItem(val song: Song, val globalIndex: Int, val isCurrent: Boolean)

/**
 * Spec Build (Variant D) song list adapter.
 * 5-column row: # | art | title+artist | format badge | duration
 */
class SongListAdapter(
    private val onTrackClick: (Int) -> Unit,
) : ListAdapter<QueueItem, SongListAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).song.id

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val trackNumber: TextView = view.findViewById(R.id.tvTrackNumber)
        val albumArt: ImageView   = view.findViewById(R.id.ivQueueAlbumArt)
        val title: TextView       = view.findViewById(R.id.tvQueueTitle)
        val artist: TextView      = view.findViewById(R.id.tvQueueArtist)
        val format: TextView      = view.findViewById(R.id.tvQueueFormat)
        val duration: TextView    = view.findViewById(R.id.tvQueueDuration)
        val bar: View             = view.findViewById(R.id.viewNowPlayingBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val song = item.song
        val context = holder.itemView.context

        // Track number (1-based display)
        val trackNum = position + 1

        // Album art (48dp rounded rect)
        val artSize = (48 * context.resources.displayMetrics.density).toInt()
        val bitmap = AlbumArtGenerator.generate(song, artSize)
        holder.albumArt.setImageBitmap(bitmap)

        holder.title.text  = song.displayName.ifBlank { song.fileName }
        holder.artist.text = song.artist.ifBlank { "Unknown Artist" }
        holder.duration.text = fmtTime(song.duration / 1000)

        // Format badge
        val ext = song.uri.substringAfterLast('.', "").uppercase().take(4)
        holder.format.text = ext
        val (textColor, bgColor) = when (ext) {
            "MP3"  -> Pair("#4ADE80".toColorInt(), "#1A4ADE80".toColorInt())
            "WMA"  -> Pair("#22D3EE".toColorInt(), "#1A22D3EE".toColorInt())
            "FLAC" -> Pair("#A78BFA".toColorInt(), "#1AA78BFA".toColorInt())
            "AAC"  -> Pair("#4ADE80".toColorInt(), "#1A4ADE80".toColorInt())
            "OGG"  -> Pair("#22D3EE".toColorInt(), "#1A22D3EE".toColorInt())
            else   -> Pair("#A78BFA".toColorInt(), "#1AA78BFA".toColorInt())
        }
        holder.format.setTextColor(textColor)
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = 4 * context.resources.displayMetrics.density
        }
        holder.format.background = badgeBg

        if (item.isCurrent) {
            holder.trackNumber.text = "▶"
            holder.trackNumber.setTextColor("#FF7A1A".toColorInt())
            holder.title.setTextColor("#FFFFFF".toColorInt())
            holder.duration.setTextColor("#FF7A1A".toColorInt())
            holder.itemView.setBackgroundColor("#33FF7A1A".toColorInt())
            holder.bar.visibility = View.VISIBLE
        } else {
            holder.trackNumber.text = String.format(java.util.Locale.ROOT, "%d", trackNum)
            holder.trackNumber.setTextColor("#99FFFFFF".toColorInt())
            holder.title.setTextColor("#FFFFFF".toColorInt())
            holder.duration.setTextColor("#CCFFFFFF".toColorInt())
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.bar.visibility = View.INVISIBLE
        }

        // Bottom divider drawn via item decoration in the RecyclerView setup

        holder.itemView.setOnClickListener { onTrackClick(item.globalIndex) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(a: QueueItem, b: QueueItem) = a.song.id == b.song.id
            override fun areContentsTheSame(a: QueueItem, b: QueueItem) = a == b
        }

        fun fmtTime(secs: Long): String {
            val m = secs / 60; val s = secs % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }
    }
}
