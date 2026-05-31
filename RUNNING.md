# Running GPS Simulator end-to-end

Everything you need, in order, to feed simulated GPS to your activity-tracking app.

---

## 1. Prerequisites (your computer)

- **Android Studio Hedgehog (2023.1.1)** or newer — https://developer.android.com/studio
  Ships with JDK 17 and the Android SDK. Do not use a system JDK 8/11.
- **A USB cable** to plug your Android phone into your computer.
- **Python 3.9+** (only if you want to generate fresh GPX files via `tools/gpx_build.py`).

## 2. Prerequisites (your Android phone)

- Android 8.0 (API 26) or newer.
- USB cable that supports data (not just charging).

### Enable Developer Options + USB debugging

1. Settings → **About phone** → tap **Build number** seven times. You'll see "You are now a developer."
2. Settings → **System → Developer options** → turn on **USB debugging**.
3. Plug the phone into your computer. A prompt appears on the phone: **Allow USB debugging?** → check "Always allow" → tap **Allow**.

## 2.5 Google OAuth setup (required for login)

The app gates access behind a Google Sign-In screen. No Firebase. Auth is done directly through Google Cloud OAuth — two OAuth clients (Web + Android) in a Google Cloud project, that's it.

### 2.5.1 Create / pick a Google Cloud project

1. Go to https://console.cloud.google.com → top bar → project dropdown → **New project** (or pick an existing one). Name it e.g. `gps-simulator`. Save the **Project ID** (Cloud auto-generates one).

### 2.5.2 Configure the OAuth consent screen

Required before you can create any OAuth client.

1. Console → **APIs & Services → OAuth consent screen**.
2. **User Type**: **External** (works for any Google account) → **Create**.
3. Fill the required fields: App name (e.g. `GPS Simulator`), User support email (your email), Developer contact email. The rest can stay blank for now. **Save and continue**.
4. **Scopes** screen → just click **Save and continue** (we only use the default `openid`, `email`, `profile`).
5. **Test users** → add your own Google email so you can sign in while the app is in "Testing" mode. **Save and continue** → **Back to dashboard**.

### 2.5.3 Create the Web OAuth client (the one both Android and backend need)

This single Web client ID is the source of truth — it's what the Android app passes as `setServerClientId(...)` AND what the backend uses as the token audience.

1. Console → **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. **Application type**: **Web application**.
3. Name: `GPS Simulator – Web`.
4. **Authorized JavaScript origins** and **Authorized redirect URIs**: leave both blank (we only need the client ID for ID token verification, not the OAuth redirect flow).
5. **Create**. Copy the **Client ID** that ends in `.apps.googleusercontent.com`. You'll put this:
   - in `~/.gradle/gradle.properties` as `google.web.client.id=...` (Android build)
   - on Render as `GOOGLE_OAUTH_WEB_CLIENT_ID=...` (backend env)

### 2.5.4 Create the Android OAuth client (authorizes your app to use the Web client)

The Android app's identity must be registered for Google to issue tokens to it. You don't use this client ID in code — Google just checks it exists.

1. Console → **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. **Application type**: **Android**.
3. Name: `GPS Simulator – Android`.
4. **Package name**: `com.gpssimulator.app` (must match exactly).
5. **SHA-1 certificate fingerprint**: get yours:
   ```
   # Debug (Android Studio's auto-generated keystore):
   keytool -keystore ~/.android/debug.keystore -list -v \
     -storepass android -keypass android -alias androiddebugkey | grep SHA1:
   ```
   Paste the hex string. **Create**.
6. Once you have a release keystore (`~/.strava-spoof-release.jks`), repeat with **+ ADD FINGERPRINT** and the release SHA-1. You can have many.

Two clients now exist in the project: the Web one and the Android one. The Web client ID is the one you use in code; the Android client just unlocks Android requests for it.

---

## 2.6 Backend setup (required — GPX files live in Postgres)

The Android app no longer stores GPX files locally. It uploads to a small Node/Express backend backed by Postgres, scoped by your Google account. Bring up the backend on the same machine you're developing from.

### 2.6.1 Postgres via Docker

If you don't already have Postgres:

```
docker run -d --name gps-simulator-pg \
  -e POSTGRES_USER=gps_simulator \
  -e POSTGRES_PASSWORD=gps_simulator \
  -e POSTGRES_DB=gps_simulator \
  -p 5432:5432 \
  postgres:16

# Verify
docker exec -it gps-simulator-pg psql -U gps_simulator -c "select 1"
```

Stop/start later with `docker stop gps-simulator-pg` / `docker start gps-simulator-pg`. Data lives in the container's volume until you `docker rm` it.

### 2.6.2 Backend env + first run

The backend verifies Google ID tokens that the Android app sends. It needs the Web client ID from §2.5.3 — same value the Android app uses.

```
cd backend
cp .env.example .env
```

Edit `.env`:

```
DATABASE_URL=postgresql://gps_simulator:gps_simulator@localhost:5432/gps_simulator
GOOGLE_OAUTH_WEB_CLIENT_ID=000000000000-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
PORT=4000
MAX_UPLOAD_BYTES=10485760
```

Then:

```
npm install
npm run migrate           # creates the gpx_files table; idempotent
npm run dev               # tsx watch; listens on PORT (default 4000)
```

You should see:

```
[migrate] applying 001_init.sql
[migrate] up to date (1 files seen, 0 previously applied)
[server] listening on http://0.0.0.0:4000
```

Sanity check from another terminal:

```
curl http://localhost:4000/health
# {"ok":true}
```

### 2.6.3 Point the Android app at the backend

The Android build reads the API base URL from a Gradle property `api.base.url` (default `http://10.0.2.2:4000`, which is the emulator-to-host shortcut).

For a real device on the same Wi-Fi, find your dev machine's LAN IP:

```
# macOS:
ipconfig getifaddr en0     # e.g. 192.168.1.42

# Linux:
# hostname -I | awk '{print $1}'

# Windows:
# ipconfig | findstr IPv4
```

Then either:

- **Add to `~/.gradle/gradle.properties`** (applies globally):
  ```
  api.base.url=http://192.168.1.42:4000
  ```
- **Or pass at build time**:
  ```
  ./gradlew assembleDebug -Papi.base.url=http://192.168.1.42:4000
  ```
- **Or hardcode** in `android/gradle.properties` (committed; only do this in a personal branch):
  ```
  api.base.url=http://192.168.1.42:4000
  ```

Make sure your dev machine's firewall allows inbound TCP/4000 on that interface, and the phone is on the same Wi-Fi.

The Android build ALSO needs the Web client ID from §2.5.3. Add to `~/.gradle/gradle.properties`:

```
google.web.client.id=000000000000-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
```

(Same value you put in the backend's `.env` as `GOOGLE_OAUTH_WEB_CLIENT_ID`. They MUST match — that's how the backend confirms the token was issued for this app.)

---

## 3. Build and install the app

You have two paths. **Path A (Android Studio)** is recommended the first time. **Path B (command line)** is faster once your machine is set up.

### 3.0 Confirm your phone is reachable

In a terminal:

```
# Find adb (Android Studio installs it here)
# macOS:
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
# Linux:
# export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
# Windows (PowerShell):
# $env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"

adb version
adb devices
```

`adb devices` must show your phone with status `device`:

```
List of devices attached
2B011XYZ12345678    device
```

If you see `unauthorized`, look at the phone — there's a "Allow USB debugging?" dialog waiting; tap **Always allow** → **Allow**. If you see nothing at all:

```
adb kill-server
adb start-server
adb devices
```

Replug the USB cable, and on the phone swipe down the notification shade and tap the USB notification → switch to **File Transfer** (or **MTP**).

---

### Path A — Android Studio (recommended first time)

1. **Open the project**
   - Launch **Android Studio**.
   - Welcome screen → click **Open** (or if a project is already open: **File → Open…**).
   - Navigate to this repo and select the **`android/`** directory (not the repo root, not a file inside it).
   - Click **OK**. If it asks "Trust and Open Project?" → click **Trust Project**.

2. **Confirm the Gradle JDK is 17** (Android Studio Hedgehog and newer default to this already — most users just verify and move on)

   The app's `build.gradle.kts` pins both `sourceCompatibility` and `jvmTarget` to **17**, so Gradle must run on JDK 17. Newer (21) usually works but may print warnings; older (11) will fail with `Unsupported class file major version`.

   Open the setting:
   - macOS: **Android Studio → Settings…**  →  search box top-left: type `gradle jdk`  →  click the highlighted result.
   - Windows/Linux: **File → Settings…**  →  search `gradle jdk`.

   Or navigate manually: **Build, Execution, Deployment → Build Tools → Gradle**.

   In the **Gradle JDK** dropdown, the value should be one of:
   - **jbr-17** (the JetBrains Runtime bundled with Android Studio — preferred)
   - **Embedded JDK (Android Studio default)** — same thing under an older label
   - Any **`17 …`** entry (e.g. `Oracle OpenJDK 17.0.x`, `Eclipse Temurin 17`)

   If it shows anything other than 17 (e.g. `11 …`, `21 …`, or `<No SDK>`):
   - Click the dropdown → pick the **jbr-17** entry near the top → **Apply** → **OK**.
   - If no 17 entry exists in the dropdown at all, use one of the two fallbacks below.

   **Fallback A — point Gradle at the JBR that ships inside Android Studio.** This always exists; you just bypass the dropdown.

   Edit `android/gradle.properties` and add a line:

   ```
   # macOS:
   org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home

   # Linux (Android Studio installed via the tarball):
   # org.gradle.java.home=/opt/android-studio/jbr

   # Windows:
   # org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
   ```

   Then **File → Sync Project with Gradle Files**. The dropdown will now show this path as the active JDK.

   **Fallback B — install JDK 17 system-wide, then restart Android Studio.** It auto-detects installed JDKs on launch and adds them to the dropdown.

   ```
   # macOS (Homebrew):
   brew install --cask temurin@17

   # Linux (Debian/Ubuntu):
   # sudo apt install -y temurin-17-jdk
   # (add the Adoptium repo first if needed: https://adoptium.net/installation/linux/)

   # Windows: download the MSI from https://adoptium.net and run it.
   ```

   Quit Android Studio fully and reopen the project. The dropdown now lists **Eclipse Temurin 17** (or similar). Select it → **Apply** → **OK**.

   **Verify from a terminal** (optional):
   ```
   cd android
   ./gradlew -v
   ```
   The output should include `JVM:  17.x.x` under "Daemon JVM". If it says 11 or 21, the dropdown above didn't take effect (or you're using a different shell `JAVA_HOME`).

3. **Trigger Gradle sync**
   - Android Studio normally starts it automatically. If not: **File → Sync Project with Gradle Files** (or click the elephant icon in the toolbar).
   - First sync downloads Gradle 8.7 wrapper + AGP 8.5.2 + Compose libs (~5–10 minutes, ~500 MB).
   - Watch the bottom **Build** tab for progress. Wait for **"BUILD SUCCESSFUL"** or **"Sync finished"**.

4. **Install missing SDK pieces if prompted**
   - Sync may surface a banner: *"Install missing platform(s) and sync project"* or *"Install Build Tools"*. Click the link.
   - If not prompted but you want to verify: **Tools → SDK Manager** → check:
     - **SDK Platforms** tab → **Android 14.0 (UpsideDownCake)** API 34 → check it → **Apply**.
     - **SDK Tools** tab → **Android SDK Build-Tools 34.0.0** → check it → **Apply**.
   - Accept the license dialog → **Next** → **Finish**.

5. **Pick your device**
   - Top center of the window: a dropdown showing **"No devices"** or a device name. Click it.
   - Your phone appears as e.g. **`Pixel 7 (USB)`**. Select it.
   - If your phone is missing: re-run `adb devices` in a terminal; it must show `device` status.

6. **Run**
   - Click the green **▶ Run 'app'** button (top right of the toolbar). Shortcut: **Ctrl/Cmd + R**.
   - Android Studio builds, installs, and launches `GPS Simulator` on the phone.
   - First build is slow (~2 min). Subsequent rebuilds are seconds.
   - On the phone, the app launches to the empty Library screen with the **+** button at bottom right.

---

### Path B — command line

Use this once you've done Path A at least once (it ensures the SDK + Gradle wrapper are present).

1. **Set the SDK path** so Gradle can find the Android SDK:

   ```
   # macOS:
   cat > android/local.properties <<EOF
   sdk.dir=$HOME/Library/Android/sdk
   EOF

   # Linux:
   # cat > android/local.properties <<EOF
   # sdk.dir=$HOME/Android/Sdk
   # EOF
   ```

2. **Verify JDK 17 is on PATH**:

   ```
   java -version
   # Must report "openjdk version \"17.*\"". If not, point JAVA_HOME at JDK 17:
   # macOS with Android Studio embedded JBR:
   # export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   # export PATH="$JAVA_HOME/bin:$PATH"
   ```

3. **Build the debug APK** (uses the Gradle wrapper that Android Studio created during Path A):

   ```
   cd android
   ./gradlew assembleDebug
   ```

   First run downloads dependencies. End-of-output should be `BUILD SUCCESSFUL`.

4. **Install on the phone**:

   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `-r` reinstalls over an existing copy. Expected output: `Success`.

5. **Launch the app**:

   ```
   adb shell am start -n com.gpssimulator.app/.MainActivity
   ```

6. **Watch logs** (in another terminal, optional but useful):

   ```
   adb logcat -v color --pid=$(adb shell pidof com.gpssimulator.app)
   ```

   Filter to just our app's logs.

---

### If something fails

| Error | Cause | Fix |
|---|---|---|
| `adb: command not found` | platform-tools not on PATH | Re-run the `export PATH=...` from step 3.0 |
| `unauthorized` in `adb devices` | Phone hasn't accepted RSA key | Tap "Always allow" on the phone, re-run `adb devices` |
| Gradle sync: `Unsupported class file major version 65` | System JDK is too new (21+) | Set Gradle JDK to **Embedded JDK 17** (step A.2) |
| Gradle sync: `Could not find SDK location` | `local.properties` missing | Open in Android Studio once, OR run step B.1 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on `adb install` | Old signed version installed | `adb uninstall com.gpssimulator.app` then retry |
| `INSTALL_FAILED_USER_RESTRICTED` | MIUI / OEM blocks USB install | On phone: Dev Options → enable **USB debugging (Security settings)** + **Install via USB** |
| App installs but immediately crashes on launch | Usually missing API 34 platform | Open SDK Manager → install Android 14 (API 34) |

## 4. One-time device setup (do this once per device)

This is the actual privilege grant that makes simulation work. Without it, the app's Start button stays disabled and tells you so.

1. On the phone: Settings → **System → Developer options** → scroll to the **Debugging** section → **Select mock location app** → tap → choose **GPS Simulator** → **OK**.
   - If you don't see GPS Simulator in the list, the app isn't installed yet — go back to step 3.
   - A shortcut from a terminal: `adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS` opens that screen directly.

2. Launch **GPS Simulator** → tap the **+** → pick any GPX → tap **Open** on the row. The Replay screen shows. If it prompts:
   - **Allow GPS Simulator to access this device's location?** → **While using the app** (or **Only this time**, but you'll see it again).
   - **Allow GPS Simulator to send you notifications?** (Android 13+) → **Allow**.

3. If the "Setup required" card still shows, tap **I did this — re-check**. The card disappears and **Start Simulation** turns enabled.

### Verify from the command line (optional)

```
# Should print: MOCK_LOCATION: allow
adb shell appops get com.gpssimulator.app MOCK_LOCATION

# Should include both lines as `granted=true`
adb shell dumpsys package com.gpssimulator.app | grep -E "ACCESS_FINE_LOCATION|POST_NOTIFICATIONS"
```

## 5. Get a GPX file into your library

You upload GPX files **from the app itself** — the backend stores them in Postgres, scoped to your Firebase user. You can rename them in-app later by tapping the pencil icon on the row. v1 rejects GPX without `<time>` data on every trackpoint.

Make sure a GPX file is reachable from the phone's file picker. Easiest path:

### Option A — use a sample (fastest)

The repo includes a real run at `tools/run_gpx_files/morning_run_timed.gpx`. Copy it to the phone's Downloads folder:

```
adb push tools/run_gpx_files/morning_run_timed.gpx /sdcard/Download/
```

(`adb` is bundled with Android Studio; full path is `~/Library/Android/sdk/platform-tools/adb` on macOS.)

Then in the app: **+** button → **Downloads** → pick the file. It uploads to the backend; the row appears with distance / duration / point count read from the server.

### Option B — generate a fresh route

From the repo root, in a Python virtualenv:

```
cd tools
pip install -r requirements.txt
python gpx_build.py \
  -s 12.97,77.59 \
  -t 2026-05-30T06:30:00+05:30 \
  -p 5:30 \
  --loop --distance 5 \
  -o my_run.gpx
adb push my_run.gpx /sdcard/Download/
```

That produces a 5 km loop starting at the given coord, 5:30/km pace. See `tools/README.md` for all the flags (`--end`, `--waypoint`, `--polyline`, etc.).

### Option C — your own GPX

Any GPX from a route builder (Komoot, plotaroute.com, etc.) If it lacks timestamps, run it through `tools/gpx_add_time.py` first:

```
python tools/gpx_add_time.py -i route.gpx -s 2026-05-30T06:30:00+05:30 -p 5:30
```

## 6. Run the simulation

1. Open **GPS Simulator** on the phone (icon: orange tile with a pin). On first launch you'll see the **Login screen** — pick one:
   - **Sign up** tab → enter an email + password (≥6 chars) → **Create account**, *or*
   - **Sign in** tab → existing email + password → **Sign in**.
   - (**Continue with Google** is greyed out for now; coming back in v0.4.)

   Once authenticated, the app remembers you until you tap the sign-out icon in the top-right of the library screen.

2. Tap the orange **+** floating button (bottom right) → the system file picker opens → tap the ☰ menu → **Downloads** → pick your GPX file (`my_run.gpx` or `morning_run_timed.gpx`) → tap it.
   - A toast says *"Uploaded …"*. The app sends the bytes to the backend, which parses and stores them in Postgres.
   - The new row appears with distance / duration / point count returned by the server.
   - Use the **pencil** icon to rename a file. Renames are sent to the backend (`PATCH /gpx/:id`); the new name is what shows up next time you load.
   - Use the **trash** icon to delete it from your account.
   - The circular **↻** icon in the top bar re-fetches the list.
3. Tap **Open** on the row. The app downloads the GPX bytes to a local cache (then reuses the cache on subsequent opens) and switches to the Replay screen.
4. Confirm the Status card says **"Status: idle"** and the bottom button reads **Start Simulation** in green.
5. Tap **Start Simulation**.
   - The status card turns blue/primary and reads **"Status: simulating — Elapsed 00:00 / mm:ss"**.
   - A persistent notification appears: *"GPS simulation active"*.
   - The button changes to a red **Stop Simulation**.
6. **Now open your activity-tracking app** → tap its Record / Start Activity button → pick the activity type (Run, Ride, Walk…) → tap Start.
   - The tracking app picks up the simulated coordinates within ~2–5 seconds. The map pans to the GPX start.
7. Let it run. You can lock the phone — the foreground service keeps emitting at 1 Hz. The tracking app records as if you were really moving.
8. When the GPX ends:
   - GPS Simulator's status flips to **"Holding last point — stop the recording in your tracking app, then stop here."**
   - In your **tracking app**: tap its lock-screen notification → tap **Pause** → **Finish** → fill in the title → **Save Activity**.
9. Back in **GPS Simulator**: tap the red **Stop Simulation**. Status returns to "idle". The mock GPS provider is removed and your real GPS comes back.

### Watch live from a terminal (optional)

In two side-by-side terminals while the simulation is running:

```
# Terminal 1 — our app's logs
adb logcat -v color --pid=$(adb shell pidof com.gpssimulator.app)

# Terminal 2 — current LocationManager state
watch -n 2 "adb shell dumpsys location | sed -n '/gps provider:/,/passive provider:/p'"
```

The `dumpsys` block updates each second with the new lat/lon that the service just emitted.

## 7. Verifying the simulation is actually working

Three quick checks. Use any one.

**A. Install GPS Test (easiest)**
- Play Store → install any free **GPS Test** app (e.g. "GPS Test" by Chartcross).
- Open it while GPS Simulator is running. Lat/lon and the map pin should match the GPX coordinates, not your physical location.
- If it shows your real location, the mock-location selection in step 4 didn't stick — re-do it and confirm with `adb shell appops get com.gpssimulator.app MOCK_LOCATION`.

**B. From a terminal (no extra app needed)**
```
# Show the latest fix from the gps provider
adb shell dumpsys location | grep -A 2 "last location"
```
The `Location` line should reflect coordinates from your GPX, and you'll see `mock=true`.

**C. Native Android location dump every second**
```
adb shell "while true; do dumpsys location | grep -E 'last location.*gps' ; sleep 1; done"
```
Watch the lat/lon advance as GPS Simulator walks the file.

## 8. Turning it off when you're done

Don't leave the app installed long-term as your mock provider; it interferes with normal apps that need GPS. To revert fully:

1. **Stop any active session** in GPS Simulator: tap **Stop Simulation**. Confirm the notification disappears.
2. **Unselect the mock app**: Settings → System → Developer options → **Select mock location app** → **None**.
3. **(Optional) Uninstall the app**:
   ```
   adb uninstall com.gpssimulator.app
   ```
4. **Verify your real GPS is back**: open Google Maps and tap the blue "my location" dot — it should re-center on where you actually are.
5. **(Optional) Turn off Developer Options** entirely: Settings → System → Developer options → toggle **Use developer options** off.

---

## Common errors

| Symptom | Cause | Fix |
|---|---|---|
| Start button stays disabled, "Mock location not allowed" | App not selected in Dev Options | Step 4.1 |
| Import fails: "trkpt missing `<time>`" | Untimed GPX | Run through `gpx_add_time.py` first |
| Tracking app records 0 m / never moves | Mock app picked but real GPS still on | Force-stop the tracking app, re-open, restart simulation, then restart its recording |
| Simulated run shows huge speed spikes | GPX has gaps or out-of-order timestamps | Re-generate with `gpx_build.py` |
| Notification gone, service died | OS killed background — rare on a foreground service | Reopen the app and tap Start again |

## Important notes

- **Don't ship this to the Play Store.** Mock-location apps violate policy and will be rejected.
- **Don't run two simulators at once.** Whichever is "Select mock location app" wins.
- **iOS is not supported.** There is no equivalent public API on iOS.
- **Be sensible.** This is for testing / fun; simulation on segments or paid challenges in any fitness app is against that app's TOS.
