package com.hcode91.toksaver

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/** Posts picked up while browsing Instagram, newest-seen first. Shared with the main screen. */
object BrowserCatch {
    private val posts = LinkedHashMap<String, Video>()

    @Synchronized
    fun add(list: List<Video>): Int {
        var added = 0
        for (v in list) {
            if (!posts.containsKey(v.id)) added++
            posts[v.id] = v // newer copy has fresher links
        }
        return added
    }

    @Synchronized fun all(): List<Video> = posts.values.reversed()
    @Synchronized fun size() = posts.size
    @Synchronized fun clear() = posts.clear()
    @Synchronized fun remove(ids: Set<String>) { ids.forEach { posts.remove(it) } }
}

/**
 * In-app Instagram browser. A small script watches the data Instagram's own page loads
 * while you scroll and hands any posts it finds to the app.
 */
class InstagramBrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var fab: ExtendedFloatingActionButton
    private val ig by lazy { InstagramClient(this) }

    private val hookJs = """
        (function(){
          if (window.__tsHooked) return; window.__tsHooked = true;
          function want(u){ u = String(u||''); return u.indexOf('/api/')>=0 || u.indexOf('graphql')>=0 || u.indexOf('/ajax/')>=0; }
          function send(t){
            try { if (t && (t.indexOf('image_versions2')>=0 || t.indexOf('video_versions')>=0)) TokSaver.onJson(t); } catch(e){}
          }
          var of = window.fetch;
          window.fetch = function(input, init){
            var u = (input && input.url) ? input.url : input;
            return of.apply(this, arguments).then(function(r){
              try { if (want(u)) r.clone().text().then(send).catch(function(){}); } catch(e){}
              return r;
            });
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__tsUrl = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(){
            var x = this;
            if (want(x.__tsUrl)) x.addEventListener('load', function(){
              try { if (x.responseType === '' || x.responseType === 'text') send(x.responseText); } catch(e){}
            });
            return os.apply(this, arguments);
          };
          function scan(){
            document.querySelectorAll('script[type="application/json"]').forEach(function(s){
              if (!s.__ts) { s.__ts = 1; send(s.textContent); }
            });
          }
          document.addEventListener('DOMContentLoaded', scan);
          setInterval(scan, 2500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Instagram"

        web = WebView(this)
        fab = ExtendedFloatingActionButton(this).apply {
            setIconResource(R.drawable.ic_download)
            setOnClickListener { setResult(RESULT_OK); finish() }
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val m = (16 * resources.displayMetrics.density).toInt()
        root.addView(fab, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(m, m, m, m * 5) })
        setContentView(root)
        updateCount()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.settings.userAgentString = InstagramClient.userAgent(this)
        web.addJavascriptInterface(Bridge(), "TokSaver")

        // Inject before Instagram's own scripts run, so every request is seen.
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, hookJs, setOf("https://www.instagram.com"))
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!early) view.evaluateJavascript(hookJs, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(hookJs, null) // no-op if already hooked
                CookieManager.getInstance().flush()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { setResult(RESULT_OK); finish() }
            }
        })

        val start = intent.getStringExtra(EXTRA_URL) ?: "https://www.instagram.com/"
        web.loadUrl(start)
    }

    private fun updateCount() {
        val n = BrowserCatch.size()
        fab.text = if (n == 0) "Scroll to detect posts" else "View $n detected"
        fab.isEnabled = n > 0
    }

    inner class Bridge {
        @JavascriptInterface
        fun onJson(text: String) {
            val found = try { ig.scanJson(text) } catch (_: Exception) { emptyList() }
            if (found.isEmpty()) return
            if (BrowserCatch.add(found) > 0) runOnUiThread { updateCount() }
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
