package com.hcode91.toksaver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class InstagramException(message: String) : IOException(message)

/**
 * Talks to Instagram's web API using the session from the in-app sign-in.
 * Handles photos, videos (Reels) and carousel posts.
 */
class InstagramClient(private val context: Context) {

    companion object {
        private const val APP_ID = "936619743392459" // Instagram web app id
        private const val BASE = "https://www.instagram.com"

        fun isSignedIn(): Boolean =
            CookieManager.getInstance().getCookie(BASE)?.contains("sessionid=") == true

        fun signOut() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        /** WebView user agent without the "; wv" marker, so Instagram treats it as a browser. */
        fun userAgent(context: Context): String =
            WebSettings.getDefaultUserAgent(context).replace("; wv)", ")")
    }

    private val ua = userAgent(context)

    private fun cookies() = CookieManager.getInstance().getCookie(BASE).orEmpty()

    private fun csrf() = Regex("csrftoken=([^;]+)").find(cookies())?.groupValues?.get(1).orEmpty()

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Cookie", cookies())
        c.setRequestProperty("X-IG-App-ID", APP_ID)
        c.setRequestProperty("X-CSRFToken", csrf())
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.setRequestProperty("Referer", "$BASE/")
        c.setRequestProperty("Accept", "*/*")
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 429 -> throw InstagramException("Instagram is rate limiting. Wait a few minutes and try again.")
                code in 300..399 || code == 401 || body.contains("\"require_login\"") || body.contains("login_required") ->
                    throw InstagramException("Instagram sign-in needed or expired. Use ⋮ → Instagram sign in.")
                code == 404 -> throw InstagramException("Account not found")
                code !in 200..299 -> throw InstagramException("Instagram error $code")
            }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /** Lists every post of [username], newest first. [onItem] returns false to stop. */
    fun listPosts(username: String, onItem: (Video) -> Boolean) {
        if (!isSignedIn()) throw InstagramException("Sign in to Instagram first (⋮ → Instagram sign in)")
        val profile = getJson("$BASE/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(username, "UTF-8"))
        val user = profile.optJSONObject("data")?.optJSONObject("user")
            ?: throw InstagramException("Account not found")
        val userId = user.getString("id")
        if (user.optBoolean("is_private") && !user.optBoolean("followed_by_viewer")) {
            throw InstagramException("This account is private and you don't follow it")
        }

        var maxId: String? = null
        while (true) {
            val url = "$BASE/api/v1/feed/user/$userId/?count=12" +
                (maxId?.let { "&max_id=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val page = getJson(url)
            val items = page.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val v = parsePost(items.getJSONObject(i)) ?: continue
                if (!onItem(v)) return
            }
            maxId = page.optString("next_max_id").ifBlank { null }
            if (!page.optBoolean("more_available") || maxId == null) break
            Thread.sleep(800) // be gentle, avoids rate limits
        }
    }

    /** Fresh download links for one post (Instagram's links expire after a while). */
    fun refreshPost(id: String): Video? {
        val info = getJson("$BASE/api/v1/media/$id/info/")
        val item = info.optJSONArray("items")?.optJSONObject(0) ?: return null
        return parsePost(item)
    }

    fun parsePost(o: JSONObject): Video? {
        val pk = o.opt("pk")?.toString() ?: o.optString("id").substringBefore('_')
        if (pk.isBlank()) return null
        val code = o.optString("code")
        val media = ArrayList<MediaItem>()
        val carousel = o.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) mediaOf(carousel.getJSONObject(i))?.let { media += it }
        } else {
            mediaOf(o)?.let { media += it }
        }
        if (media.isEmpty()) return null

        val views = when {
            o.has("play_count") -> o.optLong("play_count")
            o.has("view_count") -> o.optLong("view_count")
            else -> -1L
        }
        return Video(
            id = pk,
            url = if (code.isNotBlank()) "$BASE/p/$code/" else BASE,
            title = o.optJSONObject("caption")?.optString("text").orEmpty().take(120),
            thumbnail = gridThumb(o) ?: carousel?.optJSONObject(0)?.let { gridThumb(it) },
            duration = o.optDouble("video_duration", -1.0).toInt().takeIf { it > 0 },
            views = views.takeIf { it >= 0 },
            timestamp = o.optLong("taken_at", -1).takeIf { it > 0 },
            media = media,
            owner = o.optJSONObject("user")?.optString("username")?.ifBlank { null }
                ?: o.optJSONObject("owner")?.optString("username")?.ifBlank { null },
        )
    }

    /**
     * Finds every post inside a chunk of Instagram JSON (API or embedded page data).
     * Handles "for (;;);" prefixes and newline-separated streamed parts.
     */
    fun scanJson(text: String): List<Video> {
        val found = LinkedHashMap<String, Video>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val looksLikePost = x.has("code") &&
                        (x.has("image_versions2") || x.has("video_versions") || x.has("carousel_media"))
                    if (looksLikePost) {
                        parsePost(x)?.let { found.putIfAbsent(it.id, it) }
                        return // don't treat carousel children as separate posts
                    }
                    val keys = x.keys()
                    while (keys.hasNext()) walk(x.opt(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        val start = text.indexOf('{')
        if (start < 0) return emptyList()
        val body = text.substring(start)
        try {
            walk(JSONObject(body))
        } catch (e: Exception) {
            body.split('\n').forEach { line ->
                val t = line.trim()
                if (t.startsWith("{")) try { walk(JSONObject(t)) } catch (_: Exception) { }
            }
        }
        return found.values.toList()
    }

    private fun mediaOf(o: JSONObject): MediaItem? {
        val isVideo = o.optInt("media_type") == 2
        if (isVideo) {
            val url = o.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
            if (!url.isNullOrBlank()) return MediaItem(url, true)
        }
        val url = o.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?.optJSONObject(0)?.optString("url") // first = largest
        return url?.takeIf { it.isNotBlank() }?.let { MediaItem(it, false) }
    }

    /** A mid-size image for the grid (≈ 480px wide) to save data. */
    private fun gridThumb(o: JSONObject): String? {
        val c = o.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        var best: String? = null
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until c.length()) {
            val x = c.optJSONObject(i) ?: continue
            val d = kotlin.math.abs(x.optInt("width") - 480)
            if (d < bestDiff) { bestDiff = d; best = x.optString("url") }
        }
        return best
    }

    /**
     * Downloads one file into [dir]. Returns the file; throws [ExpiredLink] on 403/404/410
     * so the caller can refresh the post and retry.
     */
    fun download(
        item: MediaItem, dir: File, baseName: String, maxBytesPerSec: Long = 0, onProgress: (Int) -> Unit,
    ): File {
        val c = URL(item.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Referer", "$BASE/")
        try {
            val code = c.responseCode
            if (code == 403 || code == 404 || code == 410) throw ExpiredLink()
            if (code == 429) throw InstagramException("429 Too Many Requests")
            if (code !in 200..299) throw InstagramException("Download failed ($code)")
            val type = c.contentType.orEmpty()
            val ext = when {
                "mp4" in type || item.isVideo -> "mp4"
                "png" in type -> "png"
                "webp" in type -> "webp"
                "heic" in type -> "heic"
                else -> "jpg"
            }
            val out = File(dir, "$baseName.$ext")
            val total = c.contentLengthLong
            var done = 0L
            val started = System.currentTimeMillis()
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                        // Speed cap: sleep if we're ahead of the allowed rate.
                        if (maxBytesPerSec > 0) {
                            val allowedMs = done * 1000 / maxBytesPerSec
                            val ahead = allowedMs - (System.currentTimeMillis() - started)
                            if (ahead > 0) Thread.sleep(ahead)
                        }
                    }
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    class ExpiredLink : IOException("Link expired")
}

/** In-app Instagram sign-in. Closes itself once Instagram sets a session. */
class InstagramLoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Instagram sign in"
        val web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = InstagramClient.userAgent(this)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (InstagramClient.isSignedIn()) {
                    CookieManager.getInstance().flush()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        web.loadUrl("https://www.instagram.com/accounts/login/")
    }
}
