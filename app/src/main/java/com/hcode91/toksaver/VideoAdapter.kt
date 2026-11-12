package com.hcode91.toksaver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.google.android.material.progressindicator.LinearProgressIndicator

class VideoAdapter(
    private val imageLoader: ImageLoader,
    private val animated: () -> Boolean,
    private val onTap: (Video) -> Unit,
    private val onLongPress: (Video) -> Unit,
) : ListAdapter<Video, VideoAdapter.VH>(Diff) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val tint: View = v.findViewById(R.id.selectedTint)
        val check: TextView = v.findViewById(R.id.check)
        val saved: TextView = v.findViewById(R.id.savedBadge)
        val stateText: TextView = v.findViewById(R.id.stateText)
        val views: TextView = v.findViewById(R.id.views)
        val duration: TextView = v.findViewById(R.id.duration)
        val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
        var boundThumb: String? = null
    }

    /** Tile shape for the current platform. */
    var platform: Platform = Platform.TIKTOK
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    /** Forces thumbnails to reload, e.g. after toggling animated previews. */
    fun reloadThumbs() = notifyItemRangeChanged(0, itemCount)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = getItem(position)
        (h.itemView as AspectFrame).apply {
            if (ratioW != platform.aspectW || ratioH != platform.aspectH) {
                ratioW = platform.aspectW; ratioH = platform.aspectH; requestLayout()
            }
        }
        // Static cover first, then swap to TikTok's looping preview clip once it's ready.
        val anim = if (animated()) v.animatedThumb else null
        val key = "${v.thumbnail}|$anim"
        if (h.boundThumb != key) {
            h.boundThumb = key
            h.thumb.load(v.thumbnail, imageLoader) {
                crossfade(true)
                listener(onSuccess = { _, _ ->
                    if (anim != null && h.boundThumb == key) {
                        val still = h.thumb.drawable
                        h.thumb.load(anim, imageLoader) {
                            placeholder(still)
                            error(still)
                        }
                    }
                })
            }
        }
        h.tint.visibility = if (v.selected) View.VISIBLE else View.GONE
        h.check.visibility = if (v.selected) View.VISIBLE else View.GONE
        h.saved.visibility = if (v.saved && v.state !is DlState.Running) View.VISIBLE else View.GONE
        h.views.text = v.views?.let { "▶ " + compact(it) } ?: ""
        h.duration.text = when {
            v.media.size > 1 -> "▣ ${v.media.size}"          // carousel
            v.isPhotoOnly -> "Photo"
            v.duration != null -> "%d:%02d".format(v.duration / 60, v.duration % 60)
            else -> ""
        }
        h.duration.visibility = if (h.duration.text.isNotEmpty()) View.VISIBLE else View.GONE

        when (val st = v.state) {
            is DlState.Running -> {
                h.progress.visibility = View.VISIBLE
                h.progress.setProgressCompat(st.percent, true)
                h.stateText.visibility = View.VISIBLE
                h.stateText.text = when {
                    st.gifs -> "GIFs ${st.percent}%"
                    st.frames -> "Frames ${st.percent}%"
                    else -> "${st.percent}%"
                }
            }
            DlState.Queued -> {
                h.progress.visibility = View.GONE
                h.stateText.visibility = View.VISIBLE
                h.stateText.text = "Queued"
            }
            is DlState.Failed -> {
                h.progress.visibility = View.GONE
                h.stateText.visibility = View.VISIBLE
                h.stateText.text = "Failed"
            }
            else -> {
                h.progress.visibility = View.GONE
                h.stateText.visibility = View.GONE
            }
        }

        h.itemView.setOnClickListener { onTap(v) }
        h.itemView.setOnLongClickListener { onLongPress(v); true }
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }.replace(".0", "")

    object Diff : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(a: Video, b: Video) = a.id == b.id
        override fun areContentsTheSame(a: Video, b: Video) = a == b
    }
}
