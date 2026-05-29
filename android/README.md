# GPS Simulator (Android)

A native Android app that replays a user-supplied GPX file as live device GPS so any activity-tracking app records it as a normal run/ride/walk.

Design: [`tools/docs/superpowers/specs/2026-05-30-android-gps-simulator-design.md`](../tools/docs/superpowers/specs/2026-05-30-android-gps-simulator-design.md).

## Status

v0.3 — backend-backed, with auth and rename:
- Firebase Authentication (email/password). Google sign-in stubbed for v0.4.
- Per-user GPX library stored in a Node/Postgres backend. Upload, list, rename, delete.
- One-tap start/stop. 1 Hz mock GPS emission from the file's timestamps.
- Holds last point on EOF so the tracking app doesn't fall back to real GPS.
- First-run setup gate for Developer Options + permissions.

v1 requires GPX with `<time>` data on every trackpoint (use `tools/gpx_build.py` or `tools/gpx_add_time.py`).

## Build & install

Easiest path (no command line):

1. Open Android Studio Hedgehog or newer.
2. **File → Open** → select the `android/` directory.
3. Wait for Gradle sync. Android Studio will download the Gradle wrapper, the Android Gradle Plugin, and SDK components automatically.
4. Plug in your phone with USB debugging on, or use a connected Android device.
5. **Run ▸** → installs `GPS Simulator` to the device.

CLI path (if you already have Gradle 8.7 installed):

```
cd android
gradle wrapper       # creates gradlew + gradle-wrapper.jar
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## One-time device setup

Mock-location is a privileged feature. Until you do this, the app's "Start Simulation" button stays disabled and it tells you why:

1. **Enable Developer Options**: Settings → About phone → tap "Build number" 7 times.
2. **Pick GPS Simulator as the mock-location app**: Settings → System → Developer options → "Select mock location app" → **GPS Simulator**.
3. **Grant precise location** to the app when prompted.
4. **Grant notification permission** (Android 13+) — needed for the persistent "simulation active" notice.

Tapping "I did this — re-check" in the app re-runs the probe.

## End-to-end flow

1. Sign in (email/password) in the app.
2. Tap **+** → upload a GPX from your phone. The bytes go to the backend; the row shows distance / duration / point count from the server.
3. Tap a row → **Open** → the app caches the bytes locally → Replay screen.
4. Tap **Start Simulation**.
5. Open your activity-tracking app → start a recording.
6. When the GPX hits EOF the simulator holds the last point indefinitely — stop the recording, then stop here.
7. The tracking app uploads the activity recorded from the simulated coordinates.

## Project layout

```
android/
  settings.gradle.kts
  build.gradle.kts                 # root
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/gpssimulator/app/
        MainActivity.kt            # Compose UI (Library + Replay screens, AuthGate)
        LoginScreen.kt             # Email/password + Google (disabled) login UI
        AuthRepository.kt          # FirebaseAuth wrapper + AuthState flow
        ApiClient.kt               # OkHttp client; injects Firebase ID token
        GpxRepository.kt           # Talks to the backend; caches bytes locally
        MockLocationService.kt     # Foreground service + LocationManager emission
        GpxParser.kt               # XmlPullParser-based GPX reader
        GpxFile.kt                 # Remote-shaped record
        Geo.kt                     # Haversine helpers
        ServiceState.kt            # Shared StateFlow between service and UI
        SetupChecker.kt            # Permission + mock-provider probe
        TrackPoint.kt
      res/                         # Strings, themes, icons
```

## Known limitations (v1)

- iOS is not supported (no public mock-location path on iOS).
- GPX without timestamps is rejected at import. v2 will prompt for a target pace.
- No speed multiplier, no pause/resume mirroring of the tracking app's state.
- Service does not auto-resume if the OS kills the process. Re-tap Start.
- Don't ship this to the Play Store. Mock-location apps are rejected by policy.
