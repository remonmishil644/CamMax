# CamMax — screen-off 4K60 recorder for Romio's Samsung S22 Ultra

Open a session in this folder to work on the app. Read this file, then `TASK_CARD.md`.

## What it is
A Kotlin Android app (Camera2 + MediaRecorder, minSdk 29, targetSdk 34). Two launcher icons:
- **CamMax** opens `SettingsActivity` (settings, status, test button, event log).
- **CamMax REC** (green dot) launches the invisible `ToggleActivity`: tap starts a background recording through
  `RecordingService`, tap again stops it. Vibration is the only feedback: long = started, short = stopped,
  three short = error. No preview, no visible notification, no timer, no beeps (Romio's explicit choices).

- **CamMax Dark** launches `BlackoutActivity`: it opens Samsung's own camera in video mode, and `BlackoutService`
  covers the screen with an opaque black overlay after 5 s (AMOLED black = pixels off). The overlay is not
  focusable, so the volume keys still reach Samsung's camera and start or stop its recording. Hold 1 s to exit.
  This is the only route to Samsung's stabilization. Needs "Display over other apps".

## Build and release (no Android SDK or Java on this PC)
- `git push` to `main` → GitHub Actions builds, verifies (`apksigner`, `aapt2`), and publishes a release.
- Permanent install link: https://github.com/remonmishil644/CamMax/releases/latest/download/CamMax.apk
- Signing key `cammax.p12` is committed on purpose (personal sideload app). Never change it, or updates stop
  installing over the old app.
- Poll CI without `gh`: `curl -s https://api.github.com/repos/remonmishil644/CamMax/actions/runs?per_page=1`.
- `adb` exists at `C:\platform-tools`. With USB debugging on, use it for install and `logcat` instead of guessing.

## Facts proven on the phone (do not re-test)
- Camera2 lists only 24 and 30 fps. Asking for 60 anyway works: measured 57 to 60 fps at 3840x2160.
- Asking for 80 returns 60. 60 fps is the 4K ceiling. No 4K120, no high-speed entries worth using.
- Stabilization chips show only **Off** and **Optical**: Samsung exposes no electronic stabilization (EIS) to
  third-party apps. Walking footage needs Gyroflow (motion data) or a gimbal.
- "There was a problem parsing the package" was fixed by restarting the phone. The APK was fine.
- Lenses exposed: camera 0 (main, LEVEL_3, OIS) and camera 2 (ultrawide, LIMITED, no OIS). Both record 4K.
  Tele cameras 6 (3x) and 7 (10x) are physical IDs with no 4K recorder sizes.
- Camera info dump (2026-09-22): video stabilization modes = [0] on every camera, so there is no EIS at all.
  High-speed sessions exist: 1920x1080 and 1280x720 at 120 and 240 fps.
- 2026-09-22 "it records nothing": the event log showed `Storage full` on every start. The phone was out of
  space (4K60 at 100 Mbps is about 45 GB per hour). Check the log before touching code.

## Files
- `RecordingService.kt` — state machine (IDLE, STARTING, RECORDING, STOPPING), 2-minute clips via
  `setNextOutputFile`, start retries with a fallback ladder, stall and thermal watch, MediaStore output.
- `GyroLogger.kt` — one Gyroflow `.gcsv` per clip in `Documents/CamMax` (videos go to `DCIM/CamMax`).
- `ToggleActivity.kt` — the tap handler. `SettingsActivity.kt` + `res/layout/activity_main.xml` — the UI.
- `CameraCaps.kt` — lens, size, fps, stabilization discovery. `Salvage.kt` — keeps crashed clips as `_broken.mp4`.
- `Blackout.kt` — dark mode (activity + overlay service).
- `EventLog.kt` — in-app event log (`files/events.log`), vibration helper, `App` class with crash logging.

## Integration with the PC editor
`INTEGRATION.md` is the file contract with Romio's PC video editor (`F:\Video edit`): file names, the
`cammax.clip/1` sidecar JSON written to `Documents/CamMax`, sessions, gyro pre-roll. Any change to what CamMax
writes must update `INTEGRATION.md` first, bump the schema version, and be re-copied to
`F:\Video edit\CAMMAX_INTEGRATION.md`.

## Rules for this project
- Nothing can be run here: no device, no emulator. CI green only means "compiles". Say so in every report.
- Any bug: get the in-app event log first (Show advanced → Event log → Copy). Do not guess twice.
- Reply to Romio in Arabic (`arabic-writing` skill) and in `google-style`.
- Open items: in-app repair of `_broken.mp4` clips (sidecar JSON per clip already stores encoder settings);
  Gyroflow IMU orientation string is a guess (`YxZ`); Gyroflow needs OIS off too and the lens profile is not calibrated.
