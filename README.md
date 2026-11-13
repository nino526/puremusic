# PURE Music v0.6.2 (Android prototype)

- Five sound effects with persisted mode selection, Alchemy by default.
- Audio-reactive capture permission prompt appears in embedded Now Playing.
- Animation clock continues during playback even when FFT capture is temporarily unavailable; an explicit message distinguishes ambient mode from sound-reactive mode.
- Playback mode cycles Normal → Shuffle → Repeat all → Repeat one, and applies to automatic track transitions.
- Close (X) button stops music, removes playback notification and hides the mini player.
- Obsolete AudD API token input and network implementation removed. Google Song Search opens the Google app. Existing stored AudD token is deleted on upgrade.
- Light/dark status-bar contrast correction; system theme by default.

**Build:** GitHub Actions workflow `Build PURE Music APK` compiles a debug APK; this project requires Android SDK 35 and JDK 17.

**Important:** Android Audio Visualizer requires RECORD_AUDIO permission. The visualizer can be paused or can show an ambient animation if the device does not allow FFT capture. No audio is uploaded for visualization. Google recognition is external.

