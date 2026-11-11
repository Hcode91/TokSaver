package com.hcode91.toksaver

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaSaver {
    private val videoExts = setOf("mp4", "mov", "webm", "mkv")

    fun isVideo(file: File) = file.extension.lowercase() in videoExts

    /** Copies a finished download into Movies/TikTok/<user>/ so it shows in the gallery. */
    fun saveToMovies(context: Context, file: File, folder: String, username: String, video: Video, suffix: String = ""): String {
        val date = video.timestamp?.let {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it * 1000))
        } ?: "nodate"
        val ext = file.extension.lowercase().ifBlank { "mp4" }
        val name = "${username}_${date}_${video.id}$suffix.$ext"
        val mime = when (ext) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val relPath = "${Environment.DIRECTORY_MOVIES}/$folder/$username"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create file in Movies")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Couldn't open output")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "$relPath/$name"
    }

    /** Saves a photo to Pictures/<folder>/<user>/. */
    fun saveImage(context: Context, file: File, folder: String, username: String, video: Video, suffix: String): String {
        val date = video.timestamp?.let {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it * 1000))
        } ?: "nodate"
        val ext = file.extension.lowercase().ifBlank { "jpg" }
        val name = "${username}_${date}_${video.id}$suffix.$ext"
        val mime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
        val relPath = "${Environment.DIRECTORY_PICTURES}/$folder/$username"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create file in Pictures")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Couldn't open output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "$relPath/$name"
    }
}
