# Flipper Phone

Android app (Kotlin) inspired by Flipper Zero features, tested target: MIUI Global 12.0.14 (Android 10/11).

## Features
- NFC: read tag/card UID and tech list, save UID to `uids.txt`
- IR: send NEC commands (requires a phone with an IR blaster)

## Build
1. Open the folder in Android Studio (File -> Open)
2. Wait for Gradle sync
3. Connect the phone (USB debugging on) and press Run

## Notes
- NFC and IR are optional; the app installs without them
- This is not a port of the Flipper Zero firmware; it reimplements a few features
