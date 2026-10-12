# Flipper Phone

Android app (Kotlin) with Flipper-style look: orange screen and the official Flipper dolphin animations with XP/level.

## Features
- Dolphin: official animations, speech bubbles and XP/level logic from Flipper firmware 1.4.3 (tap the dolphin for another animation). XP: NFC read/save +3, IR send +1, IR save +3, scans +1
- NFC: identifies cards (NTAG213/215/216, Mifare Ultralight 11/21/C, Mifare Classic, ISO14443-4A), reads all pages of Ultralight/NTAG cards and saves them as Flipper `.nfc` files; no key attacks on protected cards. Also reads UID, ATQA, SAK; save a Flipper-compatible `.nfc` file (ISO14443-3A, format v4) to Download/FlipperPhone
- IR: send NEC, NECext, Samsung32, RC5, SIRC/SIRC15/SIRC20 (timings from firmware lib/infrared), save a Flipper `.ir` file, open `.ir` files from Flipper and pick a signal (needs an IR blaster to send)
- Wi-Fi scanner (needs Location permission and GPS on)
- Bluetooth LE scanner

- Flipper (BLE): connect to a real Flipper over Bluetooth LE, browse the SD card, read files (Flipper RPC; UUIDs from firmware 1.4.3, message layout from flipperzero-protobuf)
- Add-ons: install from a GitHub link (single .html file, release asset, or a repo with addon.html / index.html), create, edit, import and run HTML + JavaScript programs inside the app. They can use Flipper.toast, Flipper.xp, Flipper.level, Flipper.irSend and an onNfc(uid) callback; network loading is blocked
- Install add-ons on the Flipper: paste a GitHub link (.fap file, blob/raw link, release asset or a repo with a release); the app downloads it, uploads it to /ext/apps/Misc and can start it (app_start_request)
- Remote control: live Flipper screen and D-pad keys over Bluetooth (gui.proto)
- Send files to the Flipper SD card (storage.proto); .fap files go to /ext/apps/Misc unless you are already inside /ext/apps
- File viewer: .sub, .rfid, .ibtn, .nfc, .ir and BadUSB scripts (view only for hardware-bound types)
- PC link: small Wi-Fi web server to download saved .nfc/.ir files on a computer

## Not possible on a phone
Sub-GHz radio, 125 kHz RFID, GPIO, iButton (no such hardware).

## Build
Open in Android Studio, or push to GitHub: the Actions workflow builds the APK.

## File formats
Based on Flipper firmware 1.4.3 (lib/nfc, applications/main/infrared). Copy saved files to the Flipper SD card (`nfc/` and `infrared/` folders).

## License and credits
Dolphin animations (`app/src/main/assets/dolphin`), file formats and XP thresholds come from https://github.com/flipperdevices/flipperzero-firmware (v1.4.3), licensed GPLv3. Because of that, this project is distributed under GPLv3 too (see `LICENSE`). The Flipper name and logo are trademarks of Flipper Devices Inc.; this is an unofficial fan project.

Flipper UI icons from firmware assets/icons are bundled in `app/src/main/assets/icons` (GPLv3, see License).
