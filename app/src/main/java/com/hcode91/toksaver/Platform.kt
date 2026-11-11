package com.hcode91.toksaver

enum class Platform(
    val label: String,
    val folder: String,       // Movies/<folder>/<user>, Pictures/<folder>/<user>
    val hint: String,
    val columns: Int,
    val aspectW: Int,         // tile shape
    val aspectH: Int,
    private val idPrefix: String,
) {
    TIKTOK("TikTok", "TikTok", "TikTok username", 3, 9, 16, ""),
    YOUTUBE("YouTube", "YouTube", "YouTube channel (@handle)", 2, 16, 9, "yt:"),
    SHORTS("Shorts", "YouTube", "YouTube channel (@handle)", 3, 9, 16, "yt:"),
    INSTAGRAM("Instagram", "Instagram", "Instagram username", 3, 4, 5, "ig:");

    /** Key used for "already saved" tracking and the list cache. */
    fun key(id: String) = idPrefix + id

    fun cacheName(user: String) = name.lowercase() + "_" + user.lowercase()

    /** Turns "name", "@name" or a pasted profile link into a bare username / handle. */
    fun cleanUser(raw: String): String {
        val s = raw.trim()
        val fromUrl = when (this) {
            TIKTOK -> Regex("tiktok\\.com/@([\\w.-]+)").find(s)?.groupValues?.get(1)
            YOUTUBE, SHORTS -> Regex("youtube\\.com/(@[\\w.-]+|channel/UC[\\w-]{22})").find(s)
                ?.groupValues?.get(1)?.removePrefix("channel/")
            INSTAGRAM -> Regex("instagram\\.com/([\\w.]+)").find(s)?.groupValues?.get(1)
        }
        if (fromUrl != null) return fromUrl.removePrefix("@")
        return s.removePrefix("@").substringBefore('/').substringBefore('?').trim()
    }

    /** Profile URL that yt-dlp lists (TikTok / YouTube). */
    fun listUrl(user: String): String = when (this) {
        TIKTOK -> "https://www.tiktok.com/@$user"
        YOUTUBE -> ytBase(user) + "/videos"
        SHORTS -> ytBase(user) + "/shorts"
        INSTAGRAM -> "https://www.instagram.com/$user/"
    }

    private fun ytBase(user: String) =
        if (user.matches(Regex("UC[\\w-]{22}"))) "https://www.youtube.com/channel/$user"
        else "https://www.youtube.com/@$user"

    companion object {
        /** Works out the platform from a shared link. */
        fun fromLink(text: String): Platform? = when {
            "tiktok.com" in text -> TIKTOK
            "youtube.com" in text && "/shorts" in text -> SHORTS
            "youtube.com" in text -> YOUTUBE
            "instagram.com" in text -> INSTAGRAM
            else -> null
        }
    }
}
