# Android GPS Simulator — Design

**Date:** 2026-05-30
**Status:** Approved
**Sibling tools:** `tools/gpx_build.py` (synthesizes timed GPX), `tools/gpx_add_time.py` (stamps untimed GPX)

## Goal

A native Android app that replays a user-supplied GPX file as the device's live GPS in real time, so the official activity-tracking app records it as a normal activity. From the tracking app's point of view there is no way to distinguish a simulated run from a real one.

## Why

`main_product.md` describes the simulator at a high level but glosses over the hard part: making the user's real activity-tracking app accept fake GPS. Uploading a pre-built GPX is a fallback, not the product. The product is **live mock-location** that the tracking app reads through Android's normal `LocationManager` API.

## Non-goals (v1)

- iOS. There's no clean public path on iOS.
- Generating GPX in-app. Users upload pre-built files (e.g. from `gpx_build.py`).
- Untimed GPX support. v1 requires GPX with `<time>` data on every trackpoint.
- Speed multiplier, pause/resume detection, segment-aware simulation.
- Play Store distribution. Mock-location apps are rejected; sideload only.

## Architecture

```
[MainActivity / Compose UI]
     │
     ├─► Library screen ── Import (SAF) ──► GpxFileRepository ── app files dir
     │        │
     │        └─► tap file ──► Replay screen
     │                              │
     │                              ├─ Start ─► startForegroundService(MockLocationService)
     │                              └─ Stop ──► service stopSelf()
     │
     └─► SetupChecker (first-run / pre-start gate)
              ├─ ACCESS_FINE_LOCATION permission
              └─ "Select mock location app" in Developer Options
                    (verified by attempting addTestProvider in a sandbox)

[MockLocationService — foreground]
     │
     ├─ GpxParser.parse(file) ──► List<TrackPoint(lat, lon, ele, t)>
     │
     ├─ LocationManager.addTestProvider("gps", ...)
     │
     ├─ 1 Hz coroutine loop:
     │     offset = monotonic_now − start
     │     find trackpoint where (t_i − t_0) closest to offset
     │     setTestProviderLocation(...)
     │     on EOF: re-emit last point every 1 s until Stop
     │
     └─ Persistent notification with "Stop" action
```

## Components

### 1. `GpxParser`
Parses GPX 1.1. Inputs: `InputStream`. Output: `List<TrackPoint>` with `lat: Double, lon: Double, ele: Double?, time: Instant`. Uses `XmlPullParser` (built-in, no deps). Rejects files where any trackpoint lacks `<time>`.

### 2. `GpxFileRepository`
- `listFiles(): List<GpxFile>` — reads `context.filesDir/gpx/` and returns `GpxFile(name, distanceMeters, durationSeconds, pointCount)`.
- `importFile(uri: Uri): GpxFile` — copies via `ContentResolver.openInputStream`, parses to verify, writes to `filesDir/gpx/<sanitized-name>.gpx`, persists a small `.meta.json` next to it with the computed summary.
- `deleteFile(name)`.

### 3. `SetupChecker`
Three checks, called from the Replay screen before Start is enabled:
1. `ACCESS_FINE_LOCATION` granted? If not, request.
2. `addTestProvider` succeeds in a probe? If `SecurityException`, send the user to `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` with an in-app explainer.
3. Notification permission (Android 13+) for the foreground notification.

### 4. `MockLocationService`
`Service` (foreground, type `location`). Lifecycle:
- `onStartCommand(intent with filename)` →
  - parse the GPX,
  - `startForeground(notif)`,
  - `addTestProvider("gps", ...)` with realistic capabilities (hasAccuracy, hasAltitude, hasSpeed, powerRequirement low),
  - `setTestProviderEnabled("gps", true)`,
  - launch emission coroutine.
- Emission loop (1 Hz, `Dispatchers.Default`):
  - `t0 = trackpoints.first().time`
  - On tick `i`: `target = t0 + i seconds`; find largest index `k` with `trackpoints[k].time <= target` and linearly interpolate to next point for sub-second smoothness.
  - Build a `Location("gps")` with lat/lon/ele/accuracy (3.0 m) and `time = System.currentTimeMillis()`, `elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()`, derive `speed` from previous emit.
  - `setTestProviderLocation("gps", loc)`.
  - On EOF: keep re-emitting the last point each tick (so the tracking app doesn't fall back to real GPS).
- `onDestroy`: `setTestProviderEnabled(false); removeTestProvider("gps")`.
- Stop action in notification → `stopSelf()`.

### 5. UI (Jetpack Compose, single Activity, two screens)
- **LibraryScreen**: lazy list of `GpxFile`. Each row: name, distance (km, 2dp), duration (mm:ss). Top bar with "Import GPX" → `ActivityResultContracts.OpenDocument(["application/gpx+xml", "application/octet-stream", "*/*"])`.
- **ReplayScreen**: file summary card, status text ("Idle" / "Simulating — elapsed 04:12 / 28:45"), Start/Stop button. Live updates by binding to a `StateFlow` exposed from the service via a bound-service interface OR a simple `LocalBroadcastManager`-style flow through a singleton `ServiceState` object. Simpler: a `ServiceState` `object` with a `MutableStateFlow<RunState>` that the service updates and the UI collects.

## Data flow (run lifecycle)

```
user picks file in library → repository hands path to ReplayScreen
user taps Start
  → SetupChecker validates (perm + mock-app + notif perm)
  → Intent { EXTRA_GPX_PATH = ... } → startForegroundService
service onStartCommand:
  parse → addTestProvider → enable → coroutine launch
loop emits 1 Hz Location → Android LocationManager → tracking app
user opens your tracking app → Start activity → records simulated track
GPX ends → service holds last point
user hits Stop in the tracking app → then Stop in our app
service: removeTestProvider → stopSelf
```

## Permissions (manifest)

- `ACCESS_FINE_LOCATION` (runtime)
- `ACCESS_COARSE_LOCATION` (runtime; some OEM LocationManagers want both)
- `FOREGROUND_SERVICE` (normal)
- `FOREGROUND_SERVICE_LOCATION` (Android 14+, normal)
- `POST_NOTIFICATIONS` (runtime, Android 13+)
- Mock-location is **not** a manifest permission anymore; it's granted by the user selecting the app in Developer Options.

## Error handling

- GPX parse failure → toast + don't add to library.
- GPX missing `<time>` → reject at import with a message ("v1 requires GPX with timestamps; run through gpx_add_time.py first").
- `addTestProvider` `SecurityException` → SetupChecker catches, shows "Pick this app in Developer Options → Select mock location app" with a deep-link button.
- Service killed by OS → it's a foreground service, so the OS shouldn't kill it; if it does, the user reopens the app and restarts. v1 does not auto-resume.

## Testing

- **No automated UI test in v1**, because mock-location requires real device + manual Developer Options toggle.
- **Manual end-to-end**: install on real Android device, enable Dev Options, select this app as mock provider, import the sample `morning_run_built.gpx`, hit Start, open a separate "GPS Test" app to verify the simulated fix, then open the tracking app and start an activity — confirm the recorded activity matches the GPX shape.
- **Unit tests where cheap**: `GpxParser` (parse a sample GPX, assert point count and timing). `GpxFileRepository` summary math (distance via Haversine, duration from first/last timestamp).

## Out of scope follow-ups

- v2: accept untimed GPX, prompt for pace, do pace-based playback (reuses `gpx_build.py` model in Kotlin).
- v2: speed multiplier knob.
- v3: tiny map preview in the replay screen (osmdroid).
- v3: bundled `gpx_build.py` route generator in-app.
