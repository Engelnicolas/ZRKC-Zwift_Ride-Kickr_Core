# ZRKC - Zwift Ride & Kickr Core

Standalone Android app that links the Zwift Ride controllers to the KICKR CORE: 24 virtual
gears, free grade, GPX route or ERG, music and volume from the controllers.
Reference: `docs/Spécification technique — app Android Zwift Ride ↔ KICKR CORE.docx`;
documentation, Ride protocol and raw capture traces in `docs/`.

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

The interface is in English, and in French on a French-language phone. On Android 13 and
later, the language can also be chosen per app in Settings › Apps › Kick Zwift › Language.

1. Connections: allow Bluetooth and notifications, exclude the app from battery
   optimisation, tap Search, then Remember the Ride's left pod (it relays both controllers)
   and the KICKR. Only remembered devices are joined afterwards.
2. Connect the COROS watch directly to the KICKR, in Indoor Bike mode: the watch records
   the ride.
3. Prepare the ride, choose the mode, Start. During the ride, everything is controlled from
   the controllers; Finish needs a 2 s long press.

Default mapping: Shift up and Shift down on both controllers for gears, Up arrow and Down
arrow for grade (or the ERG target), Left and Right arrows for tracks, A play/pause, Y and B
volume, Z pause the ride. Everything can be changed in Settings.

## Modules

    protocol/   Pure Kotlin, no Android: Ride, KICKR and FTMS decoders, gestures, gears,
                grade profile, ride logic (RideSession), settings and their codecs, FIT
                encoder. Tested on the JVM against real frames.
    ble/        Android: links (DeviceLink, LinkSupervisor, reconnection), Nordic managers
                for the Ride, the KICKR and the FTMS fallback, scanning, TrainerWriter (the
                trainer's only writer). Link state machines and TrainerWriter tested on the JVM.
    app/        Android: RideService (foreground service), SessionController, phone
                actions, settings (DataStore), GPX routes, logs, the five screens.

## Building

Requirements: JDK 17 or newer, Android SDK with `platforms;android-37.0` and
`build-tools;36.0.0`, and a `local.properties` containing `sdk.dir=...` (not versioned).

    ./gradlew build              # tests, Android Lint, ktlint, detekt, debug and release APKs
    ./gradlew :protocol:test     # pure logic, no SDK needed at runtime
    ./gradlew ktlintFormat       # reformats the code
    ./gradlew :app:installDebug  # on the connected phone (USB debugging)

On the development machine:

    export JAVA_HOME=$HOME/.local/share/jdk-17.0.20.1+1

### Signed release APK

The key stays outside the repository. Once and for all:

    keytool -genkeypair -keystore ~/.config/kick-zwift/release.jks -alias kickzwift \
            -keyalg RSA -keysize 4096 -validity 10000

Then, for each release:

    export KICKZWIFT_STORE_FILE=~/.config/kick-zwift/release.jks
    export KICKZWIFT_STORE_PASSWORD=... KICKZWIFT_KEY_ALIAS=kickzwift KICKZWIFT_KEY_PASSWORD=...
    ./gradlew :app:assembleRelease
    ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk

Without these variables, the release APK (`app-release-unsigned.apk`) is built unsigned.

## Test vectors

The `:protocol` tests replay the frames captured on the Ride and the KICKR
(`docs/captures/`, `docs/journaux/`). The expected result comes from the names given during
the guided capture, not from a decoder. After a new capture (`../prototype/capture_guidee.py`):

    ../.venv/bin/python ../prototype/export_vectors.py

## Fuzzing

Jazzer targets in `protocol/src/fuzz`: decoders for received frames and read files.
The nightly CI (`.github/workflows/fuzz.yml`) runs them for 10 min in total. Locally:

    ./gradlew :protocol:writeFuzzClasspath
    jazzer --cp="$(cat protocol/build/fuzz-classpath.txt)" \
           --target_class=dev.kickzwift.protocol.fuzz.RideDecoderFuzz -max_total_time=60

## Dependencies

Versions pinned in `gradle/libs.versions.toml`. Each artifact is verified by its SHA-256
checksum (`gradle/verification-metadata.xml`). After a version bump:

    ./gradlew --write-verification-metadata sha256 --no-configuration-cache build

then review the file's diff before committing it.
