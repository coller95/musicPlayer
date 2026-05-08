package com.musicplayer.presentation.player

import android.graphics.Color
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

data class UpNextItem(val song: Song, val globalIndex: Int, val position: Int)

class UpNextAdapter(
    private val onTrackClick: (Int) -> Unit,
) : ListAdapter<UpNextItem, UpNextAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val pos: TextView    = view.findViewById(R.id.tvUpNextPos)
        val art: ImageView   = view.findViewById(R.id.ivUpNextArt)
        val title: TextView  = view.findViewById(R.id.tvUpNextTitle)
        val artist: TextView = view.findViewById(R.id.tvUpNextArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_up_next, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.pos.text    = String.format(java.util.Locale.ROOT, "%d", item.position)
        holder.title.text  = item.song.displayName.ifBlank { item.song.fileName }
        holder.artist.text = item.song.artist.ifBlank { "Unknown Artist" }

        val artSize = (32 * ctx.resources.displayMetrics.density).toInt()
        holder.art.setImageBitmap(AlbumArtGenerator.generate(item.song, artSize))

        // First item gets slightly highlighted background
        if (position == 0) {
            holder.itemView.setBackgroundColor("#0DFFFFFF".toColorInt())
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener { onTrackClick(item.globalIndex) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UpNextItem>() {
            override fun areItemsTheSame(a: UpNextItem, b: UpNextItem) = a.song.id == b.song.id
            override fun areContentsTheSame(a: UpNextItem, b: UpNextItem) = a == b
        }
    }
}
