# ZRKC - Zwift Ride & Kickr Core

Standalone Android app that links the Zwift Ride controllers to the KICKR CORE: 24 virtual
gears, free grade, GPX route or ERG, music and volume from the controllers, and a history of
finished rides. Technical specification: [docs/specification.md](docs/specification.md).

**Independent project, not affiliated with Zwift Inc. or Wahoo Fitness.** The protocols were
captured on the hardware for interoperability purposes. Use at your own risk.

## Tested hardware

| Device | Version |
|---|---|
| Zwift Ride (two controllers) | firmware 1.2.0, hardware B.0 |
| KICKR CORE "85FF" with Zwift Cog | firmware 1.5.36 |
| Google Pixel 10 Pro | Android 17 (API 37); app minimum: Android 12 (API 31) |

Do not open the Zwift app while the Ride is on: from firmware 1.3 onwards, Zwift locks the
controllers. The app reads each controller's version and warns you.

## Usage

1. **Connections**: allow Bluetooth and notifications, exclude the app from battery
   optimisation, tap Search, then Remember the Ride's left pod (it relays both controllers)
   and the KICKR. Only remembered devices are joined afterwards.
2. **Ride**: Prepare the ride (or Start an activity in the Activities tab), choose the mode,
   Start. Everything is then controlled from the controllers; to finish, hold Finish while it
   counts down 2, 1.
3. **Activities**: the summary offers a short comment, and every finished ride is listed in
   the Activities tab, most recent first, to review, comment or delete.

Default mapping: Shift up and Shift down for gears, Up and Down arrows for grade (or the ERG
target), Left and Right arrows for tracks, A play/pause, Y and B volume, Z pause, right
Power-up opens Spotify. Everything can be changed in Settings.

Gears are worked out by the app by default (Settings › Gears › Gears by: Power): every second it
takes the speed of a real bike at your cadence and gear and sets the KICKR to the power that
speed needs on the current grade. The gear table holds up to 60 ratios.

A heart rate monitor using the standard Bluetooth profile (such as the COROS HRM) can be
remembered like the other devices. The ride screen shows the track playing and the volume;
the track title needs notification access, which the app asks for from that screen.

Export for COROS, on the summary, sends the ride's FIT file to your COROS account (Settings ›
COROS account: region, e-mail, password, kept encrypted on the phone as an MD5). COROS has no
public upload API: this uses the private one behind Training Hub and may stop working without
notice. This is the app's only use of the network.

The interface is in English only.

## Building

Requirements: JDK 17 or newer, Android SDK with `platforms;android-37.0` and
`build-tools;36.0.0`, and a `local.properties` containing `sdk.dir=...` (not versioned).

    ./gradlew build              # tests, Android Lint, ktlint, detekt, debug and release APKs
    ./gradlew ktlintFormat       # reformats the code
    ./gradlew :app:installDebug  # on the connected phone (USB debugging)

Modules: `protocol/` (pure Kotlin logic, tested on the JVM against real frames), `ble/`
(Bluetooth links and device managers), `app/` (service, session, storage, screens).

**Signed release APK.** The key stays outside the repository:

    keytool -genkeypair -keystore ~/.config/kick-zwift/release.jks -alias kickzwift \
            -keyalg RSA -keysize 4096 -validity 10000
    export KICKZWIFT_STORE_FILE=~/.config/kick-zwift/release.jks
    export KICKZWIFT_STORE_PASSWORD=... KICKZWIFT_KEY_ALIAS=kickzwift KICKZWIFT_KEY_PASSWORD=...
    ./gradlew :app:assembleRelease

Without these variables, the release APK is built unsigned. Back up the key: without it, no
update installs over a version already handed out.

**Releases for testers.** Pushing a version tag builds the signed APK on GitHub and attaches
it to a release (`.github/workflows/release.yml`). The tag must match `versionName` in
`app/build.gradle.kts`; raise `versionCode` too, or the update won't install over the
previous one.

    git tag v1.2 && git push origin v1.2

The workflow reads four repository secrets: `KICKZWIFT_KEYSTORE_BASE64` (the key file,
`base64 -w0 release.jks`), `KICKZWIFT_STORE_PASSWORD`, `KICKZWIFT_KEY_ALIAS` and
`KICKZWIFT_KEY_PASSWORD`.

To install, testers open the APK from the
[Releases](https://github.com/Engelnicolas/ZRKC-Zwift_Ride-Kickr_Core/releases) page on the
phone (Android 12 or later) and allow installs from that source. A debug build, signed with
another key, must be uninstalled first. [Obtainium](https://github.com/ImranR98/Obtainium)
can watch the page and offer updates.

**Dependencies.** Versions are pinned in `gradle/libs.versions.toml` and every artifact is
verified by its SHA-256 checksum (`gradle/verification-metadata.xml`). After a version bump,
run `./gradlew --write-verification-metadata sha256 --no-configuration-cache build`, then
build once with an empty `GRADLE_USER_HOME`, as the CI does: some metadata files are only
downloaded there. Review the diff before committing it.

**Tests and fuzzing.** The `:protocol` tests replay frames captured on the Ride and the
KICKR (`docs/captures/`, `docs/journaux/`). Jazzer targets live in `protocol/src/fuzz` and
run nightly in CI (`.github/workflows/fuzz.yml`).

## License

Copyright (C) 2026 Nicolas Engel

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU Affero General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the [LICENSE](LICENSE) file for details.
