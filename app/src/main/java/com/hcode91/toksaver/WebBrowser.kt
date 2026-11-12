package com.hcode91.toksaver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Images and videos spotted while browsing, newest first. Only links are kept, nothing is downloaded. */
object WebCatch {
    private val items = LinkedHashMap<String, Video>()

    @Synchronized
    fun add(v: Video): Boolean {
        if (items.containsKey(v.id)) return false
        items[v.id] = v
        return true
    }

    @Synchronized fun all(): List<Video> = items.values.reversed()
    @Synchronized fun size() = items.size
    @Synchronized fun clear() = items.clear()

    fun idFor(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
}

/** Browser settings, kept between visits. */
class WebPrefs(context: Context) {
    private val p = context.getSharedPreferences("web", Context.MODE_PRIVATE)
    var detecting: Boolean
        get() = p.getBoolean("detect", true)
        set(v) = p.edit().putBoolean("detect", v).apply()
    var minImagePx: Int
        get() = p.getInt("minpx", 300)
        set(v) = p.edit().putInt("minpx", v).apply()
    var lastUrl: String
        get() = p.getString("last", "https://www.google.com/") ?: "https://www.google.com/"
        set(v) = p.edit().putString("last", v).apply()
}

/**
 * General in-app browser. Watches what pages load and lists images (above a minimum size)
 * and videos. Downloading happens later, from the main screen.
 */
class WebBrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private val prefs by lazy { WebPrefs(this) }
    @Volatile private var pageUrl: String? = null

    private val videoExt = Regex("\\.(mp4|webm|mov|m4v|mkv|m3u8|mpd)(\\?|$)", RegexOption.IGNORE_CASE)
    private val imageExt = Regex("\\.(jpe?g|png|webp|gif|avif|bmp)(\\?|$)", RegexOption.IGNORE_CASE)

    /** Finds images (with their real size) and <video> sources on the page every couple of seconds. */
    private val scanJs = """
        (function(){
          if (window.__tsScan) return; window.__tsScan = true;
          var seen = {};
          function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return null; } }
          function scan(){
            var out = [];
            document.querySelectorAll('img').forEach(function(i){
              var u = abs(i.currentSrc || i.src);
              if (!u || u.indexOf('data:')===0 || seen[u]) return;
              if (!i.complete || !i.naturalWidth) return;
              seen[u] = 1;
              out.push({u:u, w:i.naturalWidth, h:i.naturalHeight, v:0});
            });
            document.querySelectorAll('video, video source').forEach(function(v){
              var u = abs(v.currentSrc || v.src);
              if (!u || u.indexOf('blob:')===0 || seen[u]) return;
              seen[u] = 1;
              var poster = v.poster ? abs(v.poster) : '';
              out.push({u:u, w:v.videoWidth||0, h:v.videoHeight||0, v:1, p:poster});
            });
            if (out.length) TokSaverWeb.onFound(JSON.stringify(out), document.title || '', location.href);
          }
          setInterval(scan, 2000);
          window.addEventListener('load', scan);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = ""

        val d = resources.displayMetrics.density
        address = EditText(this).apply {
            setSingleLine()
            hint = "Search or enter address"
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString()); true
                } else false
            }
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = false; max = 100 }
        web = WebView(this)
        fab = ExtendedFloatingActionButton(this).apply {
            setIconResource(R.drawable.ic_download)
            setOnClickListener { setResult(RESULT_OK); finish() }
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins((8 * d).toInt(), 0, (8 * d).toInt(), 0) })
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val root = FrameLayout(this)
        root.addView(column)
        val m = (16 * d).toInt()
        root.addView(fab, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(m, m, m, m * 2) })
        setContentView(root)
        updateCount()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.settings.userAgentString = InstagramClient.userAgent(this)
        web.addJavascriptInterface(Bridge(), "TokSaverWeb")
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.setProgressCompat(p, true)
                progress.visibility = if (p in 1..99) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url
                if (!address.hasFocus()) address.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                prefs.lastUrl = url
                if (!address.hasFocus()) address.setText(url)
                view.evaluateJavascript(scanJs, null)
            }
            // Network side: catches video files/streams that never appear as a <video src>.
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (!prefs.detecting || req.isForMainFrame) return null
                val url = req.url.toString()
                if (url.startsWith("http") && videoExt.containsMatchIn(url)) {
                    found(url, isVideo = true, w = 0, h = 0, poster = null, pageTitle = "", pageUrl = pageUrl)
                }
                return null
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { setResult(RESULT_OK); finish() }
            }
        })

        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: prefs.lastUrl)
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            ' ' !in t && '.' in t -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        web.requestFocus()
        web.loadUrl(url)
    }

    /** Adds one spotted image/video to the list (links only). */
    private fun found(url: String, isVideo: Boolean, w: Int, h: Int, poster: String?, pageTitle: String, pageUrl: String?) {
        if (!prefs.detecting) return
        if (!isVideo && (w < prefs.minImagePx && h < prefs.minImagePx)) return
        val stream = Regex("\\.(m3u8|mpd)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
        val host = (pageUrl?.let { Uri.parse(it).host } ?: Uri.parse(url).host ?: "web").removePrefix("www.")
        val v = Video(
            id = WebCatch.idFor(url),
            url = url,
            title = pageTitle.ifBlank { host },
            thumbnail = if (isVideo) poster?.takeIf { it.startsWith("http") } else url,
            duration = null,
            views = null,
            timestamp = System.currentTimeMillis() / 1000,
            // Streams (m3u8/mpd) go through yt-dlp; plain files are fetched directly.
            media = if (stream) emptyList() else listOf(MediaItem(url, isVideo)),
            owner = host,
            referer = pageUrl,
        )
        if (WebCatch.add(v)) runOnUiThread { updateCount() }
    }

    private fun updateCount() {
        val n = WebCatch.size()
        fab.text = when {
            !prefs.detecting -> "Detection paused"
            n == 0 -> "Nothing found yet"
            else -> "View $n found"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Back")
        menu.add(0, 2, 0, "Forward")
        menu.add(0, 3, 0, "Reload")
        menu.add(0, 4, 0, "Pause / resume detection")
        menu.add(0, 5, 0, "Minimum image size…")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> if (web.canGoBack()) web.goBack()
            2 -> if (web.canGoForward()) web.goForward()
            3 -> web.reload()
            4 -> { prefs.detecting = !prefs.detecting; updateCount() }
            5 -> {
                val sizes = intArrayOf(100, 200, 300, 500, 800, 1200)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Ignore images smaller than")
                    .setSingleChoiceItems(sizes.map { "$it px" }.toTypedArray(), sizes.indexOf(prefs.minImagePx)) { dlg, i ->
                        prefs.minImagePx = sizes[i]; dlg.dismiss()
                    }
                    .show()
            }
        }
        return true
    }

    inner class Bridge {
        @JavascriptInterface
        fun onFound(json: String, pageTitle: String, pageUrl: String) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    found(
                        o.getString("u"), o.optInt("v") == 1, o.optInt("w"), o.optInt("h"),
                        o.optString("p").ifBlank { null }, pageTitle, pageUrl,
                    )
                }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

/** Plain HTTP download for browser finds, using the browser's cookies for that site. */
object WebDownloader {
    fun download(
        context: Context, item: MediaItem, referer: String?, dir: File, baseName: String,
        maxBytesPerSec: Long, onProgress: (Int) -> Unit,
    ): File {
        val c = URL(item.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", InstagramClient.userAgent(context))
        referer?.let { c.setRequestProperty("Referer", it) }
        CookieManager.getInstance().getCookie(item.url)?.let { c.setRequestProperty("Cookie", it) }
        try {
            val code = c.responseCode
            if (code == 429) throw IOException("429 Too Many Requests")
            if (code !in 200..299) throw IOException("Site refused the download ($code) — link may have expired; browse to it again")
            val type = c.contentType.orEmpty().lowercase()
            val urlExt = Regex("\\.([a-z0-9]{2,4})(\\?|$)").find(item.url.lowercase())?.groupValues?.get(1)
            val ext = when {
                "mp4" in type -> "mp4"
                "webm" in type -> "webm"
                "quicktime" in type -> "mov"
                "png" in type -> "png"
                "webp" in type -> "webp"
                "gif" in type -> "gif"
                "avif" in type -> "avif"
                "jpeg" in type || "jpg" in type -> "jpg"
                urlExt != null -> urlExt
                item.isVideo -> "mp4"
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
                        if (maxBytesPerSec > 0) {
                            val ahead = done * 1000 / maxBytesPerSec - (System.currentTimeMillis() - started)
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
}
