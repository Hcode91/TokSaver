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

## How it works

It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android). TikTok changes often. The app updates yt-dlp automatically once a day. If loading breaks, use ⋮ → **Update engine (nightly)**, which usually has the newest TikTok fixes.

Limits: only public accounts work. Photo (slideshow) posts aren't supported.

## Build

GitHub Actions builds the APK on every push (artifact **TokSaver-apk**). It's signed with the fixed key in `app/`, so new builds install over old ones.
