# TokSaver

Android app: pick a platform (TikTok, YouTube, YouTube Shorts or Instagram), enter a username, browse everything they've posted, pick some (or all) and save them to your phone.

- Videos save to `Movies/<Platform>/<username>/` and photos to `Pictures/<Platform>/<username>/`. Both show up in your gallery.
- **YouTube**: enter a channel's @handle or a channel link. Videos download at up to 1080p as MP4.
- **Instagram**: tap **Browse Instagram** to open Instagram inside the app (sign in there the first time). As you scroll feeds, profiles, Reels or posts, the app picks up each post it sees. Tap **View N detected** (or press back) to go to the grid and download. Photos, Reels and carousels (all items) are supported. Files go into a folder named after the account that posted them. Picked-up posts stay in the grid until you use ⋮ → **Clear detected Instagram posts**.
- **Tap** a video to select it. **Long-press** to open it in TikTok.
- The ⋮ menu lets you select all, select only videos you haven't downloaded yet, or clear the selection.
- Videos you've already saved get a **Saved** badge.
- When you tap Download, you can choose to also cut each video into JPEG frames: every frame, or 10, 5 or 1 per second. Frames go to `Pictures/TikTok/<username>/<videoId>/`.
- **Slow-motion GIFs:** cuts each video into 3-second pieces and saves each as a slowed-down GIF (normal, ½, ⅓ or ¼ speed; 480px wide, 15fps). A leftover piece under 1 second is skipped. GIFs go to `Pictures/<Platform>/<username>/<videoId>/gif_01.gif`, …
- **Don't keep the original video:** optional. When frames and/or GIFs are being made, the video itself isn't saved. If processing fails or you cancel, the video is kept so nothing is lost.
- **Only keep frames with people:** an on-device detector (MediaPipe EfficientDet-Lite0) checks each frame and skips ones without a person. You can set the detection confidence and how much of the frame the person must fill. Kept frames keep their original frame number, so gaps show which frames were skipped.
- Each tile plays TikTok's short looping preview, so you can see what a video is before downloading. You can turn this off in ⋮ → Animated previews.
- Profile lists are cached. Reopening a username shows the saved list instantly, then checks only for new videos. ⋮ → **Full reload** rebuilds the whole list.
- You can share a profile link from the TikTok app straight into TokSaver.

## Browser

The **Browser** chip opens an in-app web browser. As you browse, it lists images (above a minimum size, 300px by default) and videos it spots on each page. Only the links are listed; nothing is downloaded until you choose. Tap **View N found** (or press back) to go to the grid, then select and download with all the usual options. Files go to `Pictures/Browser/<site>/` and `Movies/Browser/<site>/`.

The browser's ⋮ menu has back, forward, reload, pause/resume detection and the minimum image size. Streaming video (m3u8/mpd) is listed as **Stream** and downloaded through yt-dlp. Protected streams (Netflix and similar) can't be saved.

## Download pace

⋮ → **Download pace** sets how gently the app downloads:

- **Fast**: no pause, full speed.
- **Normal** (default): about a 2-second pause between downloads.
- **Gentle**: about 5 seconds, capped at 2 MB/s.
- **Very gentle**: about 10 seconds, capped at 1 MB/s.

Pauses vary a little at random. If a server replies "too many requests", the app waits 60 seconds and tries that item once more.

## How it works

It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android). TikTok changes often. The app updates yt-dlp automatically once a day. If loading breaks, use ⋮ → **Update engine (nightly)**, which usually has the newest TikTok fixes.

Limits: only public accounts work. Photo (slideshow) posts aren't supported.

## Build

GitHub Actions builds the APK on every push (artifact **TokSaver-apk**). It's signed with the fixed key in `app/`, so new builds install over old ones.
