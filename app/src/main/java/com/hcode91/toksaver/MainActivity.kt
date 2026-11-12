package com.hcode91.toksaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
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
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var input: TextInputEditText
    private lateinit var fetchBtn: MaterialButton
    private lateinit var status: TextView
    private lateinit var busy: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var grid: RecyclerView
    private lateinit var userLayout: TextInputLayout
    private var shownPlatform: Platform? = null

    // After signing in to Instagram, carry on with the fetch.
    private val igLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (InstagramClient.isSignedIn()) {
            status.text = "Signed in to Instagram"
            if (vm.state.value.platform == Platform.INSTAGRAM && !input.text.isNullOrBlank()) doFetch()
        }
    }

    private val imageLoader by lazy {
        ImageLoader.Builder(this)
            .components { add(ImageDecoderDecoder.Factory()) } // animated webp previews
            .crossfade(true)
            .build()
    }

    private val adapter by lazy { VideoAdapter(
        imageLoader = imageLoader,
        animated = { vm.animatedPreviews },
        onTap = { v ->
            val st = v.state
            if (st is DlState.Failed && !vm.state.value.downloading) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Download failed")
                    .setMessage(st.reason)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Open original") { _, _ -> open(v) }
                    .show()
            }
            vm.toggle(v.id)
        },
        onLongPress = { v -> open(v) },
    ) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        input = findViewById(R.id.userInput)
        fetchBtn = findViewById(R.id.fetchBtn)
        status = findViewById(R.id.status)
        busy = findViewById(R.id.busy)
        fab = findViewById(R.id.downloadFab)

        userLayout = findViewById(R.id.userLayout)
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 3)

        val chips = findViewById<ChipGroup>(R.id.platforms)
        Platform.entries.forEach { p ->
            chips.addView(Chip(this).apply {
                id = 2000 + p.ordinal
                text = p.label
                isCheckable = true
                isChecked = p == vm.state.value.platform
            })
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            val p = Platform.entries.getOrNull((ids.firstOrNull() ?: return@setOnCheckedStateChangeListener) - 2000)
                ?: return@setOnCheckedStateChangeListener
            val st = vm.state.value
            if (st.fetching || st.downloading) {
                group.check(2000 + st.platform.ordinal) // don't switch mid-job
                return@setOnCheckedStateChangeListener
            }
            vm.setPlatform(p)
            input.setText(vm.state.value.username)
        }
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
            if (vm.state.value.downloading) vm.cancelDownloads() else askFrameMode()
        }

        toolbar.inflateMenu(R.menu.main)
        toolbar.menu.findItem(R.id.action_animated).isChecked = vm.animatedPreviews
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_full_reload -> {
                    val u = vm.state.value.username.ifBlank { input.text?.toString().orEmpty() }
                    if (u.isNotBlank()) vm.fetch(u, full = true)
                }
                R.id.action_animated -> {
                    vm.animatedPreviews = !vm.animatedPreviews
                    it.isChecked = vm.animatedPreviews
                    adapter.reloadThumbs()
                }
                R.id.action_ig_login -> igLogin.launch(Intent(this, InstagramLoginActivity::class.java))
                R.id.action_ig_logout -> {
                    InstagramClient.signOut()
                    status.text = "Signed out of Instagram"
                }
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

    /** Lets you share a profile link from TikTok / YouTube / Instagram straight into the app. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val p = Platform.fromLink(text) ?: return
        val user = p.cleanUser(text).takeIf { it.isNotBlank() } ?: return
        if (vm.state.value.fetching || vm.state.value.downloading) return
        vm.setPlatform(p)
        findViewById<ChipGroup>(R.id.platforms).check(2000 + p.ordinal)
        input.setText(user)
        doFetch()
    }

    private fun doFetch() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        if (vm.state.value.platform == Platform.INSTAGRAM && !InstagramClient.isSignedIn()) {
            status.text = "Sign in to Instagram to continue"
            igLogin.launch(Intent(this, InstagramLoginActivity::class.java))
            return
        }
        vm.fetch(text)
    }

    /** Before downloading, ask whether to also cut each video into images. */
    private fun askFrameMode() {
        val modes = FrameMode.entries
        val n = vm.state.value.selectedCount
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val group = view.findViewById<RadioGroup>(R.id.modeGroup)
        val people = view.findViewById<MaterialCheckBox>(R.id.peopleOnly)
        val peopleOpts = view.findViewById<View>(R.id.peopleOptions)
        val confLabel = view.findViewById<TextView>(R.id.confLabel)
        val confSlider = view.findViewById<Slider>(R.id.confSlider)
        val areaLabel = view.findViewById<TextView>(R.id.areaLabel)
        val areaSlider = view.findViewById<Slider>(R.id.areaSlider)
        val gifBox = view.findViewById<MaterialCheckBox>(R.id.gifs)
        val gifOpts = view.findViewById<View>(R.id.gifOptions)
        val slowLabel = view.findViewById<TextView>(R.id.slowLabel)
        val slowSlider = view.findViewById<Slider>(R.id.slowSlider)
        val deleteBox = view.findViewById<MaterialCheckBox>(R.id.deleteOriginal)

        modes.forEachIndexed { i, m ->
            group.addView(RadioButton(this).apply { id = 1000 + i; text = m.label })
        }
        val pf = vm.lastPersonFilter
        group.check(1000 + modes.indexOf(vm.lastFrameMode).coerceAtLeast(0))
        people.isChecked = pf.enabled
        confSlider.value = (pf.minConfidence * 100).toInt().coerceIn(20, 95).let { it - it % 5 }.toFloat()
        areaSlider.value = pf.minAreaPercent.coerceIn(0, 50).toFloat()
        val go = vm.lastGifOptions
        gifBox.isChecked = go.enabled
        slowSlider.value = go.slowFactor.coerceIn(1, 4).toFloat()
        deleteBox.isChecked = vm.lastDeleteOriginal

        fun refresh() {
            val framesOn = group.checkedRadioButtonId != 1000 + modes.indexOf(FrameMode.NONE)
            people.isEnabled = framesOn
            peopleOpts.visibility = if (framesOn && people.isChecked) View.VISIBLE else View.GONE
            confLabel.text = "Detection confidence: ${confSlider.value.toInt()}%"
            areaLabel.text = "Person must fill at least ${areaSlider.value.toInt()}% of the frame"
            gifOpts.visibility = if (gifBox.isChecked) View.VISIBLE else View.GONE
            val slow = slowSlider.value.toInt()
            slowLabel.text = if (slow == 1) "Speed: normal (each GIF lasts 3s)"
            else "Speed: 1/$slow (each GIF lasts ${3 * slow}s)"
            // Only offer deleting the video when something else is being made from it.
            deleteBox.isEnabled = framesOn || gifBox.isChecked
        }
        group.setOnCheckedChangeListener { _, _ -> refresh() }
        people.setOnCheckedChangeListener { _, _ -> refresh() }
        confSlider.addOnChangeListener { _, _, _ -> refresh() }
        areaSlider.addOnChangeListener { _, _, _ -> refresh() }
        gifBox.setOnCheckedChangeListener { _, _ -> refresh() }
        slowSlider.addOnChangeListener { _, _, _ -> refresh() }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle("Download $n video${if (n == 1) "" else "s"}")
            .setView(view)
            .setPositiveButton("Download") { _, _ ->
                val mode = modes.getOrElse(group.checkedRadioButtonId - 1000) { FrameMode.NONE }
                vm.startDownloads(
                    mode,
                    PersonFilter(
                        enabled = people.isChecked,
                        minConfidence = confSlider.value / 100f,
                        minAreaPercent = areaSlider.value.toInt(),
                    ),
                    GifOptions(enabled = gifBox.isChecked, slowFactor = slowSlider.value.toInt()),
                    deleteOriginal = deleteBox.isChecked && deleteBox.isEnabled,
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(v: Video) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url)))
        } catch (_: Exception) { }
    }

    private fun render(s: UiState) {
        if (shownPlatform != s.platform) {
            shownPlatform = s.platform
            (grid.layoutManager as GridLayoutManager).spanCount = s.platform.columns
            adapter.platform = s.platform
            userLayout.hint = s.platform.hint
        }
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
