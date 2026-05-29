# GPS Simulator

## Goal

Simulate GPS data on the phone so an activity-tracking app (running, cycling, walking) records a route as if the user moved through it — without the user going anywhere.

## Target Users

* Anyone whose fitness/tracking app records the device's GPS

---

## Core MVP Features

### 1. Route Playback

Upload:
* GPX file

### 2. User starts the activity in their tracking app; the GPS Simulator feeds the OS the simulated GPS in real time.

---

## Tech Stack

Frontend:

* Native Android (Kotlin + Jetpack Compose)

Backend:

* Node.js + Express + TypeScript

Storage:

* PostgreSQL — GPX files persisted per user

Auth:

* Firebase Authentication (email/password, Google planned)

---

## User Flow

Sign in

↓

Pick / upload a GPX file in the library

↓

Start the simulation (the app starts emitting mock GPS)

↓

Open the tracking app and start recording

↓

Observe the tracking app's behaviour

↓

When the GPX ends, stop the recording then stop the simulator

---

## Success Metrics

* User runs first simulation < 2 minutes after install
* Route playback works reliably end-to-end
* Reproducible testing sessions

The user can pick from a list of GPX files they've uploaded.
