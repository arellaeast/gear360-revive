# Gear 360 Revive

A near-original replacement Android app for the **Samsung Gear 360 (2016,
SM-C200)** — a camera whose hardware still works perfectly, but whose
official companion app Samsung discontinued, leaving it undrivable from a
modern phone.

Gear 360 Revive talks to the camera the way Google intended: over the
**Open Spherical Camera (OSC) API**. When the camera is put in its
"Google Street View" mode it hosts its own Wi-Fi hotspot and answers plain
HTTP commands (`/osc/info`, `/osc/state`, `/osc/commands/execute`). No
Samsung account, no Galaxy-only lock, no Samsung servers involved.

## Features vs. the original Gear 360 Manager

| Capability | Original Samsung app | Gear 360 Revive |
| --- | --- | --- |
| Remote photo shutter | ✅ | ✅ (OSC `camera.takePicture`) |
| Self-timer | ✅ | ✅ (app-side countdown) |
| Timelapse / interval shooting | ⚠️ clunky | ✅ dedicated screen, configurable interval |
| Battery / free-space status | ✅ | ✅ (`/osc/state`) |
| Camera settings (white balance, ISO, exposure, capture mode) | ✅ | ✅ where the camera exposes them via OSC options |
| Browse + download media to the phone | ✅ | ✅ Gallery tab → `Pictures/Gear360Revive` |
| Stitching dual-fisheye → 360 | ✅ in-app | 🚧 Phase 2 in-app; today use `tools/stitch.py` on a computer |
| Share to YouTube / Facebook as 360 | ✅ | 🚧 Phase 2 |
| **Live viewfinder preview** | ✅ | ❌ **Impossible** — Samsung's preview used its own private protocol, never published. This app shows your **last captured photo** as the preview still instead. |
| **Remote video start/stop** | ✅ | ❌ **Not possible over OSC** — OSC is a photo-control API. Video is started on the camera's own button; the files still show up in the Gallery for download. |
| Firmware updates | ✅ | ❌ (Samsung published the last one in 2018; there is nothing left to update to.) |

## Using it

1. **On the camera:** turn on Wi-Fi and select **Connect to Street View**
   mode (this is what enables the OSC API).
2. **On the phone:** join the Gear 360 Wi-Fi network in Android Settings.
   Android will warn that the network has no internet — tap to **stay
   connected** anyway. The camera *is* the network; there's no router.
3. **In the app (Connect tab):** the camera address is your Wi-Fi gateway.
   On 2016 units seen in the wild it is **`192.168.107.1`** (the app's
   default). Tap **Connect / test** — you should see the camera model and
   firmware (e.g. `GEAR 360`, firmware `C200...`).
4. If Android keeps routing around the camera network (mobile data
   "helping"), tap **Pin traffic to Wi-Fi** on the Connect tab to bind the
   app to the Wi-Fi network.
5. Shoot from the **Remote** tab, set intervals on the **Timelapse** tab,
   and pull files down from the **Gallery** tab.

> Note: because the phone is joined to a no-internet hotspot while you use
> the camera, other apps won't have data. That's normal and expected.

## Stitching (Phase 2 groundwork, works today)

The camera saves **unstitched dual-fisheye** files. Convert them on a
computer:

```bash
python3 tools/stitch.py SAM_1001.JPG        # photo → equirectangular + GPano tags
python3 tools/stitch.py SAM_1002.MP4        # video → equirectangular MP4
```

It uses ffmpeg's `v360` filter (`dfisheye → equirectangular`, ~200° FOV)
and, for photos, writes GPano metadata with exiftool when available. See
the header of `tools/stitch.py` for options and caveats.

## Building

No Play Store — build the APK and sideload it.

```bash
# Needs JDK 17 and the Android SDK (platform 35, build-tools 35).
export ANDROID_HOME=$HOME/Android/Sdk   # or wherever your SDK lives
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Then copy the APK to the phone and install it (allow "install from unknown
sources" for your file manager when Android asks).

Toolchain this project was set up with: Temurin JDK 17, Android SDK
platform 35 + build-tools 35.0.0, Gradle wrapper 8.9, AGP 8.6.1,
Kotlin 2.0.20, minSdk 26, targetSdk 35.

### Build status in the sandbox where this was written

The project was authored in a sandboxed VM whose network policy denies
non-HTTP TCP connections ("other TCP" is set to Deny). That policy kills
Gradle's client↔daemon IPC (a loopback socket), so
`./gradlew assembleDebug` cannot complete *in that sandbox* — the build
fails with "Could not receive a message from the daemon" before any
compilation starts, and sdkmanager is blocked by the same proxy for its
own downloads (the SDK components there were fetched as plain zips over
HTTPS instead and laid out by hand). Everything else was verified:
the SDK tools run (`aapt2 version` OK), and `tools/stitch.py` was
smoke-tested end-to-end on a synthetic dual-fisheye image (produced a
7680×3840 equirectangular JPEG, exiftool not present so GPano tagging
printed its manual command as designed).

To finish the APK build in that sandbox: open Muse Settings →
Permissions → Direct network protocols and switch **other_tcp** from
Deny to Ask, then re-run `./gradlew assembleDebug` (JDK lives at
`~/workspace/.jdk`, SDK at `~/workspace/.android-sdk`, both already
installed). On any normal machine, just use the commands above — no
special setup. First successful compile anywhere is still the real
compile check for the Kotlin sources; treat any small fixes it needs as
the expected first-build iteration.

## Roadmap

- **Phase 1 (this repo):** OSC client, Connect / Remote / Timelapse /
  Gallery screens, desktop stitch helper.
- **Phase 2:** in-app stitching (ffmpeg-based) and one-tap share of
  finished 360s; optional auto-download of new shots.
- Hardware-confirmation still wanted from a real SM-C200 session: exact
  `listFiles` response shape (the client already falls back across the
  OSC v1 `listImages` variants), which options the firmware actually
  exposes via `getOptions`, and state field names for free space.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 Jacob East.

Not affiliated with Samsung. "Gear 360" is a Samsung trademark; this is an
interoperability project for hardware its maker no longer supports.
