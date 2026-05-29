# Deploying GPS Simulator

Three things ship:

| Piece | Where | Why this pick |
|---|---|---|
| Postgres | **Neon** (https://neon.tech) — free tier | Serverless, no time-limited free trial, generous storage. |
| Backend | **Render** (https://render.com) — free Web Service | Auto-deploy from GitHub, free HTTPS, no credit card. Free tier sleeps after 15 min idle — fine for a personal tool. |
| Android APK | Sideload via **GitHub Releases** or **Firebase App Distribution** | The app violates Play Store policy on mock-location, so it can't ship there. |

Firebase Auth is already cloud-hosted — nothing to deploy.

If you want always-on backend (no cold start), swap Render for **Fly.io** — instructions at the end.

---

## 1. Provision Postgres on Neon

1. Sign in at https://neon.tech.
2. **Create project** → name `gps-simulator` → Postgres 16 → Region: pick whichever's closest to where Render runs (US East is the safe default for the free Render region).
3. From the project dashboard, **Connection details** → copy the **Connection string** that starts with `postgresql://`. Looks like:
   ```
   postgresql://user:pass@ep-xxx.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```
   Save it — you'll paste it into Render in §3.
4. The `sslmode=require` suffix is important. `pg` honors it automatically; no extra config needed.

Verify locally from a terminal (optional):
```
psql 'postgresql://user:pass@ep-xxx.us-east-2.aws.neon.tech/neondb?sslmode=require' -c "select version()"
```

---

## 2. Get the Firebase service account JSON

The backend verifies Firebase ID tokens, which needs admin credentials.

1. Firebase console → ⚙️ → **Project settings → Service accounts** tab.
2. **Generate new private key** → **Generate key** → JSON file downloads.
3. Open it in a text editor. You'll paste its **entire contents** as one env var on Render in §3.

Do **not** commit this file anywhere.

---

## 3. Deploy the backend on Render

### 3.1 Push the repo to GitHub

If you haven't yet:
```
gh repo create strava_hack_tools --private --source=. --remote=origin --push
# or do it through the GitHub UI and `git remote add origin ...; git push -u origin master`
```

### 3.2 Create the Web Service

1. https://dashboard.render.com → **New + → Web Service** → connect your GitHub → pick the `strava_hack_tools` repo.
2. Fill in:
   - **Name**: `gps-simulator-backend` (becomes part of the URL).
   - **Region**: same as your Neon region.
   - **Branch**: `master`.
   - **Root Directory**: `backend`
   - **Runtime**: `Node`.
   - **Build Command**: `npm install && npm run build`
   - **Start Command**: `node dist/index.js`
   - **Instance Type**: **Free**.

### 3.3 Environment variables

Render → your service → **Environment** tab → **Add Environment Variable** for each:

| Key | Value |
|---|---|
| `DATABASE_URL` | The Neon connection string from §1 |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | The **entire contents** of the service-account JSON, on one line (paste it; Render's textarea handles newlines fine) |
| `PORT` | `4000` |
| `MAX_UPLOAD_BYTES` | `10485760` |
| `NODE_VERSION` | `20` (forces Node 20+; matches the local dev version) |

### 3.4 First deploy

- Render auto-starts the build after env vars are saved. Watch the **Logs** tab.
- Expected output:
  ```
  [migrate] applying 001_init.sql
  [migrate] up to date (1 files seen, 0 previously applied)
  [server] listening on http://0.0.0.0:4000
  ```
- The service gets a public URL like `https://gps-simulator-backend.onrender.com`. **Copy it.**

### 3.5 Sanity check

```
curl https://gps-simulator-backend.onrender.com/health
# {"ok":true}

# Token-required endpoint should reject without auth:
curl https://gps-simulator-backend.onrender.com/gpx
# {"error":"missing Authorization: Bearer <id-token>"}
```

If `/health` is slow on the first hit (~5–10 sec), that's Render's free-tier cold start. Subsequent calls are fast.

---

## 4. Point the Android app at production

The Android build reads `api.base.url`. For a release build, you have three options; pick one.

**Option A — at build time (recommended for CI):**
```
cd android
./gradlew assembleRelease -Papi.base.url=https://gps-simulator-backend.onrender.com
```

**Option B — per-machine (won't be committed):**
Add to `~/.gradle/gradle.properties`:
```
api.base.url=https://gps-simulator-backend.onrender.com
```

**Option C — committed:**
Edit `android/gradle.properties` and add the line above. Only do this on a release branch you don't share.

The release build over HTTPS doesn't need cleartext. You can tighten security by setting `android:usesCleartextTraffic="false"` for release. (Out of scope for v1; the dev manifest leaves it on.)

---

## 5. Build a signed release APK

Play-Store-or-not, Android requires a release APK to be signed by a release keystore (the debug keystore won't install on most users' devices). One-time setup:

### 5.1 Generate the keystore

```
keytool -genkey -v \
  -keystore $HOME/.gps-simulator-release.jks \
  -keyalg RSA -keysize 2048 -validity 25000 \
  -alias gps-simulator
```

Pick a strong password and keep the file. **If you lose it, you can never push an update that the same users can install** — they'd have to uninstall first.

### 5.2 Wire it into Gradle

Add to `~/.gradle/gradle.properties` (NOT `android/gradle.properties` — keep secrets out of the repo):

```
GPS_SIMULATOR_KEYSTORE=/Users/you/.gps-simulator-release.jks
GPS_SIMULATOR_KEYSTORE_PASSWORD=...
GPS_SIMULATOR_KEY_ALIAS=gps-simulator
GPS_SIMULATOR_KEY_PASSWORD=...
```

Then add a `signingConfigs` block to `android/app/build.gradle.kts` and attach it to `buildTypes.release`:

```kotlin
android {
    // ...existing...

    signingConfigs {
        create("release") {
            val ks = (project.findProperty("GPS_SIMULATOR_KEYSTORE") as String?)?.let { file(it) }
            if (ks != null && ks.exists()) {
                storeFile = ks
                storePassword = project.findProperty("GPS_SIMULATOR_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("GPS_SIMULATOR_KEY_ALIAS") as String?
                keyPassword = project.findProperty("GPS_SIMULATOR_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

(I left this out of the committed build script to avoid wedging your local dev. Add it once you're ready to ship.)

### 5.3 Add the release SHA-1 to Firebase

The release keystore has a different SHA-1 than debug. Email/password sign-in doesn't care, but if you later re-enable Google Sign-In it does:

```
keytool -list -v -keystore $HOME/.gps-simulator-release.jks -alias gps-simulator | grep SHA1:
```

Paste that into Firebase console → Project settings → Your Android app → **Add fingerprint**. Re-download `google-services.json` and replace `android/app/google-services.json` (still gitignored).

### 5.4 Build it

```
cd android
./gradlew assembleRelease -Papi.base.url=https://gps-simulator-backend.onrender.com
ls -lh app/build/outputs/apk/release/app-release.apk
```

This is the file you distribute.

---

## 6. Distribute the APK

### Option A — GitHub Releases (free, simple)

```
gh release create v0.3.0 \
  android/app/build/outputs/apk/release/app-release.apk \
  --title "v0.3.0" --notes "Initial release"
```

Send your testers the download link from the release page. They:
1. Download the APK on the phone's browser.
2. Tap it → Android prompts to allow install from this source → **Settings** → enable → back → **Install**.

### Option B — Firebase App Distribution (free, sends install link via email)

1. Firebase console → **App Distribution** → onboard your Android app.
2. Install the CLI: `npm install -g firebase-tools` → `firebase login`.
3. Get your Firebase **App ID** from Project settings → General → Your apps (the `1:1234:android:abcd…` one).
4. Upload + send:
   ```
   firebase appdistribution:distribute \
     android/app/build/outputs/apk/release/app-release.apk \
     --app 1:1234:android:abcd... \
     --groups testers \
     --release-notes "v0.3.0"
   ```
5. Add tester emails in the console; they get an email with install instructions.

---

## 7. Verify the live stack end-to-end

On a real phone with the new APK installed:

1. Open the app → sign up with a fresh email + password.
2. Backend logs (Render dashboard) should show `POST /gpx` requests when you upload.
3. Postgres (Neon dashboard → SQL editor) should show rows in `gpx_files`:
   ```
   SELECT user_uid, name, distance_m, duration_s, size_bytes
   FROM gpx_files
   ORDER BY created_at DESC
   LIMIT 5;
   ```
4. Do the §4–§8 simulation flow from `RUNNING.md` and confirm your tracking app records the activity.

---

## 8. Known caveats

- **Render free tier sleeps after 15 min idle.** First request after sleep takes ~5–10 sec. If that annoys you, upgrade to Render's Starter plan (~$7/mo) or switch to Fly.io.
- **Cleartext HTTP is on in the manifest** — fine for dev with `10.0.2.2:4000`, unnecessary now that production is HTTPS. To tighten:
  ```xml
  <application
      android:usesCleartextTraffic="false"
      ...>
  ```
- **No rate limiting** in the backend. For a personal-use app it's fine. If you share it more widely, put `express-rate-limit` in front of the routes.
- **No backup** of Postgres data. Neon's free tier has 7-day point-in-time recovery; that's the floor. For real safety, take a manual `pg_dump` periodically.
- **Mock-location apps violate the Play Store policy.** Don't try to upload to Google Play — sideload only.
- **Be sensible.** Simulated activities on paid challenges or segments are against the host fitness app's TOS.

---

## Alternative: deploy on Fly.io instead of Render

Fly.io doesn't sleep, has more generous always-on free allowance, but requires the CLI and a credit card.

```
brew install flyctl   # or `curl -L https://fly.io/install.sh | sh`
flyctl auth signup    # or login

cd backend
flyctl launch --name gps-simulator-backend --no-deploy
# Answer the prompts: pick a region, decline Postgres (we use Neon), decline Upstash Redis.

flyctl secrets set \
  DATABASE_URL='postgresql://user:pass@ep-xxx...neon.tech/neondb?sslmode=require' \
  FIREBASE_SERVICE_ACCOUNT_JSON="$(cat ~/Downloads/firebase-service-account.json)" \
  MAX_UPLOAD_BYTES=10485760

flyctl deploy
flyctl status   # → URL like https://gps-simulator-backend.fly.dev
```

`fly launch` writes a `fly.toml`. Make sure it has:
```
[build]
  [build.args]
    NODE_VERSION = "20"

[http_service]
  internal_port = 4000
  force_https = true
```

Then point Android at `https://gps-simulator-backend.fly.dev` per §4 and continue from §5.
