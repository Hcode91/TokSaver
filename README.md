# TokSaver

Android app: enter a TikTok username, browse all of that profile's public videos, pick some (or all) and save them to your phone.

- Videos save to `Movies/TikTok/<username>/` and show up in your gallery.
- **Tap** a video to select it. **Long-press** to open it in TikTok.
- The ⋮ menu lets you select all, select only videos you haven't downloaded yet, or clear the selection.
- Videos you've already saved get a **Saved** badge.
- When you tap Download, you can choose to also cut each video into JPEG frames: every frame, or 10, 5 or 1 per second. Frames go to `Pictures/TikTok/<username>/<videoId>/`.
- You can share a profile link from the TikTok app straight into TokSaver.

## How it works

It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android). TikTok changes often. The app updates yt-dlp automatically once a day. If loading breaks, use ⋮ → **Update engine (nightly)**, which usually has the newest TikTok fixes.

Limits: only public accounts work. Photo (slideshow) posts aren't supported.

## Build

GitHub Actions builds the APK on every push (artifact **TokSaver-apk**). It's signed with the fixed key in `app/`, so new builds install over old ones.
