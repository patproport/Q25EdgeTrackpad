# Q25 Edge Trackpad

An Android trackpad utility for the Zinwa Q25. It changes the Q25 trackpad mode and can turn edge movement into scrolling while keeping cursor movement and physical clicks available.

This project is a modified derivative of [Q25 Trackpad Customizer](https://github.com/shroyertech/Q25TrackpadCustomizer) by Shroyer Tech and its GPLv3 [Q25 Trackpad Enhanced fork](https://github.com/astroboii47/Q25TrackpadCustomizer) by astroboii47. The local Enhanced source shares the settings backup structure and other app components with this project. The Q25 Edge Trackpad 1.0.0 changes, prepared in 2026, include mode handling, edge scrolling, per-app switching, root monitor recovery, backup and restore improvements, and replacement launcher artwork. See [LICENSE](LICENSE) for the GPLv3 text and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party notices.

## Device and software requirements

- Zinwa Q25. Other devices are not supported or tested.
- Android 16 has been tested. Other Android versions and ROM builds have not been verified.
- Root access through KernelSU is required by the implementation. The exact KernelSU version used for the reported test was not recorded. Other `su` providers are not verified.
- The app's Accessibility service must be enabled. Android may require allowing restricted settings before enabling it.
- This app depends on the Q25 trackpad interface exposed at `/proc/q20_switch_key_mouse` and Android input-monitor APIs available to its root monitor. ROMs that omit or change these interfaces may not work.

LineageOS builds have not been separately tested. A LineageOS build is only expected to work if it runs on the Q25 and provides the required trackpad interface, root access, and Accessibility support; this is not a compatibility guarantee.

## Modes

- **Mouse (Gesture):** Scrolls at screen edges by dispatching Accessibility gestures.
- **Mouse (Smooth Wheel):** Sends continuous vertical and horizontal scroll events at screen edges.
- **Keyboard:** Uses the Q25 keyboard mode.

The app also provides per-app mode selection, automatic keyboard switching while entering text, Quick Toggle, Bottom Touch Guard, and JSON settings backup and restore. An earlier signed 1.0.0 candidate was installed and tested on a Zinwa Q25 with KernelSU root and Accessibility enabled. The reported checks cover Gesture cursor movement, click and drag; Smooth Wheel scrolling; Keyboard mode; edge and corner scrolling; Quick Toggle; and per-app automatic mode switching. The final APK built from this public source history still needs its own verification. Settings migration from the earlier debug package was intentionally skipped and remains untested.

## Install

1. Once published, download the signed APK from the [Q25 Edge Trackpad Releases page](https://github.com/patproport/Q25EdgeTrackpad/releases/latest). The repository and Release are not live yet.
2. Install the APK on the Q25. Android may require enabling installation from the application used to open the APK.
3. Open the app and grant root access in KernelSU when prompted.
4. Follow **Initial setup** in the app to enable its Accessibility service. If Android blocks the service, allow restricted settings from the app's system App Info page, then enable the service again.
5. Select a mode and grant any requested notification permission.

The signed 1.0.0 APK (`versionCode` 1) uses package ID `io.github.patproport.q25edgetrackpad`. The prior test build uses `tech.shroyer.q25trackpadcustomizer.edgescroll`, so Android treats the release as a separate app. To move settings, export the test build's JSON to a location outside the app, then disable its Accessibility service before installing the signed release. Grant KernelSU and Accessibility access only to the new app, then restore the JSON there. The release accepts backups from the prior package in code, but this migration has not been tested on Q25. Remove the test build only after confirming the release and restored settings work.

## Build a debug APK on Windows

Install Android Studio or the Android SDK command-line tools, Android SDK Platforms 34 and 36, Android SDK build tools, and Java 17 or newer. The app compiles against SDK 34; its compile-only hidden API stub module also needs the SDK 36 `android.jar`. Set `JAVA_HOME` if Java is not on `PATH`; set `ANDROID_SDK_ROOT` to your SDK location. From the project directory, run:

```powershell
.\build-debug.ps1 -Clean
```

The script builds and verifies a debug APK without using ADB. To compare its package, version, and signing certificate with a reference APK, pass `-BackupApk (Join-Path $env:TEMP 'known-good.apk')`. The optional comparison does not install anything. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Build a signed release APK on Windows

Keep a dedicated release keystore and a verified backup outside this repository, then run `./build-release.ps1` in PowerShell. The script prompts for the keystore path, alias, and passwords; Gradle reads them from process environment variables, and the script clears those variables after the build. It checks the APK signature, package, version, certificate fingerprint, and SHA-256. The APK remains in the ignored `app/build/outputs/apk/release/` directory until it is separately uploaded to GitHub Releases. Keep the keystore and passwords private; all future updates to this package must use the same signing key. See [RELEASE.md](RELEASE.md) for key storage and device verification steps.

## License and asset status

The project code is licensed under GPL-3.0; preserve the upstream notices and identify modifications when redistributing modified versions. The launcher artwork was created specifically for this derivative from simple geometric shapes and is included under GPL-3.0-or-later. Its editable SVG source is [`artwork/ic_launcher_master.svg`](artwork/ic_launcher_master.svg). The former launcher images attributed upstream to WhoCon via Flaticon have been removed from the application resources and are not part of the intended public source or APK.

The separate settings-screen gear vector and the resolved release dependency inventory are credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), with a copy of the Apache-2.0 terms. The public Release should link the source for its exact build and these notices alongside the APK.
