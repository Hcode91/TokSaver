package com.hcode91.toksaver

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.TimeUnit

/** Settings for "cut the video into slow-motion GIFs". */
data class GifOptions(
    val enabled: Boolean = false,
    val slowFactor: Int = 2,     // 2 = half speed, 3 = third speed, 4 = quarter speed
    val clipSeconds: Int = 3,
    val width: Int = 480,
    val fps: Int = 15,
)

/** Uses the ffmpeg bundled with youtubedl-android to make GIFs. */
object GifMaker {

    /**
     * Cuts [file] into [GifOptions.clipSeconds]-long pieces, slows each down and saves it as a GIF
     * in Pictures/<folder>/<user>/<videoId>/. A leftover under 1 second is skipped.
     * Returns how many GIFs were saved.
     */
    fun make(
        context: Context,
        file: File,
        folder: String,
        username: String,
        videoId: String,
        opts: GifOptions,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Int {
        val durationSec = videoLengthSeconds(file)
        if (durationSec <= 0.0) throw IllegalStateException("Can't read video length")
        val clips = buildList {
            var start = 0.0
            while (start < durationSec) {
                val len = minOf(opts.clipSeconds.toDouble(), durationSec - start)
                if (len >= 1.0 || isEmpty()) add(start to len)
                start += opts.clipSeconds
            }
        }

        val tmpDir = File(context.cacheDir, "gif").apply { deleteRecursively(); mkdirs() }
        val relPath = "${Environment.DIRECTORY_PICTURES}/$folder/$username/$videoId"
        var saved = 0
        try {
            for ((i, clip) in clips.withIndex()) {
                if (cancelled()) break
                val (start, len) = clip
                val out = File(tmpDir, "clip.gif")
                // Slow down, shrink, then build a per-clip palette for good GIF colours.
                val filter = "setpts=${opts.slowFactor}*PTS,fps=${opts.fps}," +
                    "scale=${opts.width}:-2:flags=lanczos,split[a][b];" +
                    "[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4"
                runFfmpeg(
                    context,
                    listOf(
                        "-y", "-ss", "%.3f".format(java.util.Locale.US, start),
                        "-t", "%.3f".format(java.util.Locale.US, len),
                        "-i", file.absolutePath,
                        "-an", "-filter_complex", filter,
                        "-loop", "0", out.absolutePath,
                    ),
                )
                saveGif(context, out, relPath, "gif_%02d.gif".format(i + 1))
                out.delete()
                saved++
                onProgress((i + 1) * 100 / clips.size)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
        return saved
    }

    private fun videoLengthSeconds(file: File): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } finally {
            mmr.release()
        }
    }

    private fun runFfmpeg(context: Context, args: List<String>) {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!bin.exists()) throw IllegalStateException("ffmpeg missing")
        val pkgs = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-loglevel", "error") + args)
            .redirectErrorStream(true)
        pb.environment().apply {
            put(
                "LD_LIBRARY_PATH",
                listOf("ffmpeg", "python", "aria2c").joinToString(":") { File(pkgs, "$it/usr/lib").absolutePath },
            )
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroy()
            throw IllegalStateException("GIF took too long")
        }
        if (p.exitValue() != 0) {
            throw IllegalStateException("GIF failed: " + log.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(150))
        }
    }

    private fun saveGif(context: Context, file: File, relPath: String, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create GIF in Pictures")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't open GIF output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
