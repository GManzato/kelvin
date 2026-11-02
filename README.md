<p align="center">
  <img src="assets/kelvin-logo.svg" alt="Kelvin logo" width="480">
</p>

# Kelvin

**Kelvin is an independent, unofficial thermal viewer for a specific camera model: the FLIR ONE for iPhone (Lightning, model 435-0004-01, USB `09cb:1996`), used on an Android phone through a Lightning-to-USB-C adapter.** It talks to the camera directly over USB, shows a live thermal, visible, blended or edge view with temperature readouts, and saves photos, video and timelapse logs on the phone. Nothing is uploaded automatically.

> **Trademarks and affiliation.** Kelvin is an independent project. It is not affiliated with, endorsed by, or sponsored by Teledyne FLIR. "FLIR" and "FLIR ONE" are marks of their respective owners and are used here only to describe which hardware Kelvin is compatible with. This repository contains no vendor logos and no vendor SDK.

> **Safety.** Kelvin is a hobby tool, not a medical, fire-safety or electrical-safety instrument. Do not rely on a thermal picture to decide that a circuit, appliance or surface is safe.

## Status

- Current version: **0.6** (`versionCode` 6), Android 10 (API 29) or newer, phone with USB host / OTG support.
- Physically tested with a FLIR ONE Lightning (435-0004-01) through an adapter on a Samsung Galaxy S25 Ultra. That is the verified camera/phone combination; it is not a general compatibility guarantee. Other phones may work or may not; see [Compatibility](#compatibility).
- This is **not** the iPhone app and does not run on iOS.
- Hardware-free regression tests supplement manual device checks. Neither establishes compatibility with other cameras, phones, adapters or operating-system versions.

## License status

This repository is **public as source-available, so the code can be read, but the original Kelvin code does not currently carry a general reuse license.** It is not released as open source, and no MIT, Apache, GPL or similar license applies to it at this time. Whatever rights apply by law or under GitHub's terms are unaffected. If you want to reuse or redistribute Kelvin code, ask first.

Third-party libraries and fonts used by the app keep their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The bundled fonts, Inter and JetBrains Mono, are under the SIL Open Font License 1.1 ([Inter](android/app/src/main/assets/font-licenses/inter-OFL-1.1.txt), [JetBrains Mono](android/app/src/main/assets/font-licenses/jetbrains-mono-OFL-1.1.txt)), and the Gradle wrapper is under Apache 2.0 ([license text](third_party/gradle-wrapper-LICENSE.txt)).

## Download

No build is needed to try it:

- Latest release page: <https://github.com/GManzato/kelvin/releases/latest>
- Direct APK for version 0.6: <https://github.com/GManzato/kelvin/releases/download/v0.6/Kelvin-0.6.apk>

### Install (sideload)

1. Download `Kelvin-0.6.apk` on the phone (or copy it there).
2. Open it. Android will ask you to allow installs from the app you opened it with (browser or file manager); allow that for the install, then you can turn it off again. Wording differs between Android versions and phone makers.
3. Android or Play Protect may warn about an app from outside the Play Store. Kelvin is not distributed through Google Play. Install only if you trust where you got the file.
4. Connect the camera to the phone through a Lightning-to-USB-C adapter and press the camera's power button. Kelvin finds it on its own once it is powered on.
5. Android shows **"Allow Kelvin to access FLIR ONE?"**. Tap OK. Tick *Always open Kelvin when FLIR ONE is connected* if you want Kelvin to launch whenever you plug the camera in. The live stream stays on the phone; sharing saved files or diagnostics is an explicit action.

The release APK uses the package `be.apexlab.kelvin` (app name "Kelvin"). Debug builds you make yourself use `be.apexlab.kelvin.debug` ("Kelvin dev") and install next to it.

Versions up to 0.5 used the package `com.gilles.flirone`. Android treats 0.6 as a different app: it installs next to the old one instead of updating it, and settings and captures stored inside the old app are not carried over. Export anything you want to keep, then uninstall the old Kelvin.

## Features

- Live view: thermal, visible, thermal/visible blend (adjustable) and edges modes. Portrait by default; the live view also turns to landscape with the phone.
- Seven palettes: Iron, Rainbow, White-hot, Black-hot, Arctic, Lava, Contrast.
- Measurement tools: spot, box (min/max/average) and delta between two points; hottest/coldest markers; °C or °F.
- Scale lock to a fixed range, and over-range markers for readings outside the camera's built-in −20…120 °C range.
- Threshold alarms (above, below, between) with optional sound and haptic feedback.
- Emissivity presets or a custom value, plus a reflected-temperature setting.
- Thermal/visible alignment controls (subject distance for parallax, 180° flips).
- Photos that keep per-pixel temperatures, so they can be re-examined later.
- Gallery with filters, viewer, before/after compare, palette recolor, CSV export of the portrait 60×80 temperature grid (saved to `Download/Kelvin`), sharing, and delete with undo.
- H.264 video recording (MP4) of the selected view with its readouts, with playback and sharing.
- Timelapse logging: one whole frame every 30 s, with a max-temperature chart and CSV export. Timelapse runs while the app is alive; there is no foreground service, so **unattended background logging is not reliable** and you should not count on it.
- Optional power saving: pauses the stream when the app goes to the background.
- A compatibility report for helping add support for other cameras (see [Diagnostics](#compatibility-report-and-diagnostics)).

The thermal sensor is 80×60 pixels (Kelvin rotates it to 60×80 for portrait use). Kelvin upsamples that image for display; it does not add detail, and there is no "HD" or super-resolution claim.

## Compatibility

Only one camera is streamed.

| Hardware | USB | Kelvin 0.5 |
| --- | --- | --- |
| FLIR ONE for iPhone, Lightning, 435-0004-01, through a Lightning-to-USB-C adapter | `09cb:1996` | **Tested** and streamed |
| FLIR ONE Pro and other Lightning models that identify differently | `09cb:1996` | Identified and **reported only**; stopped before any config or frame session |
| FLIR ONE for Android (USB-C or Micro-USB variants) | other `09cb` product IDs | Detected for the compatibility report only; **not supported** for streaming |
| FLIR ONE Edge (wireless) | not discoverable over USB | **Not supported** |
| Any other USB device | any | Ignored for streaming (listed in the report only) |

Kelvin only opens devices with the tested product ID. Unknown FLIR product IDs are never opened, and no camera commands are sent to them. Matching dimensions alone does not establish calibration compatibility, so Kelvin does not guess temperatures for unverified models.

Phone-side notes: the phone must support USB host mode. Some phones need a thin case or no case so the adapter seats fully. If connections are unstable on your phone, a powered USB hub or OTG adapter with external power may help.

## Troubleshooting

- **Camera not detected**: check the adapter is fully seated, the camera is powered on (press the power button on its bottom), and the camera has charge (charge it through its own USB-C port for a while if the LED stays off). After plugging in, allow about 20 s for the camera to start.
- **"Camera not responding" or reconnect loops**: the camera may still hold an old session, be low on power, or need a moment. Kelvin keeps trying and reconnects when the camera reappears on USB. If it stays stuck, unplug the camera from the adapter, wait a couple of seconds, and plug it back in. A stale link can need either some waiting or a physical replug; there is **no guaranteed timeout** after which it recovers by itself.
- **Unstable power over OTG**: try a powered hub, a different adapter, or another cable.
- **"This camera isn't supported yet"**: see the table above and consider sending a compatibility report.

## Compatibility report and diagnostics

If a camera does not connect, or you use a different FLIR model, Kelvin can produce a JSON compatibility report:

- From the connection screens, tap **Report**, or open **Settings → Compatibility report**, then **Refresh report** and **Share**.
- It contains app version, phone and Android details, USB host support, USB descriptors of attached devices (FLIR candidates flagged), camera identification metadata, the connection stages reached, and a short journal of events and errors.
- It does **not** contain images, raw frames or temperature data, or phone identifiers. The **camera serial number is optional and off by default**; enable it only if you want it included.
- Reports stay on the device. Nothing is uploaded automatically; sharing hands the one file to Android's share sheet and you choose where it goes. The event journal is kept in memory since the app process started.
- Even so, a report still describes your phone model and hardware. **Please do not post unredacted reports publicly** (for example in public issues). Review the file first, and especially do not post it with the serial enabled. Share it privately with whoever is helping you.

## Scientific limits and calibration

- Temperatures come from fixed conversion constants derived from the calibration data of one particular FLIR ONE unit, plus the alignment I measured with it. They are **not** generic factory calibration for every unit and they are **not** certified measurements.
- No accuracy figure is promised. Kelvin applies only the emissivity and reflected-temperature settings it exposes; it does not model other effects such as atmosphere or distance.
- Shiny and low-emissivity surfaces (bare metal, glass) reflect their surroundings and read wrong. Use emissivity presets as rough guides only.
- Over-range readings are marked, not corrected. The camera's shutter recalibrates periodically and may click when it does.
- Treat everything as indicative, not as proof.

## Build from source

Requirements:

- JDK 17 or newer that works with the Android Gradle plugin (AGP 8.11 requires JDK 17 as a minimum; the project targets Java 17 bytecode).
- Android SDK with **platform 35** (compile and target SDK 35, `minSdk` 29). Install it with Android Studio's SDK Manager or `sdkmanager`, and accept the SDK licenses yourself.
- Gradle 8.13 and Kotlin 2.1.20 are pulled in by the wrapper and the build files; no separate install needed.

With the command line:

```sh
cd android
./gradlew assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r` or copy it to the phone.

With Android Studio: open the **`android/`** directory (not the repository root) and let Gradle sync.

Gradle needs to know where the SDK is: set `ANDROID_HOME`, or let Android Studio create `android/local.properties` with `sdk.dir=...`. `local.properties` is machine-specific and is not tracked in git.

### Release signing

A release build is optional. If `android/keystore.properties` exists, `assembleRelease` signs with it; otherwise the release output is unsigned and cannot be installed as is. The file uses these keys (supply your own values):

```properties
storeFile=...
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is resolved relative to `android/`. Never commit the keystore or `keystore.properties`.

A clone **cannot reproduce the maintained signing identity**. An APK you sign with your own key is a different build lineage: it cannot update an installed release signed with the maintainer's key (Android will refuse the update; you would have to uninstall first, which also removes the app's data). Use the debug build, which has its own package name, to avoid that clash.

## Tests

Run the Android JVM regression suite from `android/` with the same JDK and
Android SDK setup used for builds:

```sh
./gradlew testDebugUnitTest
```

It covers thermal count conversion, portrait rotation and flips, visible-region
scaling, saturation warnings, emissivity/reflected-temperature correction,
alarm thresholds, box measurements, temperature units and byte-stream buffering.
The reference temperatures protect the current conversion from regressions;
they are not independent calibration or proof of measurement accuracy.

Run the Python reference-driver protocol suite from the repository root:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install pyusb
.venv/bin/python -m unittest discover -s tests -v
```

These tests use an in-memory USB byte transport to exercise the real Python
iAP2 parser: fragmented and coalesced packets, bad-header resynchronization,
duplicate suppression, sequence wrap, detect/sync, resets and read timeouts.
Only PyUSB is needed for this suite, not NumPy, Pillow, libusb or a camera.
The Android and Python drivers are separate implementations: passing Python
tests does not verify Android USB sessions.

Neither suite accesses a physical camera. USB permissions, real connections,
Compose interaction, diagnostics sharing and media capture still need
device/emulator testing.

## Python USB research tools

The repository root also holds the Python prototype used to work out the USB bring-up before it was ported to Kotlin. It is a research aid, not a supported product.

| File | Purpose |
| --- | --- |
| `flirone_ios.py` | Camera driver over libusb: resets the device, claims the interfaces, runs the iAP2 bring-up, opens the config channel, then yields frames (thermal block, visible JPEG, status JSON). |
| `iap2host.py` | Minimal iAP2 link layer that plays the phone side so the camera starts. |
| `snap.py` | Grabs one frame, prints temperature statistics, writes images. |

Dependencies: Python 3 with `pyusb`, `numpy` and `Pillow`, plus the **libusb** system library (for example `brew install libusb` on macOS or your distribution's `libusb-1.0` package on Linux). There is no `requirements.txt`.

```sh
python3 -m venv .venv
source .venv/bin/activate
pip install pyusb numpy Pillow
python snap.py          # optional: python snap.py 15  (read 15 frames, then save the last; 15 is the default)
```

Notes:

- Run it from the repository root; `thermal.png` (colorized, 640×480) and `visible.jpg` are written to the current directory, overwriting earlier ones.
- USB permission handling differs per OS. Linux usually needs a udev rule or elevated privileges to open the device; macOS may or may not need `sudo` depending on setup. Windows is untested.
- `snap.py` embeds the raw calibration constants of the one camera unit I used, so its temperatures carry the same caveats as above.
- The stdout status JSON (printed after the statistics) comes from the camera and can include private camera information such as identifiers. Do not paste it publicly without checking it.
- The tools reset the USB device and speak to it as an Apple accessory host; only run them with a camera you own.

## Source map

```
android/                           Gradle project (open this in Android Studio)
  app/src/main/AndroidManifest.xml
  app/src/main/res/                icons, colors, USB device filter, bundled fonts
  app/src/main/assets/font-licenses/  font copyright notices and license texts
  app/src/test/java/              JVM thermal, units and byte-stream regression tests
  app/src/main/java/be/apexlab/kelvin/
    FlirCamera.kt                  USB driver for the tested Lightning transport
    Iap2Link.kt                    iAP2 link and session handling
    CameraController.kt            connection state, permission and model gating
    Thermal.kt                     palettes, calibration, temperature analysis
    Captures.kt                    photos, video and timelapse storage, CSV export
    Recorder.kt                    H.264/MP4 video recording
    Timelapse.kt                   30-second timelapse logger
    CompatibilityDiagnostics.kt    redacted compatibility report
    Prefs.kt, ThermalApp.kt, MainActivity.kt
    ui/                            Compose screens (live, gallery, viewer, compare, settings, ...)
flirone_ios.py, iap2host.py, snap.py   Python research tools
tests/test_iap2host.py             hardware-free Python iAP2 protocol regressions
THIRD_PARTY_NOTICES.md             third-party licenses
```

Architecture: the Python code and the Kotlin app are **two independent ports** of the same USB bring-up. The Android app does not run or bundle the Python code, and no FLIR SDK or binary is bundled in either.

## Roadmap

- Support for additional cameras, only when proper factory calibration and thermal layout can be established.
- Super-resolution or other upscaling is deferred; Kelvin makes no HD claim.
- Device/emulator integration tests for connection lifecycle, diagnostics and media handling.

## Contributing and reports

Compatibility reports for other FLIR models are the most useful input right now; see the privacy notes above before sharing. Because the code has no reuse license yet, please open an issue to discuss before sending code.
