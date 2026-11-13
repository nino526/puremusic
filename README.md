# PURE Music — Android Studio project (source prototype v0.5)

Dark English-language Android app, no ads, no analytics, no account.

## Recognition choices (v0.2)
- **Free Song Search (Google):** Opens Google app song search via an undocumented Android intent (with an app-launch fallback). No token or fee. Google displays its result externally; PURE Music cannot import or store that result automatically. Google app availability, shortcut support, and recognition availability vary by device and may change.
- **Listen & Identify (AudD):** Existing in-app 12-second recorder remains as an OPTIONAL paid/trial token-based provider. It still saves successful results to PURE Music history.
- This update does **not** integrate Shazam fingerprinting or a free unlimited recognition API. Neither is guaranteed by a GitHub repository. To integrate another project's GPL-3.0 implementation, study source, dependencies and license obligations; official service availability is separate.

## Implemented
- Reads locally stored songs using Android MediaStore and requests audio permission.
- Plays/pause local music using MediaPlayer; song list search.
- Creates/deletes playlists and adds tracks (stored on device via SharedPreferences).
- Floating draggable Android overlay shortcut (user-approved draw-over-apps permission); foreground notification.
- Explicit **Listen & Identify** recognition: 12-second microphone sample sent to AudD if user supplies an API token. Results are saved to the local recognition history.
- Android share target for TikTok URLs; opens a verified TikTok link in an external app.
- Legal file downloads via Android DownloadManager from an HTTPS direct `.mp3`, `.m4a` or `.ogg` URL provided by the user. Saves in `Music/PURE Music`.

## Not yet implemented
- Automatic continuous identification in the background; requires careful implementation of microphone foreground service, microphone access restrictions, battery/consent controls.
- TikTok audio extraction: the app does not bypass platform controls or fetch protected audio from video links.
- Automatic discovery of official full-song download sources, waveform/album art, media session + lockscreen integration, playlist reordering, persistent playback service.
- No APK in this archive: build with Android Studio and an Android SDK.

## Build
1. Install Android Studio (JDK 17, Android SDK platform 35).
2. Open this folder as an Android Studio project, allow Gradle sync to download dependencies.
3. Connect an Android device (Android 8+), run the `app` configuration.
4. Grant music permission. For free recognition use **Identify > Free Song Search (Google)** (Google app required). Optional integrated recognition still requires microphone permission and an **AudD** API token under Settings.
5. For overlay tap `Enable Floating Button`, allow draw over other apps, then return to Settings and tap again.
6. For TikTok use TikTok > Share > PURE Music, or paste its link into the Identify page. Downloads require a directly downloadable authorized audio URL.

## Privacy and rights
An AudD sample is uploaded to the AudD service only after tapping recognition. Token is stored in app preferences (not encrypted in this prototype). Never ship a shared API token in an APK. Only download audio for which you have necessary permission or license. Recognition metadata doesn't grant access to copyrighted tracks.

## Scope
This is a *source prototype*; not production-ready or independently compiled/tested in this environment. Android 14+ foreground-service and notification behavior should be tested on a physical device. Automatic recognition and real TikTok audio downloads are not represented as completed functionality.

## v0.4 — unified music playback
- Music playback moved to `PlaybackService` (foreground service, `mediaPlayback` type), using Android `MediaSession` and a MediaStyle notification with previous/play-pause/next controls.
- Mini player now includes shuffle, previous, play/pause, next, repeat and the queue. Tap the title/cover to expand.
- Full player has cover placeholder, title/artist, seek bar, position/duration, shuffle, previous, play/pause, next, repeat, and up-next queue.
- Background audio stays active when MainActivity is destroyed; lock-screen and shade media controls use the session.
- Android/MIUI renders the notification media player according to OS design. The app cannot dictate the exact MIUI control arrangement, artwork is a placeholder until album-art extraction is added.
- Google's external song search continues to work as in v0.3.
- **Build status:** source modifications made, but not compiled or device-tested here because Android SDK/Gradle wrapper are unavailable. Open the folder in Android Studio, sync, build a debug APK and test background playback, notification controls and Android 13+ notification permission on-device.


## v0.5 — Music Visualizer

Open **Now Playing → Visualizer**. Choose Ambience, Alchemy, or Bars and Waves; tap the settings icon for the five palettes, effect intensity and 15/30/60 FPS. Touch artwork to hide controls; Android system bars are hidden while the visualizer is open. Playback continues through the existing PlaybackService and system media notification.

The visualizer uses the Android `audiofx.Visualizer` FFT/waveform output of the active local MediaPlayer audio session. It is offline: captures are used only for rendering, not uploaded or saved. Android requires RECORD_AUDIO permission even for session visualization. Some devices/audio-output routes may block visualizer capture. On unsupported devices the UI displays an error, not fake audio levels. Capture is released when paused or when exiting the visualizer. Default 30 FPS; 15 FPS is recommended to conserve battery.

Build with Android Studio (Android SDK 35, Java 17, Gradle Android plugin per existing project). This package is source-only and has not been compiled or device-tested in this environment.

## Tablet build workflow

GitHub Actions configuration is included in `.github/workflows/build-apk.yml`. The workflow automatically starts when committed to `main`, and can be triggered manually from the Actions tab. It builds a debug APK with Android SDK 35 and JDK 17 and uploads the resulting artifact. The workflow has not yet run; successful compilation and behavior must be verified. See `BUILD_ON_TABLET.md`.
