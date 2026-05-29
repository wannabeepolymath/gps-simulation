# strava_hack_tools

Tools for Strava — both offline data hacks (Python CLIs) and a live GPS spoofer (Android app).

## What's here

### `android/` — Strava Spoof (live GPS spoofing)

Native Android app. Replays a GPX file as the device's mock GPS in real time so the official Strava app records it as a normal run. Gated behind Firebase Auth (email/password or Google). GPX files live in the backend, not on the device. See [`android/README.md`](android/README.md) for build + on-device setup.

### `backend/` — Node/Express + Postgres

REST API that stores per-user GPX files in Postgres, scoped by Firebase UID. Endpoints for upload, list, download, rename, delete. See [`backend/README.md`](backend/README.md) for the API shape and [RUNNING.md §2.6](RUNNING.md) for setup.

### `tools/` — Python CLIs

Scripts that build, time, and download GPX data. See [`tools/README.md`](tools/README.md).

- `gpx_build.py` — synthesizes a realistic timed GPX from a starting coord (OSRM + opentopodata + pace model).
- `gpx_add_time.py` — stamps an untimed GPX at constant pace.
- `join_gpx_strava_files.py` — concatenates GPX files.
- `strava_traces_downloader.py` — reconstructs GPX from public Strava activities.
- `strava_kudos_tool.py` — mass-kudos in feeds/clubs.
- `strava_photo_downloader.py` — bulk download an athlete's photos.

### `main_product.md`

The original product brief. Aspirational — the Android app is the actual implementation of the live-spoofing vision.

## End-to-end flow (the product)

1. Use `tools/gpx_build.py` to synthesize a plausible timed GPX (or bring your own).
2. Transfer the GPX to your phone.
3. Open the **Strava Spoof** Android app, import the GPX, tap **Start Spoofing**.
4. Open the official **Strava** app and start a Run.
5. When the GPX ends the spoof app holds the last point — stop Strava, then stop the spoof.
6. Strava uploads the spoofed activity.
