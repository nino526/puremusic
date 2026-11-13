# PURE Music — build APK without a laptop

The `.github/workflows/build-apk.yml` workflow builds a **debug APK** using GitHub Actions.
No locally installed Android Studio, Gradle, or Android SDK is needed on the tablet.

1. Create a private repository on GitHub and upload the *contents* of this folder (not the ZIP as one file). Maintain folder paths, including `.github/workflows/build-apk.yml`.
2. Open your repository's **Actions** tab. Choose **Build PURE Music APK** → **Run workflow**.
3. When the job finishes successfully, open it and download **PUREMusic-debug-APK** from **Artifacts**.
4. Extract the artifact ZIP. Install `app-debug.apk` on your Android tablet, granting permission to install from your browser/file manager if requested.
5. Test playback, the media controls in the notification shade, microphone and visualizer on an actual Android device.

Notes:
- This workflow has NOT been executed in this environment, because SDK/Gradle and external downloads are unavailable here.
- If GitHub Actions reports a Kotlin/Gradle error, copy the build log into ChatGPT for fixes.
- The debug APK is signed with a standard debug key on the GitHub runner; it may NOT install as an update over the existing PURE Music. If Android reports a signature conflict, uninstall the old app *after backing up your playlists and app data* or use the original signing key to create a matching signed APK.
- GitHub Actions free usage and artifact retention depend on your account/repository.
