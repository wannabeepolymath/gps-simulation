# Running Strava Spoof end-to-end

Everything you need, in order, to spoof a Strava run.

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

## 2.5 Firebase setup (required for login)

The app gates access behind a login screen that supports **Email/Password** and **Sign in with Google**, backed by **Firebase Authentication**. The build fails without a real `google-services.json` in `android/app/`. Do this once.

### 2.5.1 Create a Firebase project

1. Go to https://console.firebase.google.com → **Add project** → name it e.g. `strava-spoof` → continue. You can disable Google Analytics — it's not needed.

### 2.5.2 Register the Android app

1. In the Firebase console, **Project Overview → Add app → Android** icon.
2. **Android package name**: `com.strava.spoof` (must match exactly).
3. **Debug signing certificate SHA-1**: needed for Google Sign-In. Get it from your local machine:
   ```
   # macOS / Linux:
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
     -storepass android -keypass android | grep "SHA1:"

   # Windows (PowerShell):
   # keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" `
   #   -alias androiddebugkey -storepass android -keypass android | Select-String SHA1
   ```
   Paste the hex string (e.g. `12:34:AB:…:EF`) into the Firebase form. Click **Register app**.
4. **Download `google-services.json`** → move it to **`android/app/google-services.json`**. (This file is gitignored — never commit it.)
5. Skip the "Add Firebase SDK" and "Verify installation" wizard steps — `build.gradle.kts` is already set up.

### 2.5.3 Enable the auth providers

In the Firebase console: **Build → Authentication → Get started**, then on the **Sign-in method** tab:

1. **Email/Password** → click → toggle **Enable** → **Save**.
2. **Google** → click → toggle **Enable** → set a project support email → **Save**.

### 2.5.4 Verify the Web OAuth client exists

Google Sign-In on Android uses a **Web client ID**, not the Android client ID (counter-intuitive, but that's how it works).

1. Firebase Console → ⚙️ → **Project settings** → **General** tab → scroll to "Your apps".
2. There should be a **Web app** entry. If not: click **Add app → Web (`</>`)** → nickname it → register. (No need to add any SDK; we only need the client ID generated.)
3. **Re-download `google-services.json`** from the Android app card. The fresh file now contains an `oauth_client` entry of `client_type: 3` (web). The google-services Gradle plugin uses it to generate `R.string.default_web_client_id` at build time.

Without this step, email/password works but the "Continue with Google" button errors with *"Google sign-in not configured."*.

---

## 2.6 Backend setup (required — GPX files live in Postgres)

The Android app no longer stores GPX files locally. It uploads to a small Node/Express backend backed by Postgres, scoped by your Firebase user. Bring up the backend on the same machine you're developing from.

### 2.6.1 Postgres via Docker

If you don't already have Postgres:

```
docker run -d --name strava-spoof-pg \
  -e POSTGRES_USER=strava_spoof \
  -e POSTGRES_PASSWORD=strava_spoof \
  -e POSTGRES_DB=strava_spoof \
  -p 5432:5432 \
  postgres:16

# Verify
docker exec -it strava-spoof-pg psql -U strava_spoof -c "select 1"
```

Stop/start later with `docker stop strava-spoof-pg` / `docker start strava-spoof-pg`. Data lives in the container's volume until you `docker rm` it.

### 2.6.2 Firebase Admin service account

The backend verifies Firebase ID tokens that the Android app sends. It needs the project's service account key:

1. Firebase console → ⚙️ → **Project settings → Service accounts** tab.
2. Click **Generate new private key** → **Generate key**. A JSON file downloads.
3. Move it to `backend/firebase-service-account.json`. (Gitignored.)
4. Keep it private — anyone with this file has admin access to your Firebase project.

### 2.6.3 Backend env + first run

```
cd backend
cp .env.example .env
# Edit .env if your Postgres URL or service account path differs.

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

### 2.6.4 Point the Android app at the backend

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
   - Android Studio builds, installs, and launches `Strava Spoof` on the phone.
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
   adb shell am start -n com.strava.spoof/.MainActivity
   ```

6. **Watch logs** (in another terminal, optional but useful):

   ```
   adb logcat -v color --pid=$(adb shell pidof com.strava.spoof)
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
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on `adb install` | Old signed version installed | `adb uninstall com.strava.spoof` then retry |
| `INSTALL_FAILED_USER_RESTRICTED` | MIUI / OEM blocks USB install | On phone: Dev Options → enable **USB debugging (Security settings)** + **Install via USB** |
| App installs but immediately crashes on launch | Usually missing API 34 platform | Open SDK Manager → install Android 14 (API 34) |

## 4. One-time device setup (do this once per device)

This is the actual privilege grant that makes spoofing work. Without it, the app's Start button stays disabled and tells you so.

1. On the phone: Settings → **System → Developer options** → scroll to the **Debugging** section → **Select mock location app** → tap → choose **Strava Spoof** → **OK**.
   - If you don't see Strava Spoof in the list, the app isn't installed yet — go back to step 3.
   - A shortcut from a terminal: `adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS` opens that screen directly.

2. Launch **Strava Spoof** → tap the **+** → pick any GPX → tap **Open** on the row. The Replay screen shows. If it prompts:
   - **Allow Strava Spoof to access this device's location?** → **While using the app** (or **Only this time**, but you'll see it again).
   - **Allow Strava Spoof to send you notifications?** (Android 13+) → **Allow**.

3. If the "Setup required" card still shows, tap **I did this — re-check**. The card disappears and **Start Spoofing** turns enabled.

### Verify from the command line (optional)

```
# Should print: MOCK_LOCATION: allow
adb shell appops get com.strava.spoof MOCK_LOCATION

# Should include both lines as `granted=true`
adb shell dumpsys package com.strava.spoof | grep -E "ACCESS_FINE_LOCATION|POST_NOTIFICATIONS"
```

## 5. Get a GPX file into your library

You upload GPX files **from the app itself** — the backend stores them in Postgres, scoped to your Firebase user. You can rename them in-app later by tapping the pencil icon on the row. v1 rejects GPX without `<time>` data on every trackpoint.

Make sure a GPX file is reachable from the phone's file picker. Easiest path:

### Option A — use a sample (fastest)

The repo includes a real run at `tools/run_gpx_files/Morning_Run_strava_timed.gpx`. Copy it to the phone's Downloads folder:

```
adb push tools/run_gpx_files/Morning_Run_strava_timed.gpx /sdcard/Download/
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

Any GPX from Strava route builder, Komoot, plotaroute.com, etc. If it lacks timestamps, run it through `tools/gpx_add_time.py` first:

```
python tools/gpx_add_time.py -i route.gpx -s 2026-05-30T06:30:00+05:30 -p 5:30
```

## 6. Run the spoof

1. Open **Strava Spoof** on the phone (icon: orange tile with a pin). On first launch you'll see the **Login screen** — pick one:
   - **Sign up** tab → enter an email + password (≥6 chars) → **Create account**, *or*
   - **Sign in** tab → existing email + password → **Sign in**, *or*
   - **Continue with Google** → pick a Google account from the system sheet.

   Once authenticated, the app remembers you until you tap the sign-out icon in the top-right of the library screen.

2. Tap the orange **+** floating button (bottom right) → the system file picker opens → tap the ☰ menu → **Downloads** → pick your GPX file (`my_run.gpx` or `Morning_Run_strava_timed.gpx`) → tap it.
   - A toast says *"Uploaded …"*. The app sends the bytes to the backend, which parses and stores them in Postgres.
   - The new row appears with distance / duration / point count returned by the server.
   - Use the **pencil** icon to rename a file. Renames are sent to the backend (`PATCH /gpx/:id`); the new name is what shows up next time you load.
   - Use the **trash** icon to delete it from your account.
   - The circular **↻** icon in the top bar re-fetches the list.
3. Tap **Open** on the row. The app downloads the GPX bytes to a local cache (then reuses the cache on subsequent opens) and switches to the Replay screen.
4. Confirm the Status card says **"Status: idle"** and the bottom button reads **Start Spoofing** in green.
5. Tap **Start Spoofing**.
   - The status card turns blue/primary and reads **"Status: spoofing — Elapsed 00:00 / mm:ss"**.
   - A persistent notification appears: *"GPS spoofing active"*.
   - The button changes to a red **Stop Spoofing**.
6. **Now open the real Strava app** → tap **Record** (bottom nav) → confirm **Run** is selected → tap the big orange **Start** button.
   - Strava picks up the spoofed coordinates within ~2–5 seconds. The map pans to the GPX start.
7. Let it run. You can lock the phone — the foreground service keeps emitting at 1 Hz. Strava records as if you were really moving.
8. When the GPX ends:
   - Strava Spoof's status flips to **"Holding last point — stop the Strava run, then stop here."**
   - In **Strava**: tap the lock-screen Strava notification → tap **Pause** → **Finish** → fill in the title → **Save Activity**.
9. Back in **Strava Spoof**: tap the red **Stop Spoofing**. Status returns to "idle". The mock GPS provider is removed and your real GPS comes back.

### Watch live from a terminal (optional)

In two side-by-side terminals while the spoof is running:

```
# Terminal 1 — our app's logs
adb logcat -v color --pid=$(adb shell pidof com.strava.spoof)

# Terminal 2 — current LocationManager state
watch -n 2 "adb shell dumpsys location | sed -n '/gps provider:/,/passive provider:/p'"
```

The `dumpsys` block updates each second with the new lat/lon that the service just emitted.

## 7. Verifying the spoof is actually working

Three quick checks. Use any one.

**A. Install GPS Test (easiest)**
- Play Store → install any free **GPS Test** app (e.g. "GPS Test" by Chartcross).
- Open it while Strava Spoof is running. Lat/lon and the map pin should match the GPX coordinates, not your physical location.
- If it shows your real location, the mock-location selection in step 4 didn't stick — re-do it and confirm with `adb shell appops get com.strava.spoof MOCK_LOCATION`.

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
Watch the lat/lon advance as Strava Spoof walks the file.

## 8. Turning it off when you're done

Don't leave the app installed long-term as your mock provider; it interferes with normal apps that need GPS. To revert fully:

1. **Stop any active session** in Strava Spoof: tap **Stop Spoofing**. Confirm the notification disappears.
2. **Unselect the mock app**: Settings → System → Developer options → **Select mock location app** → **None**.
3. **(Optional) Uninstall the app**:
   ```
   adb uninstall com.strava.spoof
   ```
4. **Verify your real GPS is back**: open Google Maps and tap the blue "my location" dot — it should re-center on where you actually are.
5. **(Optional) Turn off Developer Options** entirely: Settings → System → Developer options → toggle **Use developer options** off.

---

## Common errors

| Symptom | Cause | Fix |
|---|---|---|
| Start button stays disabled, "Mock location not allowed" | App not selected in Dev Options | Step 4.1 |
| Import fails: "trkpt missing `<time>`" | Untimed GPX | Run through `gpx_add_time.py` first |
| Strava records 0 m / never moves | Mock app picked but real GPS still on | Force-stop Strava, re-open, restart spoof, then restart Strava recording |
| Spoofed run shows huge speed spikes | GPX has gaps or out-of-order timestamps | Re-generate with `gpx_build.py` |
| Notification gone, service died | OS killed background — rare on a foreground service | Reopen the app and tap Start again |

## Important notes

- **Don't ship this to the Play Store.** Mock-location apps violate policy and will be rejected.
- **Don't run two spoofers at once.** Whichever is "Select mock location app" wins.
- **iOS is not supported.** There is no equivalent public API on iOS.
- **Be sensible.** This is for testing / fun; spoofing on Strava segments or paid challenges is against their TOS.
