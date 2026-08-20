# BMW Car Control — Get a ready-to-install APK

I can't compile an APK myself in this environment (no access to Google's
Android SDK servers), but this project is set up to build itself
automatically and for free using GitHub Actions — entirely from your phone,
no PC or Android Studio required.

## Steps (all doable from a phone browser)

1. Go to https://github.com and sign in (or create a free account).
2. Tap **+** → **New repository**. Name it e.g. `BMWCarControl`. Keep it
   **Public** (required for free Actions minutes). Create it.
3. On the new repo page, tap **Add file → Upload files**.
4. Upload every file from this project, keeping the folder structure:
   - `build.gradle`
   - `settings.gradle`
   - `gradle.properties`
   - `app/build.gradle`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/example/bmwcarcontrol/MainActivity.kt`
   - `app/src/main/res/layout/activity_main.xml`
   - `app/src/main/res/values/strings.xml`
   - `.github/workflows/build.yml`

   (GitHub's upload page supports dragging a whole folder in a desktop
   browser. On mobile, if you can't select folders, use the GitHub app's
   "create file" option and paste each file's content in one at a time —
   there are only 8 files.)
5. Commit the files. This automatically triggers the **Actions** workflow.
6. Tap the **Actions** tab → open the running/most recent workflow run →
   wait ~2–3 minutes for it to finish (green check).
7. Scroll down to **Artifacts** → tap **BMWCarControl-debug-apk** to
   download a zip containing `app-debug.apk`.
8. On your phone, open the downloaded zip (Android's Files app can extract
   it), then tap `app-debug.apk` to install. You'll need to allow
   "install unknown apps" for your browser/file manager when prompted —
   this is normal for any APK not from the Play Store.

This produces a real, signed (debug-signed) installable APK built by
GitHub's servers, using the exact same reverse-engineered protocol
described in `MainActivity.kt`.
