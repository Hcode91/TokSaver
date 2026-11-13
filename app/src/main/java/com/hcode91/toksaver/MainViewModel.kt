package com.hcode91.toksaver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
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
    val platform: Platform = Platform.TIKTOK,
    val username: String = "",
    val videos: List<Video> = emptyList(),
    val fetching: Boolean = false,
    val downloading: Boolean = false,
    val savedAccounts: List<ListCache.Saved> = emptyList(),
    val ownerFilter: String? = null,   // Instagram account / website shown (browsed tabs)
    val hiddenKeys: Set<String> = emptySet(),
    val showHidden: Boolean = false,   // true = grid shows only the hidden items
) {
    val selectedCount get() = videos.count { it.selected }

    fun isHidden(v: Video) = platform.key(v.id) in hiddenKeys

    /** Items in the current list that are hidden. */
    val hiddenCount get() = videos.count { isHidden(it) }

    private val notHidden: List<Video>
        get() = videos.filter { isHidden(it) == showHidden }

    /** What the grid shows: everything, or just one account / website. */
    val visibleVideos: List<Video>
        get() = notHidden.let { list ->
            if (ownerFilter == null) list else list.filter { (it.owner ?: "") == ownerFilter }
        }

    /** Accounts / websites in the browsed list, biggest first. */
    val owners: List<Pair<String, Int>>
        get() = notHidden.groupingBy { it.owner ?: "" }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SavedStore(app)
    private val listCache = ListCache(app)

    var animatedPreviews: Boolean
        get() = store.animatedPreviews
        set(v) { store.animatedPreviews = v }
    private val ig = InstagramClient(app)
    private val _state = MutableStateFlow(
        UiState(
            platform = store.platform,
            username = store.lastUser(store.platform),
            hiddenKeys = store.hiddenKeys(),
        )
    )
    val state: StateFlow<UiState> = _state

    private val engineLock = Mutex()
    private var fetchJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownloads = false
    @Volatile private var stopFetchFlag = false

    init {
        // Reopen where you left off: saved list for the last account (or the browsed list).
        val p0 = _state.value.platform
        if (p0.browsed) loadBrowsed() else showSaved(store.lastUser(p0))
        refreshSaved()
        viewModelScope.launch(Dispatchers.IO) {
            engineLock.withLock {
                _state.update { it.copy(busyText = "Preparing engine… (first launch takes a few seconds)") }
                try {
                    YoutubeDL.getInstance().init(app)
                    // ffmpeg merges YouTube's separate video + audio streams.
                    try { FFmpeg.getInstance().init(app) } catch (_: Exception) { }
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

    /** Switch platform; shows that platform's last username. */
    fun setPlatform(p: Platform) {
        val st = _state.value
        if (st.platform == p || st.fetching || st.downloading) return
        store.platform = p
        _state.update {
            it.copy(platform = p, username = store.lastUser(p), videos = emptyList(), status = "", ownerFilter = null, showHidden = false)
        }
        if (p.browsed) loadBrowsed() else showSaved(store.lastUser(p))
        refreshSaved()
    }

    private fun refreshSaved() {
        val p = _state.value.platform
        val list = if (p.browsed) emptyList() else listCache.savedUsers(p)
        _state.update { it.copy(savedAccounts = list) }
    }

    /** Shows a saved account's list straight from the phone, without going online. */
    fun showSaved(raw: String) {
        val st = _state.value
        if (st.fetching || st.downloading || st.platform.browsed) return
        val p = st.platform
        val user = p.cleanUser(raw)
        if (user.isBlank()) return
        val e = listCache.load(p, user) ?: return
        store.setLastUser(p, user)
        val date = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.UK).format(java.util.Date(e.savedAt))
        _state.update {
            it.copy(
                username = user,
                videos = e.videos.map { v -> v.copy(saved = store.isSaved(p.key(v.id))) },
                status = "@$user · ${e.videos.size} saved · last checked $date · Fetch to check for new",
            )
        }
    }

    /** Forgets a saved account list (doesn't touch anything already downloaded). */
    fun forgetSaved(user: String) {
        val st = _state.value
        if (st.fetching || st.downloading) return
        listCache.clear(st.platform, user)
        if (st.username.equals(user, ignoreCase = true)) {
            _state.update { it.copy(videos = emptyList(), status = "Removed @$user") }
        }
        refreshSaved()
    }

    /** Shows what the in-app browser picked up (Instagram posts or general web finds), kept between visits. */
    fun loadBrowsed() {
        if (_state.value.downloading || _state.value.fetching) return
        val p = _state.value.platform
        if (!p.browsed) return
        val earlier = listCache.load(p, BROWSER_USER)?.videos.orEmpty()
        val fresh = if (p == Platform.INSTAGRAM) BrowserCatch.all() else WebCatch.all()
        val freshIds = fresh.map { it.id }.toHashSet()
        val prev = _state.value.videos.associateBy { it.id }
        val merged = (fresh + earlier.filter { it.id !in freshIds }).map { v ->
            val old = prev[v.id]
            v.copy(
                saved = store.isSaved(p.key(v.id)),
                selected = old?.selected ?: false,
                state = old?.state ?: DlState.Idle,
            )
        }
        listCache.save(p, BROWSER_USER, merged, complete = false)
        val what = if (p == Platform.INSTAGRAM) "Instagram posts" else "images & videos found"
        _state.update {
            it.copy(
                username = BROWSER_USER,
                videos = merged,
                status = if (merged.isEmpty()) "Tap Browse and scroll: things you see get listed here"
                else "${merged.size} $what · tap to select, long-press to open",
            )
        }
    }

    /** Clears the browsed list, or only the account / website currently filtered to. */
    fun clearBrowsed() {
        val st = _state.value
        val p = st.platform
        if (st.downloading || !p.browsed) return
        val only = st.ownerFilter
        if (only == null) {
            if (p == Platform.INSTAGRAM) BrowserCatch.clear() else WebCatch.clear()
            listCache.clear(p, BROWSER_USER)
            _state.update { it.copy(videos = emptyList(), status = "Cleared the list") }
            return
        }
        val gone = st.videos.filter { (it.owner ?: "") == only }.map { it.id }.toHashSet()
        if (p == Platform.INSTAGRAM) BrowserCatch.remove(gone) else WebCatch.remove(gone)
        val left = st.videos.filter { it.id !in gone }
        if (left.isEmpty()) listCache.clear(p, BROWSER_USER) else listCache.save(p, BROWSER_USER, left, false)
        _state.update { it.copy(videos = left, ownerFilter = null, status = "Removed ${gone.size} from ${label(p, only)}") }
    }

    fun setOwnerFilter(owner: String?) {
        if (_state.value.downloading) return
        _state.update {
            val shown = if (owner == null) it.videos.size else it.videos.count { v -> (v.owner ?: "") == owner }
            it.copy(
                ownerFilter = owner,
                status = if (owner == null) "${it.videos.size} in total · tap to select, long-press to open"
                else "${label(it.platform, owner)} · $shown · tap to select, long-press to open",
            )
        }
    }

    private fun label(p: Platform, owner: String) =
        if (owner.isBlank()) "unknown" else if (p == Platform.INSTAGRAM) "@$owner" else owner

    /**
     * Shows the cached list (if any) instantly, then checks TikTok.
     * Normal refresh stops once it reaches videos we already have; [full] reloads everything.
     */
    fun fetch(raw: String, full: Boolean = false) {
        val platform = _state.value.platform
        val user = platform.cleanUser(raw)
        if (user.isBlank() || _state.value.downloading) return
        fetchJob?.let { if (it.isActive) return }
        store.setLastUser(platform, user)
        stopFetchFlag = false

        val cached = if (full) null else listCache.load(platform, user)
        val cachedVideos = cached?.videos?.map { it.copy(saved = store.isSaved(platform.key(it.id))) }.orEmpty()
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
            if (!_state.value.engineReady && platform != Platform.INSTAGRAM) {
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

            // Handles one listed item; returns false once we've caught up with the cache.
            fun onItem(v: Video): Boolean {
                if (reachedKnown) return false
                if (fetched.containsKey(v.id)) return true
                fetched[v.id] = v.copy(saved = store.isSaved(platform.key(v.id)))
                if (v.id in knownIds) knownInARow++ else { newCount++; knownInARow = 0 }
                publish(
                    if (incremental) "Checking… $newCount new"
                    else "Loading… ${fetched.size} found"
                )
                // Enough already-known posts in a row (beats pinned posts) = caught up.
                if (incremental && cacheComplete && knownInARow >= 12) reachedKnown = true
                return !reachedKnown
            }

            try {
                if (platform == Platform.INSTAGRAM) {
                    ig.listPosts(user) { v -> !stopFetchFlag && onItem(v) }
                    if (stopFetchFlag && !reachedKnown) throw YoutubeDL.CanceledException()
                } else {
                    val req = YoutubeDLRequest(platform.listUrl(user)).apply {
                        addOption("--flat-playlist")
                        addOption("--dump-json")
                        addOption("--ignore-errors")
                    }
                    YoutubeDL.getInstance().execute(req, FETCH_ID) { _, _, line ->
                        if (!line.startsWith("{") || reachedKnown) return@execute
                        val v = Video.fromJson(line, user, platform) ?: return@execute
                        if (!onItem(v)) YoutubeDL.getInstance().destroyProcessById(FETCH_ID)
                    }
                }
                finishFetch(
                    user,
                    if (fetched.isEmpty() && !incremental) "Nothing found for @$user" else null,
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
        listCache.save(_state.value.platform, user, videos, complete)
        refreshSaved()
        _state.update {
            it.copy(
                fetching = false,
                status = buildString {
                    append("@$user · ${videos.size} posts")
                    if (incremental) append(" · $newCount new")
                    if (problem != null) append(" · $problem")
                    else append(" · tap to select, long-press to open")
                },
            )
        }
    }

    fun stopFetch() {
        stopFetchFlag = true
        YoutubeDL.getInstance().destroyProcessById(FETCH_ID)
    }

    fun toggle(id: String) = mutate { if (it.id == id) it.copy(selected = !it.selected) else it }
    // Select actions only touch what's on screen (respecting the account / website filter).
    fun selectAll() = mutate { if (isVisible(it)) it.copy(selected = true) else it }
    fun selectNew() = mutate { if (isVisible(it)) it.copy(selected = !it.saved) else it }

    private fun isVisible(v: Video): Boolean {
        val st = _state.value
        if (st.isHidden(v) != st.showHidden) return false
        val f = st.ownerFilter ?: return true
        return (v.owner ?: "") == f
    }

    /** Hides the selected items for good (or un-hides them when viewing hidden items). */
    fun hideSelected() {
        val st = _state.value
        if (st.downloading) return
        val keys = st.videos.filter { it.selected && isVisible(it) }.map { st.platform.key(it.id) }
        if (keys.isEmpty()) return
        val hide = !st.showHidden
        store.setHidden(keys, hide)
        _state.update { s ->
            s.copy(
                hiddenKeys = store.hiddenKeys(),
                videos = s.videos.map { if (it.selected) it.copy(selected = false) else it },
                status = if (hide) "Hid ${keys.size} · ⋮ → Show hidden to undo" else "Un-hid ${keys.size}",
            )
        }
    }

    fun toggleShowHidden() {
        if (_state.value.downloading) return
        _state.update { s ->
            val on = !s.showHidden
            s.copy(
                showHidden = on,
                ownerFilter = null,
                videos = s.videos.map { it.copy(selected = false) },
                status = if (on) "Showing ${s.hiddenCount} hidden · select and use Unhide to bring back"
                else "${s.videos.size - s.hiddenCount} shown · ${s.hiddenCount} hidden",
            )
        }
    }
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

    var lastGifOptions: GifOptions
        get() = store.gifOptions
        set(v) { store.gifOptions = v }

    var lastDeleteOriginal: Boolean
        get() = store.deleteOriginal
        set(v) { store.deleteOriginal = v }

    fun startDownloads(
        frameMode: FrameMode,
        personFilter: PersonFilter,
        gifs: GifOptions,
        deleteOriginal: Boolean,
    ) {
        val s = _state.value
        if (s.downloading || s.fetching) return
        val queue = s.videos.filter { it.selected }
        if (queue.isEmpty()) return
        lastFrameMode = frameMode
        lastPersonFilter = personFilter
        lastGifOptions = gifs
        lastDeleteOriginal = deleteOriginal
        val pace = store.pace
        val processing = frameMode != FrameMode.NONE || gifs.enabled
        val dropVideo = processing && deleteOriginal
        val user = s.username
        val platform = s.platform
        val shownUser = if (user == BROWSER_USER) "<account>" else user
        cancelDownloads = false
        queue.forEach { q -> setVideo(q.id) { it.copy(state = DlState.Queued) } }
        _state.update { it.copy(downloading = true) }

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val workDir = File(app.cacheDir, "dl")
            var ok = 0
            var failed = 0
            var framesSaved = 0
            var gifsSaved = 0
            var videosDropped = 0
            var photosOrVideos = 0
            var photos = 0
            var detectorError: String? = null
            val detector = if (frameMode != FrameMode.NONE && personFilter.enabled) {
                try {
                    PersonDetector(app, personFilter)
                } catch (e: Throwable) {
                    detectorError = e.message ?: "person detector failed to load"
                    null
                }
            } else null
            queue@ for ((i, v) in queue.withIndex()) {
                if (cancelDownloads) break
                // Pause between downloads so we don't hammer the servers.
                if (i > 0 && !waitFor(pace.pauseMs(), "Pausing before next download")) break
                var attempt = 0
                retry@ while (true) {
                _state.update { it.copy(status = "Downloading ${i + 1} of ${queue.size}…") }
                setVideo(v.id) { it.copy(state = DlState.Running(0)) }
                workDir.deleteRecursively()
                workDir.mkdirs()
                try {
                    // Pull out frames from a saved video, if asked for.
                    // Frames and/or GIFs from a downloaded video, if asked for.
                    fun process(file: File, procId: String, who: String) {
                        if (frameMode != FrameMode.NONE) {
                            setVideo(v.id) { it.copy(state = DlState.Running(0, frames = true)) }
                            var lastPct = -1
                            framesSaved += FrameExtractor.extract(
                                app, file, platform.folder, who, procId, frameMode,
                                cancelled = { cancelDownloads },
                                keep = detector?.let { it::hasPerson },
                            ) { pct ->
                                if (pct != lastPct) {
                                    lastPct = pct
                                    setVideo(v.id) { it.copy(state = DlState.Running(pct, frames = true)) }
                                }
                            }
                        }
                        if (gifs.enabled && !cancelDownloads) {
                            setVideo(v.id) { it.copy(state = DlState.Running(0, gifs = true)) }
                            gifsSaved += GifMaker.make(
                                app, file, platform.folder, who, procId, gifs,
                                cancelled = { cancelDownloads },
                            ) { pct -> setVideo(v.id) { it.copy(state = DlState.Running(pct, gifs = true)) } }
                        }
                    }

                    // Keep the video unless "delete original" is on and processing fully worked.
                    fun handleVideo(file: File, suffix: String, procId: String, who: String) {
                        if (!dropVideo) {
                            MediaSaver.saveToMovies(app, file, platform.folder, who, v, suffix)
                            photosOrVideos++
                            process(file, procId, who)
                            return
                        }
                        try {
                            process(file, procId, who)
                        } catch (e: Exception) {
                            MediaSaver.saveToMovies(app, file, platform.folder, who, v, suffix)
                            photosOrVideos++
                            throw e
                        }
                        if (cancelDownloads) {
                            // Stopped part-way: keep the video so nothing is lost.
                            MediaSaver.saveToMovies(app, file, platform.folder, who, v, suffix)
                            photosOrVideos++
                        } else {
                            videosDropped++
                        }
                    }

                    if (platform == Platform.BROWSER && v.media.isNotEmpty()) {
                        // Plain image/video file found while browsing.
                        val item = v.media.first()
                        val file = WebDownloader.download(
                            app, item, v.referer, workDir, v.id, pace.bytesPerSec,
                        ) { pct -> setVideo(v.id) { it.copy(state = DlState.Running(pct)) } }
                        val site = v.owner ?: "web"
                        if (item.isVideo || MediaSaver.isVideo(file)) {
                            handleVideo(file, "", v.id, site)
                        } else {
                            MediaSaver.saveImage(app, file, platform.folder, site, v, "")
                            photos++
                        }
                    } else if (platform == Platform.INSTAGRAM) {
                        val folderUser = v.owner ?: user
                        var post = v
                        var refreshed = false
                        val many = v.media.size > 1
                        var idx = 0
                        while (idx < post.media.size) {
                            if (cancelDownloads) throw YoutubeDL.CanceledException()
                            val item = post.media[idx]
                            val suffix = if (many) "_${idx + 1}" else ""
                            val file = try {
                                ig.download(item, workDir, "${v.id}$suffix", pace.bytesPerSec) { pct ->
                                    val overall = (idx * 100 + pct) / post.media.size
                                    setVideo(v.id) { it.copy(state = DlState.Running(overall)) }
                                }
                            } catch (e: InstagramClient.ExpiredLink) {
                                // Cached links go stale; fetch fresh ones once and retry.
                                if (refreshed) throw InstagramException("Instagram link expired")
                                post = ig.refreshPost(v.id) ?: throw InstagramException("Post no longer available")
                                refreshed = true
                                continue
                            }
                            if (item.isVideo) {
                                handleVideo(file, suffix, "${v.id}$suffix", folderUser)
                            } else {
                                MediaSaver.saveImage(app, file, platform.folder, folderUser, v, suffix)
                                photos++
                            }
                            file.delete()
                            idx++
                        }
                    } else {
                        val req = YoutubeDLRequest(v.url).apply {
                            if (platform == Platform.TIKTOK) {
                                // H.264 plays everywhere; fall back to whatever is best.
                                addOption("-f", "b[vcodec=h264]/b[vcodec^=avc]/b")
                            } else {
                                // YouTube: up to 1080p H.264 + AAC, merged to MP4 by ffmpeg.
                                addOption("-f", "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                                addOption("--merge-output-format", "mp4")
                            }
                            addOption("-o", "${workDir.absolutePath}/%(id)s.%(ext)s")
                            addOption("--no-playlist")
                            addOption("--no-mtime")
                            addOption("--no-part")
                            pace.limitRate?.let { addOption("--limit-rate", it) }
                            addOption("--retries", "3")
                            v.referer?.let { addOption("--referer", it) }
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
                        handleVideo(file, "", v.id, v.owner ?: user)
                    }
                    store.markSaved(platform.key(v.id))
                    setVideo(v.id) { it.copy(state = DlState.Done, saved = true, selected = false) }
                    ok++
                    break@retry
                } catch (e: YoutubeDL.CanceledException) {
                    setVideo(v.id) { it.copy(state = DlState.Idle) }
                    break@queue
                } catch (e: Exception) {
                    // Server says slow down: back off for a minute, then try once more.
                    if (isRateLimited(e.message) && attempt++ == 0) {
                        setVideo(v.id) { it.copy(state = DlState.Queued) }
                        if (waitFor(60_000, "Server asked us to slow down · waiting")) continue@retry
                        break@queue
                    }
                    setVideo(v.id) { it.copy(state = DlState.Failed(friendlyError(e.message))) }
                    failed++
                    break@retry
                }
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
                        append("Done $ok")
                        if (photosOrVideos > 0) append(" · $photosOrVideos videos → Movies/${platform.folder}/$shownUser")
                        if (photos > 0) append(" · $photos photos → Pictures/${platform.folder}/$shownUser")
                        if (framesSaved > 0) append(" · $framesSaved frames")
                        if (gifsSaved > 0) append(" · $gifsSaved GIFs")
                        if (framesSaved > 0 || gifsSaved > 0) append(" → Pictures/${platform.folder}/$shownUser")
                        if (videosDropped > 0) append(" · $videosDropped originals not kept")
                        if (detector != null) append(" (people only)")
                        detectorError?.let { append(" · person filter off: $it") }
                        if (failed > 0) append(" · $failed failed")
                        if (cancelDownloads) append(" · cancelled")
                    },
                )
            }
        }
    }

    var pace: DownloadPace
        get() = store.pace
        set(v) { store.pace = v }

    /** Waits [ms], showing a countdown; returns false if downloads were cancelled meanwhile. */
    private suspend fun waitFor(ms: Long, label: String): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            if (cancelDownloads) return false
            val left = end - System.currentTimeMillis()
            if (left <= 0) return true
            if (ms >= 1500) _state.update { it.copy(status = "$label… ${(left + 999) / 1000}s") }
            kotlinx.coroutines.delay(minOf(left, 250))
        }
    }

    private fun isRateLimited(msg: String?): Boolean {
        val m = msg.orEmpty().lowercase()
        return "429" in m || "too many requests" in m || "rate limit" in m || "rate-limit" in m
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
        const val BROWSER_USER = "browser"
    }
}
