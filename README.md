# CamMax

Custom Android video recorder for Samsung S22 Ultra. Exposes every resolution and fps the Camera2 API reports on your device, plus manual ISO, HEVC/H264, bitrate slider, and screen-off recording (black overlay + foreground service).

## Build
Push to GitHub — Actions builds the debug APK automatically. Download it from the Actions run's artifacts.

## Install
Enable "Install unknown apps" for your file browser, tap the APK.

## Notes
- 4K@60fps is the S22 Ultra hardware ceiling for UHD. 8K is 24fps only. 1080p goes up to 240fps via the SLOMO entries in the mode list.
- Tap the black overlay to reveal the screen again.
- Files land in `Movies/` inside the app's private storage — connect USB and pull them.
