# GPS Simulator — Backend

Node.js + Express + Postgres backend that stores per-user GPX files. The Android app talks to it via REST, authenticated with Firebase ID tokens.

## Endpoints

All require `Authorization: Bearer <firebase-id-token>`.

| Method | Path | Body | Response |
|---|---|---|---|
| `GET`    | `/gpx`               | —                              | `{ files: GpxFile[] }` |
| `POST`   | `/gpx`               | multipart: `file`, `name?`     | `{ file: GpxFile }` |
| `GET`    | `/gpx/:id/download`  | —                              | raw `application/gpx+xml` |
| `PATCH`  | `/gpx/:id`           | `{ "name": "new.gpx" }`        | `{ file: GpxFile }` |
| `DELETE` | `/gpx/:id`           | —                              | `204` |

`GpxFile = { id, name, distanceMeters, durationSeconds, pointCount, sizeBytes, createdAt, updatedAt }`.

Health: `GET /health` (no auth) → `{ ok: true }`.

## Storage model

- One Postgres table, `gpx_files`. Bytes stored in a `BYTEA` column.
- Rows scoped by Firebase UID (`user_uid`). No separate users table.
- Indexed by `(user_uid, created_at DESC)` for the list endpoint.

GPX parsing happens server-side at upload time; distance/duration/point count are computed and stored alongside the bytes.

## Run

See [RUNNING.md §2.6](../RUNNING.md) in the repo root for the full setup walkthrough (Postgres via Docker, Firebase service account JSON, env vars).

```
npm install
cp .env.example .env       # fill in DATABASE_URL + GOOGLE_APPLICATION_CREDENTIALS
npm run migrate            # apply migrations/*.sql
npm run dev                # tsx watch on port 4000
```

## Layout

```
backend/
  package.json
  tsconfig.json
  .env.example
  migrations/
    001_init.sql
  src/
    index.ts                 # Express bootstrap, mounts /gpx
    lib/
      db.ts                  # pg pool
      migrate.ts             # one-pass migration runner; idempotent
      firebase.ts            # firebase-admin init (service account)
      auth.ts                # requireAuth middleware → req.userUid
      gpx.ts                 # tiny GPX parser + Haversine summary
    routes/
      gpx.ts                 # CRUD endpoints
```
