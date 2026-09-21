# Task card: CamMax build 3 (2026-09-21)

- **Goal:** Record with the screen off at settings the stock Samsung app hides (4K60), started and stopped from one icon.
- **User:** Romio. Taps the green dot and puts the phone down without looking at it.
- **Reference:** Stock Samsung camera for quality and stabilization. "Background Video Recorder" apps for the tap-to-toggle flow.
- **Scope mode:** Hold. In-app repair of broken clips moves to build 4.
- **Won't touch:** No preview, no notification UI, no timer, no beeps, no quick-settings tile, no auto-start after reboot.

## Acceptance checks (how each is proven)
1. **4K60 is real.** Record 30 s at 3840x2160 @ 60. Settings shows "measured ~60 fps". Proof: status line + file properties on the PC.
2. **Toggle works blind.** Green dot: tap → long buzz, tap → short buzz, nothing opens. Proof: Romio on the phone.
3. **2-minute clips with no gap.** Record 5 min → 3 files in DCIM/CamMax, each about 2 min. Proof: file list + durations.
4. **Nothing is lost on a crash.** Force-stop CamMax mid-recording, reopen → leftover clip shows as `_broken.mp4`, not gone. Proof: file list.
5. **Storage full stops cleanly.** Three short buzzes, clips saved, status says "Storage full". Proof: fill the phone or lower the threshold for one test.

CI turning green only proves the code compiles. Every check above needs a recording from the S22 Ultra.
