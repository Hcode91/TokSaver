package com.hcode91.toksaver

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.widget.FrameLayout
import android.util.AttributeSet
import org.json.JSONObject

sealed class DlState {
    data object Idle : DlState()
    data object Queued : DlState()
    data class Running(val percent: Int, val frames: Boolean = false) : DlState()
    data object Done : DlState()
    data class Failed(val reason: String) : DlState()
}

data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val duration: Int?,
    val views: Long?,
    val timestamp: Long?,
    val selected: Boolean = false,
    val saved: Boolean = false,
    val state: DlState = DlState.Idle,
) {
    companion object {
        /** Parses one line of `yt-dlp --flat-playlist --dump-json` output. */
        fun fromJson(line: String, username: String): Video? = try {
            val o = JSONObject(line)
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.startsWith("http") }
                ?: o.optString("webpage_url").takeIf { it.startsWith("http") }
                ?: "https://www.tiktok.com/@$username/video/$id"
            Video(
                id = id,
                url = url,
                title = o.optString("title", ""),
                thumbnail = pickThumb(o),
                duration = o.optInt("duration", -1).takeIf { it > 0 },
                views = o.optLong("view_count", -1).takeIf { it >= 0 },
                timestamp = o.optLong("timestamp", -1).takeIf { it > 0 },
            )
        } catch (e: Exception) {
            null
        }

        private fun pickThumb(o: JSONObject): String? {
            o.optString("thumbnail").takeIf { it.startsWith("http") }?.let { return it }
            val arr = o.optJSONArray("thumbnails") ?: return null
            var fallback: String? = null
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val u = t.optString("url").takeIf { it.startsWith("http") } ?: continue
                // Prefer the static cover; dynamicCover is an animated webp.
                if (t.optString("id") == "cover" || t.optString("id") == "originCover") return u
                if (t.optString("id") != "dynamicCover" && fallback == null) fallback = u
            }
            return fallback ?: arr.optJSONObject(0)?.optString("url")
        }
    }
}

/** Remembers which video IDs have already been saved to the phone. */
class SavedStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved", MODE_PRIVATE)
    fun isSaved(id: String) = prefs.getBoolean(id, false)
    fun markSaved(id: String) = prefs.edit().putBoolean(id, true).apply()

    var lastUser: String
        get() = prefs.getString("__last_user", "") ?: ""
        set(v) = prefs.edit().putString("__last_user", v).apply()

    var frameMode: FrameMode
        get() = runCatching { FrameMode.valueOf(prefs.getString("__frame_mode", "NONE")!!) }
            .getOrDefault(FrameMode.NONE)
        set(v) = prefs.edit().putString("__frame_mode", v.name).apply()

    var lastUpdate: Long
        get() = prefs.getLong("__last_update", 0L)
        set(v) = prefs.edit().putLong("__last_update", v).apply()
}

/** FrameLayout that keeps a 9:16 portrait aspect ratio. */
class AspectFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w * 16 / 9
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
