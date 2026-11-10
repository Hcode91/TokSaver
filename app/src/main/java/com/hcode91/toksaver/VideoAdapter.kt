package com.hcode91.toksaver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.progressindicator.LinearProgressIndicator

class VideoAdapter(
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = getItem(position)
        if (h.boundThumb != v.thumbnail) {
            h.boundThumb = v.thumbnail
            h.thumb.load(v.thumbnail) { crossfade(true) }
        }
        h.tint.visibility = if (v.selected) View.VISIBLE else View.GONE
        h.check.visibility = if (v.selected) View.VISIBLE else View.GONE
        h.saved.visibility = if (v.saved && v.state !is DlState.Running) View.VISIBLE else View.GONE
        h.views.text = v.views?.let { "▶ " + compact(it) } ?: ""
        h.duration.text = v.duration?.let { "%d:%02d".format(it / 60, it % 60) } ?: ""
        h.duration.visibility = if (v.duration != null) View.VISIBLE else View.GONE

        when (val st = v.state) {
            is DlState.Running -> {
                h.progress.visibility = View.VISIBLE
                h.progress.setProgressCompat(st.percent, true)
                h.stateText.visibility = View.VISIBLE
                h.stateText.text = "${st.percent}%"
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
