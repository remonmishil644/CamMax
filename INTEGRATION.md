# CamMax ↔ Video editor: integration contract (v3, 2026-09-23)

Two projects, one pipeline. **CamMax** (`F:\CamMax`, Android app on the Samsung S22 Ultra) records.
The **editor** (`F:\Video edit`, PC tool built on FFmpeg) sorts, trims, stabilizes, joins, and exports.
They never talk over a network. They integrate through **files with fixed names and a fixed JSON schema**.
This file is the single source of truth. A copy lives in `F:\Video edit\CAMMAX_INTEGRATION.md`; when the
contract changes, change `F:\CamMax\INTEGRATION.md` first, bump the schema version, then re-copy.

## 1. What CamMax writes on the phone

| Path on the phone | File | Content |
|---|---|---|
| `DCIM/CamMax/` | `CamMax_YYYYMMDD_HHMMSS_N.mp4` | One clip, at most 2 minutes. HEVC or H.264 + AAC 192 kbps 48 kHz stereo. |
| `DCIM/CamMax/` | `…_broken.mp4` | A clip whose recording was cut (crash, battery, heat). The MP4 index (`moov`) is missing. |
| `Documents/CamMax/` | same basename + `.gcsv` | Gyroflow IMU log for that clip: gyro (rad/s) and accelerometer, about 430 Hz. |
| `Documents/CamMax/` | same basename + `.json` | Clip sidecar, schema `cammax.clip/3` (section 2). Written when the clip closes. |

Rules the editor can rely on:
- Files pair by **basename**. `CamMax_20260922_021800_3.mp4` ↔ `CamMax_20260922_021800_3.gcsv` ↔ `….json`.
- One tap-to-start … tap-to-stop run is a **session**. Clips in a session share `sessionId` and have
  `clipIndex` 1, 2, 3 … They are cut with `MediaRecorder.setNextOutputFile`, so they are **gapless** and share
  identical codec parameters: they can be joined with FFmpeg's concat demuxer and `-c copy` (no re-encode).
  After an interruption CamMax reopens the camera; then there is a gap of about 2 s and `interruption` is non-empty
  on the clip that closed.
- A clip with **no `.json`** was never closed cleanly: treat it as broken even if its name has no `_broken`.
- Clips recorded in **dark mode** (Samsung's own camera under a black overlay) are ordinary Samsung files in
  `DCIM/Camera/` with **no** `.gcsv` and **no** `.json`. They already carry Samsung's stabilization.
- Rotation is stored as MP4 rotation metadata and repeated in the JSON (`rotation`, degrees clockwise).

## 2. Sidecar JSON, schema `cammax.clip/3`

```json
{
  "schema": "cammax.clip/3",
  "fpsTimeline": [59, 60, 60, 57, 52, 48],
  "droppedFrames": 214,
  "clock": "realtime",
  "firstFrameNs": 123456789000000,
  "sessionFirstFrameNs": 123456789000000,
  "gyroT0Ns": 123455789000000,
  "gyroFirstSampleNs": 123455790200000,
  "gyroRateHz": 430.2,
  "sessionId": "S20260922_021800",
  "clipIndex": 3,
  "videoFile": "CamMax_20260922_021800_3.mp4",
  "gyroFile": "CamMax_20260922_021800_3.gcsv",
  "gyroPreRollMs": 1000,
  "status": "complete",
  "device": "SM-S908E",
  "cameraId": "0",
  "width": 3840, "height": 2160,
  "fpsRequested": 60, "fpsMeasured": 59.94,
  "highSpeed": false,
  "codec": "hevc",
  "videoBitrate": 100000000,
  "audioBitrate": 192000, "audioSampleRate": 48000, "audioChannels": 2,
  "rotation": 90,
  "iso": 0,
  "stabMode": 0,
  "stabApplied": "electronic off, optical off",
  "readoutMs": 16.4,
  "lens": {
    "lensKey": "SM-S908E_cam0_3840x2160",
    "name": "Main", "cameraId": "0",
    "focalLengthMm": 6.4, "equivFocalMm": 23, "aperture": 1.8,
    "sensorWidthMm": 9.83, "sensorHeightMm": 7.37,
    "pixelArray": "8000x6000", "activeArray": "8000x6000", "preCorrectionActiveArray": "8000x6000",
    "intrinsics": null, "distortion": null
  },
  "thermalStatus": 2,
  "interruption": "",
  "startMs": 1790000000000, "endMs": 1790000120000
}
```

Field notes:
- **Frame drops (v3).** `fpsTimeline` is the number of sensor frames in each full second after the 1 s settle
  window; `droppedFrames` counts the frames missing from the 1/fps grid (a gap of N frame periods adds N-1).
  Frames sit on an exact grid and gaps are whole multiples, so the timeline shows *where* the phone throttled.
- **Orphans (v3).** A recording that produced no video deletes its own `.gcsv` and, when it failed, writes a
  `.json` with `status: "broken"`, `videoFile: null`, `gyroFile: null`, plus `interruption` and `thermalStatus`.
  So every `.json` is a recording attempt; a `.gcsv` alone should no longer occur (older builds left them).
- **Rotation.** The MP4 display matrix reads -90 where the sidecar says `rotation: 90`. Same orientation,
  opposite sign convention. Trust either.
- **`r_frame_rate`.** MediaRecorder writes the container; CamMax cannot set its header frame rate. ffprobe's
  `r_frame_rate` is a guess from timestamps and reads 120/1 when frames are dropped on a 59.94 grid. Use
  `avg_frame_rate` or the sidecar (`fpsRequested`, `fpsMeasured`, `fpsTimeline`), never `r_frame_rate`.
- **Clock sync (v2).** `clock` is `realtime` when the camera stamps frames on the same clock as the gyroscope
  (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`). Then `firstFrameNs` is the sensor timestamp of the first frame the
  recorder could encode, and the `.gcsv` is written so that **this frame sits at exactly t = 1000.000 ms**
  (`gyroT0Ns = firstFrameNs - 1e9`). Pass Gyroflow `--gyro-offset 0` style: the offset is known, skip the heavy
  auto-sync, or run it only as a fine check over a short range. `gyroFirstSampleNs` is the clock time of the
  first row in the `.gcsv`; the row's `t` equals `(gyroFirstSampleNs - gyroT0Ns) / 1e6` ms. When `clock` is
  `unknown`, CamMax anchors with `elapsedRealtimeNanos()` at the first frame callback, which is accurate to a
  few ms. Clips 2, 3, … of a session start by a file switch inside MediaRecorder, so `firstFrameNs` is 0 for
  them: derive their start from `sessionFirstFrameNs` plus the summed durations of the earlier clips (gapless).
- **Lens identity (v2).** `lens.lensKey` is stable per phone, camera, resolution and mode: use it as the key of
  the Gyroflow lens-profile cache in the editor. `intrinsics` (`[fx, fy, cx, cy, s]` in pre-correction
  active-array pixels) and `distortion` (Brown-Conrady `[k1, k2, k3, p1, p2]`) come from the camera when Samsung
  publishes them; on this phone expect `null`, so the editor calibrates once per `lensKey` with Gyroflow's
  calibrator (a 30 s clip of a checkerboard on the PC screen) and stores the profile under that key.
- `status`: `complete` or `broken`. `gyroFile` is `null` when motion logging was off.
- `stabMode`: 0 Off, 1 Optical (OIS), 2 Electronic, 3 Both, 4 Enhanced. On this phone only 0 and 1 exist.
  **Gyroflow needs `stabMode` 0.** With 1 (OIS on) gyro stabilization gives wobble: fall back to optical-flow
  stabilization (vid.stab) or none, and tell the user why.
- `gyroPreRollMs`: each `.gcsv` starts about 1000 ms **before** the first video frame, because the exact clip
  switch time is only known to about a second. Use it as the initial offset, then let Gyroflow auto-sync.
- `readoutMs`: sensor readout time (rolling shutter) reported by the camera. 0 means unknown. The same value is
  in the `.gcsv` header as `frame_readout_time`.
- `fpsMeasured` is the sensor frame rate averaged over the recording. Samsung lists only 24/30 fps for 4K but
  delivers a measured 57 to 60 when asked for 60. Probe the file too: MediaRecorder output can be slightly VFR.
- `highSpeed: true` clips are 1080p at 120 or 240 fps, recorded in real time. On a 60 fps timeline they give
  clean 2x or 4x slow motion with no frame interpolation.
- `thermalStatus`: Android scale 0 none … 3 severe … 6 shutdown.
- `.gcsv` header `orientation,YxZ` is a **guess**. If Gyroflow moves the picture the wrong way, find the right
  string once with Gyroflow's "Guess IMU orientation" and store it in the editor's config as an override.

## 3. What the editor should do with it

1. **Ingest.** Pull `DCIM/CamMax` and `Documents/CamMax` from the phone into one folder per shooting day,
   video and sidecars side by side (Gyroflow auto-loads a `.gcsv` that sits next to the video with the same
   name). `adb` is installed at `C:\platform-tools` (`adb pull /sdcard/DCIM/CamMax …`); MTP copy also works.
   Verify sizes after copy. Never delete from the phone without an explicit user action.
2. **Group by session.** Read every `.json`, group by `sessionId`, order by `clipIndex`. Show a session as one
   item in the sorting grid, with its total duration.
3. **Lossless join.** For a session with no `interruption`, offer "join session" using the concat demuxer with
   `-c copy`. This fits the editor's smart-export rule (copy, do not re-encode).
4. **Gyro stabilization.** For clips with `gyroFile` and `stabMode` 0, stabilize with **Gyroflow's CLI** instead
   of vid.stab: it uses real motion data, so it is far better for walking footage. Stabilize **per clip, before
   joining**, and only the trimmed ranges the user kept (the PC is an i5-7200U with Intel HD 620: every skipped
   second saves minutes). Encode with Quick Sync (`hevc_qsv`). For clips without gyro data keep the vid.stab path.
5. **Repair.** For `_broken.mp4` (or a clip with no `.json`), repair the missing `moov` with `untrunc`, using a
   complete clip from the **same session** as the reference (identical encoder settings). Offer this at ingest.
6. **Report.** Surface per clip: measured fps, stabilization used, thermal status, interruptions. Flag clips
   where `fpsMeasured` is more than 5% under `fpsRequested`.

## 4. What is NOT in the contract (do not assume)
- No network link, no shared database, no live control of the phone from the PC.
- CamMax never deletes or moves files after writing them.
- Audio exists in every CamMax clip, but the editor's default is to export muted.
