package com.hcode91.toksaver

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** How many frames to pull out of each downloaded video. */
enum class FrameMode(val label: String, val perSecond: Int) {
    NONE("Video only", 0),
    ALL("Video + every frame", -1),
    TEN("Video + 10 frames per second", 10),
    FIVE("Video + 5 frames per second", 5),
    ONE("Video + 1 frame per second", 1),
}

object FrameExtractor {

    /**
     * Saves frames from [file] as JPEGs to Pictures/TikTok/<user>/<videoId>/.
     * If [keep] is given, only frames it accepts are saved; file names keep the
     * original frame number so gaps show which frames were skipped.
     * Returns the number of frames saved. Stops early if [cancelled] returns true.
     */
    fun extract(
        context: Context,
        file: File,
        folder: String,
        username: String,
        videoId: String,
        mode: FrameMode,
        cancelled: () -> Boolean,
        keep: ((Bitmap) -> Boolean)? = null,
        onProgress: (Int) -> Unit,
    ): Int {
        if (mode == FrameMode.NONE) return 0
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IllegalStateException("Can't read video length")
            val frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0

            val relPath = "${Environment.DIRECTORY_PICTURES}/$folder/$username/$videoId"
            var saved = 0
            var index = 0
            fun handle(bmp: Bitmap) {
                index++
                if (keep == null || keep(bmp)) {
                    saved++
                    saveJpeg(context, bmp, relPath, "frame_%05d.jpg".format(index))
                }
                bmp.recycle()
            }

            if (mode == FrameMode.ALL && frameCount > 0) {
                // Decode sequentially in small batches: fast, and keeps memory low.
                val batch = 4
                var i = 0
                while (i < frameCount) {
                    if (cancelled()) break
                    val n = minOf(batch, frameCount - i)
                    val frames = try {
                        mmr.getFramesAtIndex(i, n)
                    } catch (e: Exception) {
                        // Some files report one frame too many; take what we can.
                        listOfNotNull(runCatching { mmr.getFrameAtIndex(i) }.getOrNull())
                    }
                    if (frames.isEmpty()) break
                    for (bmp in frames) handle(bmp)
                    i += n
                    onProgress(i * 100 / frameCount)
                }
            } else {
                // Fixed rate (or "every frame" fallback when the count is unknown: 30fps).
                val perSec = if (mode.perSecond > 0) mode.perSecond else 30
                val stepUs = 1_000_000L / perSec
                val totalUs = durationMs * 1000
                var t = 0L
                while (t < totalUs) {
                    if (cancelled()) break
                    val bmp = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp != null) handle(bmp)
                    t += stepUs
                    onProgress((t * 100 / totalUs).toInt().coerceAtMost(100))
                }
            }
            return saved
        } finally {
            mmr.release()
        }
    }

    private fun saveJpeg(context: Context, bmp: Bitmap, relPath: String, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create image in Pictures")
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: throw IllegalStateException("Couldn't open image output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
