# Task card: CamMax build 6 (2026-09-22)

- **Goal:** The green dot starts a recording every time, and the settings app looks and works like a finished product.
- **User:** Romio. Taps the green dot blind, phone in a pocket or on a stand. Opens CamMax rarely, to change a setting or read what happened.
- **Reference:** Stock Samsung camera for quality. Blackmagic Camera and mcpro24fps for the dark, technical settings look.
- **Scope mode:** Hold. 4K60 is the proven ceiling (the 80 fps test returned 60), so no more fps work.
- **Won't touch:** No preview, no visible notification, no timer, no beeps, no tile. In-app repair of broken clips stays for a later build.

## Acceptance checks (how each is proven)
1. **Start is reliable.** 20 taps in a row, including stop-then-start within 1 second: 20 long buzzes, 20 saved clips. Proof: Romio on the phone plus the event log.
2. **Every failure explains itself.** Any failed start gives three short buzzes and a reason in "Last:". Proof: event log (Advanced, Event log, Copy).
3. **Settings save themselves.** Change a chip, leave the app, tap the green dot: the clip uses the new setting. Proof: "Last:" line.
4. **Test button works.** It records 10 seconds and shows the measured frame rate without leaving the app. Proof: screenshot.
5. **The screen reads well.** Screenshot of the new settings screen on the S22 Ultra, checked against contrast and 48 dp touch-target rules.

CI turning green only proves the code compiles. I have no Android device or emulator here, so checks 1 to 5 all need the phone.
