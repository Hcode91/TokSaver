package com.hcode91.toksaver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class UiState(
    val engineReady: Boolean = false,
    val engineVersion: String? = null,
    val busyText: String? = null,     // non-null = show spinner + this text
    val status: String = "",
    val username: String = "",
    val videos: List<Video> = emptyList(),
    val fetching: Boolean = false,
    val downloading: Boolean = false,
) {
    val selectedCount get() = videos.count { it.selected }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SavedStore(app)
    private val listCache = ListCache(app)

    var animatedPreviews: Boolean
        get() = store.animatedPreviews
        set(v) { store.animatedPreviews = v }
    private val _state = MutableStateFlow(UiState(username = store.lastUser))
    val state: StateFlow<UiState> = _state

    private val engineLock = Mutex()
    private var fetchJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownloads = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            engineLock.withLock {
                _state.update { it.copy(busyText = "Preparing engine… (first launch takes a few seconds)") }
                try {
                    YoutubeDL.getInstance().init(app)
                    _state.update { it.copy(engineReady = true, engineVersion = YoutubeDL.getInstance().versionName(app)) }
                } catch (e: Exception) {
                    _state.update { it.copy(busyText = null, status = "Engine failed to start: ${e.message}") }
                    return@launch
                }
                // TikTok changes often, so keep yt-dlp fresh (at most once a day).
                if (System.currentTimeMillis() - store.lastUpdate > 24 * 3600_000L) {
                    doUpdate(YoutubeDL.UpdateChannel.STABLE, quiet = true)
                }
                _state.update { it.copy(busyText = null) }
            }
        }
    }

    fun updateEngine(nightly: Boolean) {
        if (_state.value.fetching || _state.value.downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            engineLock.withLock {
                doUpdate(
                    if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE,
                    quiet = false,
                )
                _state.update { it.copy(busyText = null) }
            }
        }
    }

    private fun doUpdate(channel: YoutubeDL.UpdateChannel, quiet: Boolean) {
        val app = getApplication<Application>()
        _state.update { it.copy(busyText = "Updating engine…") }
        try {
            val result = YoutubeDL.getInstance().updateYoutubeDL(app, channel)
            store.lastUpdate = System.currentTimeMillis()
            val ver = YoutubeDL.getInstance().versionName(app)
            _state.update {
                it.copy(
                    engineVersion = ver,
                    status = if (quiet) it.status else when (result) {
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Engine already up to date ($ver)"
                        else -> "Engine updated to $ver"
                    },
                )
            }
        } catch (e: Exception) {
            if (!quiet) _state.update { it.copy(status = "Update failed: ${e.message}") }
        }
    }

    /** Accepts "name", "@name" or a full profile / share link. */
    private fun cleanUsername(raw: String): String {
        val s = raw.trim()
        Regex("tiktok\\.com/@([\\w.-]+)").find(s)?.let { return it.groupValues[1] }
        return s.removePrefix("@").substringBefore('/').substringBefore('?').trim()
    }

    /**
     * Shows the cached list (if any) instantly, then checks TikTok.
     * Normal refresh stops once it reaches videos we already have; [full] reloads everything.
     */
    fun fetch(raw: String, full: Boolean = false) {
        val user = cleanUsername(raw)
        if (user.isBlank() || _state.value.downloading) return
        fetchJob?.let { if (it.isActive) return }
        store.lastUser = user

        val cached = if (full) null else listCache.load(user)
        val cachedVideos = cached?.videos?.map { it.copy(saved = store.isSaved(it.id)) }.orEmpty()
        val incremental = cachedVideos.isNotEmpty()
        // Only stop at known videos if the cached list was a full one.
        val cacheComplete = cached?.complete == true
        _state.update {
            it.copy(
                username = user,
                videos = cachedVideos,
                fetching = true,
                status = if (incremental) "${cachedVideos.size} cached videos · checking for new ones…"
                else "Looking up @$user…",
            )
        }

        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            engineLock.withLock { } // wait for init/update to finish
            if (!_state.value.engineReady) {
                _state.update { it.copy(fetching = false) }
                return@launch
            }
            val knownIds = cachedVideos.map { it.id }.toHashSet()
            val fetched = LinkedHashMap<String, Video>()
            var newCount = 0
            var knownInARow = 0
            var reachedKnown = false

            // Rebuild the list: fetched videos first (newest), then cached ones not re-seen.
            fun publish(status: String) {
                val rest = cachedVideos.filter { it.id !in fetched }
                _state.update { st ->
                    val prev = st.videos.associateBy { it.id }
                    val merged = (fetched.values + rest).map { v ->
                        prev[v.id]?.let { p -> v.copy(selected = p.selected, saved = p.saved, state = p.state) } ?: v
                    }
                    st.copy(videos = merged, status = status)
                }
            }

            val req = YoutubeDLRequest("https://www.tiktok.com/@$user").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--ignore-errors")
            }
            try {
                YoutubeDL.getInstance().execute(req, FETCH_ID) { _, _, line ->
                    if (!line.startsWith("{") || reachedKnown) return@execute
                    val v = Video.fromJson(line, user) ?: return@execute
                    if (fetched.containsKey(v.id)) return@execute
                    fetched[v.id] = v.copy(saved = store.isSaved(v.id))
                    if (v.id in knownIds) knownInARow++ else { newCount++; knownInARow = 0 }
                    publish(
                        if (incremental) "Checking… $newCount new"
                        else "Loading… ${fetched.size} videos found"
                    )
                    // Enough already-known videos in a row (beats pinned posts) = caught up.
                    if (incremental && cacheComplete && knownInARow >= 12) {
                        reachedKnown = true
                        YoutubeDL.getInstance().destroyProcessById(FETCH_ID)
                    }
                }
                finishFetch(
                    user,
                    if (fetched.isEmpty() && !incremental) "No videos found for @$user" else null,
                    newCount, incremental,
                    complete = if (reachedKnown) cacheComplete else true,
                )
            } catch (e: YoutubeDL.CanceledException) {
                finishFetch(
                    user, if (reachedKnown) null else "Stopped", newCount, incremental,
                    complete = reachedKnown && cacheComplete,
                )
            } catch (e: Exception) {
                val msg = friendlyError(e.message)
                finishFetch(
                    user,
                    if (_state.value.videos.isEmpty()) "Couldn't load @$user: $msg" else "Stopped early: $msg",
                    newCount, incremental, complete = false,
                )
            }
        }
    }

    private fun finishFetch(
        user: String, problem: String?, newCount: Int, incremental: Boolean, complete: Boolean,
    ) {
        val videos = _state.value.videos
        listCache.save(user, videos, complete)
        _state.update {
            it.copy(
                fetching = false,
                status = buildString {
                    append("@$user · ${videos.size} videos")
                    if (incremental) append(" · $newCount new")
                    if (problem != null) append(" · $problem")
                    else append(" · tap to select, long-press to open")
                },
            )
        }
    }

    fun stopFetch() {
        YoutubeDL.getInstance().destroyProcessById(FETCH_ID)
    }

    fun toggle(id: String) = mutate { if (it.id == id) it.copy(selected = !it.selected) else it }
    fun selectAll() = mutate { it.copy(selected = true) }
    fun selectNew() = mutate { it.copy(selected = !it.saved) }
    fun clearSelection() = mutate { it.copy(selected = false) }

    private fun mutate(f: (Video) -> Video) {
        if (_state.value.downloading) return
        _state.update { s -> s.copy(videos = s.videos.map(f)) }
    }

    private fun setVideo(id: String, f: (Video) -> Video) {
        _state.update { s -> s.copy(videos = s.videos.map { if (it.id == id) f(it) else it }) }
    }

    var lastFrameMode: FrameMode
        get() = store.frameMode
        set(v) { store.frameMode = v }

    var lastPersonFilter: PersonFilter
        get() = store.personFilter
        set(v) { store.personFilter = v }

    fun startDownloads(frameMode: FrameMode, personFilter: PersonFilter) {
        val s = _state.value
        if (s.downloading || s.fetching) return
        val queue = s.videos.filter { it.selected }
        if (queue.isEmpty()) return
        lastFrameMode = frameMode
        lastPersonFilter = personFilter
        val user = s.username
        cancelDownloads = false
        queue.forEach { q -> setVideo(q.id) { it.copy(state = DlState.Queued) } }
        _state.update { it.copy(downloading = true) }

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val workDir = File(app.cacheDir, "dl")
            var ok = 0
            var failed = 0
            var framesSaved = 0
            var detectorError: String? = null
            val detector = if (frameMode != FrameMode.NONE && personFilter.enabled) {
                try {
                    PersonDetector(app, personFilter)
                } catch (e: Throwable) {
                    detectorError = e.message ?: "person detector failed to load"
                    null
                }
            } else null
            for ((i, v) in queue.withIndex()) {
                if (cancelDownloads) break
                _state.update { it.copy(status = "Downloading ${i + 1} of ${queue.size}…") }
                setVideo(v.id) { it.copy(state = DlState.Running(0)) }
                workDir.deleteRecursively()
                workDir.mkdirs()
                try {
                    val req = YoutubeDLRequest(v.url).apply {
                        // H.264 plays everywhere; fall back to whatever is best.
                        addOption("-f", "b[vcodec=h264]/b[vcodec^=avc]/b")
                        addOption("-o", "${workDir.absolutePath}/%(id)s.%(ext)s")
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("--no-part")
                    }
                    YoutubeDL.getInstance().execute(req, DL_ID) { p, _, _ ->
                        val pct = p.toInt().coerceIn(0, 100)
                        setVideo(v.id) {
                            val cur = it.state
                            if (cur is DlState.Running && cur.percent == pct) it
                            else it.copy(state = DlState.Running(pct))
                        }
                    }
                    val file = workDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.length() }
                        ?: throw IllegalStateException("No file produced")
                    if (!MediaSaver.isVideo(file)) throw IllegalStateException("Photo post (not supported)")
                    MediaSaver.saveToMovies(app, file, user, v)
                    store.markSaved(v.id)
                    if (frameMode != FrameMode.NONE) {
                        setVideo(v.id) { it.copy(state = DlState.Running(0, frames = true)) }
                        var lastPct = -1
                        framesSaved += FrameExtractor.extract(
                            app, file, user, v.id, frameMode,
                            cancelled = { cancelDownloads },
                            keep = detector?.let { it::hasPerson },
                        ) { pct ->
                            if (pct != lastPct) {
                                lastPct = pct
                                setVideo(v.id) { it.copy(state = DlState.Running(pct, frames = true)) }
                            }
                        }
                    }
                    setVideo(v.id) { it.copy(state = DlState.Done, saved = true, selected = false) }
                    ok++
                } catch (e: YoutubeDL.CanceledException) {
                    setVideo(v.id) { it.copy(state = DlState.Idle) }
                    break
                } catch (e: Exception) {
                    setVideo(v.id) { it.copy(state = DlState.Failed(friendlyError(e.message))) }
                    failed++
                }
            }
            workDir.deleteRecursively()
            detector?.close()
            // Anything still queued after a cancel goes back to idle.
            _state.update { st ->
                st.copy(
                    downloading = false,
                    videos = st.videos.map { if (it.state is DlState.Queued) it.copy(state = DlState.Idle) else it },
                    status = buildString {
                        append("Saved $ok to Movies/TikTok/$user")
                        if (framesSaved > 0) append(" · $framesSaved frames to Pictures/TikTok/$user")
                        if (detector != null) append(" (people only)")
                        detectorError?.let { append(" · person filter off: $it") }
                        if (failed > 0) append(" · $failed failed")
                        if (cancelDownloads) append(" · cancelled")
                    },
                )
            }
        }
    }

    fun cancelDownloads() {
        cancelDownloads = true
        YoutubeDL.getInstance().destroyProcessById(DL_ID)
    }

    private fun friendlyError(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown error"
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: raw
        return err.removePrefix("ERROR:").replace(Regex("^\\[[^]]+]\\s*"), "").trim().take(220)
    }

    override fun onCleared() {
        stopFetch()
        cancelDownloads()
    }

    companion object {
        private const val FETCH_ID = "fetch"
        private const val DL_ID = "dl"
    }
}
