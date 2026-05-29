# Strava Spoof (Android)

A native Android app that replays a user-supplied GPX file as live device GPS so the official Strava app records it as a normal activity.

Design: [`tools/docs/superpowers/specs/2026-05-30-android-gps-spoof-app-design.md`](../tools/docs/superpowers/specs/2026-05-30-android-gps-spoof-app-design.md).

## Status

v0.1 — minimal but complete:
- Import GPX files via SAF (Files / Drive / share-to-app).
- One-tap start/stop. 1 Hz mock GPS emission from the file's timestamps.
- Holds last point on EOF so Strava doesn't fall back to real GPS.
- First-run setup gate for Developer Options + permissions.

v1 requires GPX with `<time>` data on every trackpoint (use `tools/gpx_build.py` or `tools/gpx_add_time.py`).

## Build & install

Easiest path (no command line):

1. Open Android Studio Hedgehog or newer.
2. **File → Open** → select the `android/` directory.
3. Wait for Gradle sync. Android Studio will download the Gradle wrapper, the Android Gradle Plugin, and SDK components automatically.
4. Plug in your phone with USB debugging on, or use a connected Android device.
5. **Run ▸** → installs `Strava Spoof` to the device.

CLI path (if you already have Gradle 8.7 installed):

```
cd android
gradle wrapper       # creates gradlew + gradle-wrapper.jar
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## One-time device setup

Mock-location is a privileged feature. Until you do this, the app's "Start Spoofing" button stays disabled and it tells you why:

1. **Enable Developer Options**: Settings → About phone → tap "Build number" 7 times.
2. **Pick Strava Spoof as the mock-location app**: Settings → System → Developer options → "Select mock location app" → **Strava Spoof**.
3. **Grant precise location** to the app when prompted.
4. **Grant notification permission** (Android 13+) — needed for the persistent "spoofing active" notice.

Tapping "I did this — re-check" in the app re-runs the probe.

## End-to-end flow

1. Import a GPX file (or transfer one via cable to `/sdcard/Download/` first).
2. Tap it → Replay screen → **Start Spoofing**.
3. Open the official Strava app → start a Run.
4. When the GPX hits EOF the app holds the last point indefinitely — stop Strava, then stop here.
5. Strava uploads the activity recorded from the spoofed coordinates.

## Project layout

```
android/
  settings.gradle.kts
  build.gradle.kts                 # root
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/strava/spoof/
        MainActivity.kt            # Compose UI (Library + Replay screens)
        MockLocationService.kt     # Foreground service + LocationManager emission
        GpxParser.kt               # XmlPullParser-based GPX reader
        GpxFile.kt                 # Data classes + JSON meta
        GpxFileRepository.kt       # Imports + lists local GPX files
        Geo.kt                     # Haversine helpers
        ServiceState.kt            # Shared StateFlow between service and UI
        SetupChecker.kt            # Permission + mock-provider probe
        TrackPoint.kt
      res/                         # Strings, themes, icons
```

## Known limitations (v1)

- iOS is not supported (no public mock-location path on iOS).
- GPX without timestamps is rejected at import. v2 will prompt for a target pace.
- No speed multiplier, no pause/resume mirroring of Strava state.
- Service does not auto-resume if the OS kills the process. Re-tap Start.
- Don't ship this to the Play Store. Mock-location apps are rejected by policy.
