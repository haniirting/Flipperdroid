# Flipper Phone + Flipper Link

Two Android apps in one Gradle project (built together by GitHub Actions).

## Flipper Phone (`app`)
The phone as a pocket "Flipper" with only functions that exist in the original firmware:
- Dolphin: official animations, speech bubbles and XP/level logic from firmware 1.4.3 / dev (tap for another animation)
- NFC: identifies cards (NTAG213/215/216, Mifare Ultralight 11/21/C, Mifare Classic, ISO14443-4A), reads Ultralight/NTAG pages, saves Flipper `.nfc` files; no key attacks
- IR: NEC, NECext, Samsung32, RC5, SIRC/SIRC15/SIRC20, save and open Flipper `.ir` files (needs an IR blaster)
- Flipper icons from firmware assets

## Flipper Link (`companion`)
Connects to a real Flipper over Bluetooth LE and manages it, like the original mobile app:
- Device: device info, power info, ping, play alert, sync time
- Archive: NFC, Sub-GHz, RFID, iButton, Infrared, BadUSB key folders with a file viewer
- Files: SD card browser, import files from the phone (the destination folder is chosen by extension)
- Remote control: live screen and D-pad keys
- Apps: install a `.fap` from a GitHub link or from the phone and start it

Protocol: Flipper RPC over the BLE serial service (UUIDs from firmware serial_service_uuid.inc, messages from flipperzero-protobuf: flipper.proto, system.proto, storage.proto, gui.proto, application.proto).

## Not possible on a phone
Sub-GHz radio, 125 kHz RFID, GPIO, iButton, BadUSB (no such hardware).

## Build
Open in Android Studio, or push to GitHub: the Actions workflow builds both APKs
(`app/build/outputs/apk/debug/app-debug.apk` and `companion/build/outputs/apk/debug/companion-debug.apk`).

## License and credits
Dolphin animations, icons, file formats and XP thresholds come from https://github.com/flipperdevices/flipperzero-firmware, licensed GPLv3. Because of that, this project is distributed under GPLv3 too (see `LICENSE`). The Flipper name and logo are trademarks of Flipper Devices Inc.; this is an unofficial fan project.
