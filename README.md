# Flipper Phone

Android app (Kotlin) with Flipper-style look: orange screen, pixel dolphin with XP/level.

## Features
- Dolphin mascot (grows with use: each scan/read/send gives XP)
- NFC: read UID and tag technologies, save UID to `uids.txt`
- IR: send NEC commands (needs a phone with an IR blaster)
- Wi-Fi scanner (needs Location permission and GPS on)
- Bluetooth LE scanner

## Not possible on a phone
Sub-GHz radio, 125 kHz RFID, GPIO, iButton (no such hardware).

## Build
Open in Android Studio, or push to GitHub: the Actions workflow builds the APK.
