# gps-simulator

A native Android app + Node/Postgres backend that simulates the device's GPS in real time. Plus a small kit of Python utility scripts in `tools/` that work with Strava's public endpoints (separate concern — Strava-specific by design).

## What's here

### `android/` — GPS Simulator (live GPS simulation)

Native Android app. Replays a GPX file as the device's mock GPS in real time so any activity-tracking app records it as a normal run/ride/walk. Gated behind Firebase Auth (email/password). GPX files live in the backend, not on the device. See [`android/README.md`](android/README.md) for build + on-device setup.

### `backend/` — Node/Express + Postgres

REST API that stores per-user GPX files in Postgres, scoped by Firebase UID. Endpoints for upload, list, download, rename, delete. See [`backend/README.md`](backend/README.md) for the API shape and [RUNNING.md §2.6](RUNNING.md) for setup.

### `tools/` — Python CLIs (Strava-specific utilities)

Scripts that build, time, and download GPX data. These talk to Strava's public endpoints; their names reflect that and are intentionally not renamed. See [`tools/README.md`](tools/README.md).

- `gpx_build.py` — synthesizes a realistic timed GPX from a starting coord (OSRM + opentopodata + pace model).
- `gpx_add_time.py` — stamps an untimed GPX at constant pace.
- `join_gpx_strava_files.py` — concatenates GPX files.
- `strava_traces_downloader.py` — reconstructs GPX from public Strava activities.
- `strava_kudos_tool.py` — mass-kudos in feeds/clubs.
- `strava_photo_downloader.py` — bulk download an athlete's photos.

### `main_product.md`

The product brief.

## End-to-end flow (the product)

1. Use `tools/gpx_build.py` to synthesize a plausible timed GPX (or bring your own).
2. Sign in to the **GPS Simulator** Android app and upload the GPX. It lands in your account on the backend.
3. Tap the file → **Start Simulation** in the app.
4. Open your activity-tracking app and start a recording.
5. When the GPX ends the simulator holds the last point — stop the recording, then stop the simulator.
6. The tracking app uploads the recorded activity as if you'd actually moved.
