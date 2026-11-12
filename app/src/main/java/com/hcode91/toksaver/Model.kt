package com.hcode91.toksaver

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.AttributeSet
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed class DlState {
    data object Idle : DlState()
    data object Queued : DlState()
    data class Running(val percent: Int, val frames: Boolean = false, val gifs: Boolean = false) : DlState()
    data object Done : DlState()
    data class Failed(val reason: String) : DlState()
}

/** One photo or video inside a post (Instagram carousels have several). */
data class MediaItem(val url: String, val isVideo: Boolean)

data class Video(
    val id: String,
    val url: String,              // page to open in the app / browser
    val title: String,
    val thumbnail: String?,
    val animatedThumb: String? = null,
    val duration: Int?,
    val views: Long?,
    val timestamp: Long?,
    val media: List<MediaItem> = emptyList(),  // direct files (Instagram); empty = use yt-dlp
    val owner: String? = null,                 // account that posted it (Instagram browser)
    val selected: Boolean = false,
    val saved: Boolean = false,
    val state: DlState = DlState.Idle,
) {
    val isPhotoOnly get() = media.isNotEmpty() && media.none { it.isVideo }

    fun toCache(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title)
        put("thumb", thumbnail ?: ""); put("anim", animatedThumb ?: "")
        put("dur", duration ?: -1); put("views", views ?: -1); put("ts", timestamp ?: -1)
        owner?.let { put("owner", it) }
        if (media.isNotEmpty()) {
            put("media", JSONArray().apply {
                media.forEach { put(JSONObject().put("u", it.url).put("v", it.isVideo)) }
            })
        }
    }

    companion object {
        /** Parses one line of `yt-dlp --flat-playlist --dump-json` output. */
        fun fromJson(line: String, user: String, platform: Platform): Video? = try {
            val o = JSONObject(line)
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val fallbackUrl = when (platform) {
                Platform.TIKTOK -> "https://www.tiktok.com/@$user/video/$id"
                Platform.SHORTS -> "https://www.youtube.com/shorts/$id"
                else -> "https://www.youtube.com/watch?v=$id"
            }
            val url = o.optString("url").takeIf { it.startsWith("http") }
                ?: o.optString("webpage_url").takeIf { it.startsWith("http") }
                ?: fallbackUrl
            val thumb = when (platform) {
                Platform.TIKTOK -> pickTikTokThumb(o)
                Platform.YOUTUBE -> "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                else -> lastThumb(o) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            }
            Video(
                id = id,
                url = url,
                title = o.optString("title", ""),
                thumbnail = thumb,
                animatedThumb = if (platform == Platform.TIKTOK) pickAnimated(o) else null,
                duration = o.optDouble("duration", -1.0).toInt().takeIf { it > 0 },
                views = o.optLong("view_count", -1).takeIf { it >= 0 },
                timestamp = o.optLong("timestamp", -1).takeIf { it > 0 },
            )
        } catch (e: Exception) {
            null
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
            owner = o.optString("owner").ifBlank { null },
            media = o.optJSONArray("media")?.let { arr ->
                (0 until arr.length()).map {
                    val m = arr.getJSONObject(it)
                    MediaItem(m.getString("u"), m.getBoolean("v"))
                }
            }.orEmpty(),
        )

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

        private fun lastThumb(o: JSONObject): String? {
            val arr = o.optJSONArray("thumbnails") ?: return null
            for (i in arr.length() - 1 downTo 0) {
                arr.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") }?.let { return it }
            }
            return null
        }

        private fun pickTikTokThumb(o: JSONObject): String? {
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

/** Saved lists per platform + username, so big profiles open instantly. */
class ListCache(context: Context) {
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }

    // TikTok lists from before multi-platform were saved as "<user>.json".
    private fun file(name: String) = File(dir, "$name.json")

    data class Entry(val videos: List<Video>, val savedAt: Long, val complete: Boolean)

    fun load(platform: Platform, user: String): Entry? {
        val f = file(platform.cacheName(user)).takeIf { it.exists() }
            ?: if (platform == Platform.TIKTOK) file(user.lowercase()).takeIf { it.exists() } else null
        f ?: return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("videos")
            Entry(
                (0 until arr.length()).map { Video.fromCache(arr.getJSONObject(it)) },
                o.getLong("savedAt"),
                o.optBoolean("complete", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear(platform: Platform, user: String) {
        file(platform.cacheName(user)).delete()
    }

    fun save(platform: Platform, user: String, videos: List<Video>, complete: Boolean) {
        if (videos.isEmpty()) return
        try {
            val arr = JSONArray()
            videos.forEach { arr.put(it.toCache()) }
            val name = platform.cacheName(user)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(
                JSONObject().put("savedAt", System.currentTimeMillis())
                    .put("complete", complete).put("videos", arr).toString()
            )
            tmp.renameTo(file(name))
        } catch (_: Exception) { }
    }
}

/** Remembers which posts have already been saved, plus app settings. */
class SavedStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved", MODE_PRIVATE)
    fun isSaved(key: String) = prefs.getBoolean(key, false)
    fun markSaved(key: String) = prefs.edit().putBoolean(key, true).apply()

    fun lastUser(p: Platform): String =
        prefs.getString("__last_user_${p.name}", null)
            ?: if (p == Platform.TIKTOK) prefs.getString("__last_user", "") ?: "" else ""

    fun setLastUser(p: Platform, v: String) = prefs.edit().putString("__last_user_${p.name}", v).apply()

    var platform: Platform
        get() = runCatching { Platform.valueOf(prefs.getString("__platform", "TIKTOK")!!) }
            .getOrDefault(Platform.TIKTOK)
        set(v) = prefs.edit().putString("__platform", v.name).apply()

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

    var gifOptions: GifOptions
        get() = GifOptions(
            enabled = prefs.getBoolean("__gif_on", false),
            slowFactor = prefs.getInt("__gif_slow", 2),
        )
        set(v) = prefs.edit().putBoolean("__gif_on", v.enabled).putInt("__gif_slow", v.slowFactor).apply()

    var deleteOriginal: Boolean
        get() = prefs.getBoolean("__del_orig", false)
        set(v) = prefs.edit().putBoolean("__del_orig", v).apply()

    var animatedPreviews: Boolean
        get() = prefs.getBoolean("__anim", true)
        set(v) = prefs.edit().putBoolean("__anim", v).apply()

    var lastUpdate: Long
        get() = prefs.getLong("__last_update", 0L)
        set(v) = prefs.edit().putLong("__last_update", v).apply()
}

/** FrameLayout with a fixed aspect ratio (set per platform). */
class AspectFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var ratioW = 9
    var ratioH = 16

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w * ratioH / ratioW
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
