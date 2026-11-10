package com.hcode91.toksaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var input: TextInputEditText
    private lateinit var fetchBtn: MaterialButton
    private lateinit var status: TextView
    private lateinit var busy: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var toolbar: MaterialToolbar

    private val adapter = VideoAdapter(
        onTap = { v ->
            val st = v.state
            if (st is DlState.Failed && !vm.state.value.downloading) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Download failed")
                    .setMessage(st.reason)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Open in TikTok") { _, _ -> open(v) }
                    .show()
            }
            vm.toggle(v.id)
        },
        onLongPress = { v -> open(v) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        input = findViewById(R.id.userInput)
        fetchBtn = findViewById(R.id.fetchBtn)
        status = findViewById(R.id.status)
        busy = findViewById(R.id.busy)
        fab = findViewById(R.id.downloadFab)

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter
        (grid.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        if (savedInstanceState == null) {
            input.setText(vm.state.value.username)
            handleShare(intent)
        }

        fetchBtn.setOnClickListener {
            if (vm.state.value.fetching) vm.stopFetch() else doFetch()
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doFetch(); true } else false
        }
        fab.setOnClickListener {
            if (vm.state.value.downloading) vm.cancelDownloads() else vm.startDownloads()
        }

        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_select_all -> vm.selectAll()
                R.id.action_select_new -> vm.selectNew()
                R.id.action_clear -> vm.clearSelection()
                R.id.action_update -> vm.updateEngine(nightly = false)
                R.id.action_update_nightly -> vm.updateEngine(nightly = true)
            }
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Lets you share a profile link from TikTok straight into the app. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val user = Regex("tiktok\\.com/@([\\w.-]+)").find(text)?.groupValues?.get(1) ?: return
        input.setText(user)
        vm.fetch(user)
    }

    private fun doFetch() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        vm.fetch(text)
    }

    private fun open(v: Video) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url)))
        } catch (_: Exception) { }
    }

    private fun render(s: UiState) {
        adapter.submitList(s.videos)

        toolbar.subtitle = s.engineVersion?.let { "engine $it" }
        busy.visibility = if (s.busyText != null || s.fetching) View.VISIBLE else View.GONE
        status.text = s.busyText ?: s.status

        fetchBtn.text = if (s.fetching) "Stop" else "Fetch"
        fetchBtn.isEnabled = s.engineReady && s.busyText == null && !s.downloading
        input.isEnabled = !s.fetching && !s.downloading

        val sel = s.selectedCount
        when {
            s.downloading -> {
                fab.visibility = View.VISIBLE
                fab.text = "Cancel"
                fab.isEnabled = true
            }
            sel > 0 && !s.fetching -> {
                fab.visibility = View.VISIBLE
                fab.text = "Download ($sel)"
                fab.isEnabled = true
            }
            else -> fab.visibility = View.GONE
        }

        // Stop the screen sleeping mid-download (Android may pause the app otherwise).
        if (s.downloading || s.fetching) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
