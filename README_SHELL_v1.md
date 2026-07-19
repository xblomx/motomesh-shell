# Moto Mesh Shell v1.0 · 2026-07-19

**What it is:** a ~300-line native Android host. It opens the unchanged PWA
(https://app.moto-mesh.com) in a System WebView, while the APP holds a
`microphone|location` **foreground service**. Android law: the process that owns
the service keeps mic + GPS in the background. Result: **intercom and positions
work no matter what is on screen · including behind Google Maps.**
The service also sets `MODE_IN_COMMUNICATION` + voice-call audio focus, so
Maps' voice **ducks around** the intercom, and the old owner-mic asymmetry
dies natively.

The PWA is untouched · riders without the shell keep working exactly as today.

## Build path A · Android Studio (10 minutes, one-time install)
1. Install Android Studio (Hedgehog or newer) · accept SDK 34 when prompted.
2. **Open** this folder as a project · let Gradle sync.
3. Plug in your Pixel (USB debugging on) · press **Run ▶**.
   APK also lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Build path B · GitHub Actions (no local tools)
1. Create a private repo, push this folder to `main`.
2. Repo → **Actions** → `build-apk` run → download artifact
   **MotoMeshShell-debug-apk** → that's your installable APK.

## Rolling out to the group
1. Send `app-debug.apk` to each rider (Signal/USB/download link).
2. Install (allow "unknown apps" for the browser/file app once).
3. Open **Moto Mesh** (the new icon) · grant **Microphone, Location,
   Notifications** on first run.
4. The persistent notification "**Moto Mesh · intercom active**" is the
   contract: while it shows, voice + GPS survive anything. Swiping the app
   away from Recents ends it deliberately.

## First-contact test order
1. **One phone** with the shell, one on the plain PWA · talk both ways.
2. Shell-phone opens **Google Maps** on top · talk both ways again ·
   this is the moment that was impossible yesterday.
3. Then the group.

## Honest v1 notes
- Debug-signed APK is fine for sideloading the group; Play Store later means
  a signing key + the mic-foreground-service policy form (routine, not hard).
- The shell inherits every PWA feature (SW, updates bar, NFC join links ·
  tags open the shell if it's set as link handler, else Chrome · both work).
- UA suffix `MotoMeshShell/1.0` lets the PWA detect the shell for future
  shell-only UX (e.g. hiding the "stay in Moto" nudge).
