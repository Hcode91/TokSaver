package com.hcode91.toksaver

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.widget.FrameLayout
import android.util.AttributeSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val animatedThumb: String? = null,
    val duration: Int?,
    val views: Long?,
    val timestamp: Long?,
    val selected: Boolean = false,
    val saved: Boolean = false,
    val state: DlState = DlState.Idle,
) {
    fun toCache(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title)
        put("thumb", thumbnail ?: ""); put("anim", animatedThumb ?: "")
        put("dur", duration ?: -1); put("views", views ?: -1); put("ts", timestamp ?: -1)
    }

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
                animatedThumb = pickAnimated(o),
                duration = o.optInt("duration", -1).takeIf { it > 0 },
                views = o.optLong("view_count", -1).takeIf { it >= 0 },
                timestamp = o.optLong("timestamp", -1).takeIf { it > 0 },
            )
        } catch (e: Exception) {
            null
        }

        /** TikTok's own short looping preview (animated webp), if offered. */
        private fun pickAnimated(o: JSONObject): String? {
            val arr = o.optJSONArray("thumbnails") ?: return null
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("id") == "dynamicCover") {
                    return t.optString("url").takeIf { it.startsWith("http") }
                }
            }
            return null
        }

        fun fromCache(o: JSONObject) = Video(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.optString("title", ""),
            thumbnail = o.optString("thumb").ifBlank { null },
            animatedThumb = o.optString("anim").ifBlank { null },
            duration = o.optInt("dur", -1).takeIf { it > 0 },
            views = o.optLong("views", -1).takeIf { it >= 0 },
            timestamp = o.optLong("ts", -1).takeIf { it > 0 },
        )

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

/** Saved video lists per username, so big profiles open instantly. */
class ListCache(context: Context) {
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private fun file(user: String) = File(dir, user.lowercase() + ".json")

    data class Entry(val videos: List<Video>, val savedAt: Long, val complete: Boolean)

    fun load(user: String): Entry? = try {
        val f = file(user)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("videos")
            Entry((0 until arr.length()).map { Video.fromCache(arr.getJSONObject(it)) }, o.getLong("savedAt"), o.optBoolean("complete", false))
        }
    } catch (e: Exception) {
        null
    }

    fun save(user: String, videos: List<Video>, complete: Boolean) {
        if (videos.isEmpty()) return
        try {
            val arr = JSONArray()
            videos.forEach { arr.put(it.toCache()) }
            val tmp = File(dir, "$user.tmp")
            tmp.writeText(JSONObject().put("savedAt", System.currentTimeMillis()).put("complete", complete).put("videos", arr).toString())
            tmp.renameTo(file(user))
        } catch (_: Exception) { }
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

    var personFilter: PersonFilter
        get() = PersonFilter(
            enabled = prefs.getBoolean("__pf_on", false),
            minConfidence = prefs.getFloat("__pf_conf", 0.5f),
            minAreaPercent = prefs.getInt("__pf_area", 10),
        )
        set(v) = prefs.edit()
            .putBoolean("__pf_on", v.enabled)
            .putFloat("__pf_conf", v.minConfidence)
            .putInt("__pf_area", v.minAreaPercent)
            .apply()

    var animatedPreviews: Boolean
        get() = prefs.getBoolean("__anim", true)
        set(v) = prefs.edit().putBoolean("__anim", v).apply()

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
