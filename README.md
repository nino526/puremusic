# Nino.music — Android music player

Offline-first Android player with local songs, playlists, mini/full player, integrated audio visualizer and free song identification via the Google app. No ads or account.

## Android features
- User-selectable System / Light / Dark theme.
- Nine procedural visualizations: Plasma, Alchemy, Particles, Spectrum, Waves, Circle, Aura, Galaxy, and Nebula Pulse. Alchemy is the initial mode; selection is saved.
- Visualizer is shown in Now Playing and full screen. Android RECORD_AUDIO permission is required to capture the local playback audio session. Some phone routes/OS versions may not expose audio capture.
- Local song library, queue, playlists, play next, playback mode (Normal / Shuffle / Repeat all / Repeat one), sharing and MediaStore deletion with Android confirmation.
- Background playback and Android system media controls.
- Android Quick Settings **Identify song** tile. Add the tile in Quick Settings edit mode; on Android 13+, Settings provides an Add tile request. Tile opens Google's song recognition.
- Authorized HTTPS direct audio downloads only. TikTok video URL playback links are opened in the original app; no audio extraction.

### Building
GitHub Actions runs Kotlin unit tests, builds a debug APK, verifies metadata and uploads it under Actions > Artifacts. An Android emulator smoke-test workflow also runs.

Built artifacts are **debug APKs**, not production-signed releases. Emulator checks do not replace testing live audio visualization, Quick Settings tile and Android MediaStore permissions on a physical phone.

Package ID is intentionally kept as `com.puremusic.app` to preserve installed settings and playlists across upgrades when using the same APK signing key.
