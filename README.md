# Flipper Phone + Flipper Link

Two Android apps in one Gradle project (built together by GitHub Actions).

## Flipper Phone (`app`)
The phone as a pocket "Flipper". The main menu has the same sections, in the same order, as the original Flipper menu:
Archive, Sub-GHz, 125 kHz RFID, NFC, Infrared, GPIO, iButton, Bad USB, U2F, Settings.
- Dolphin: official animations, speech bubbles, XP/level logic and the deed weights with the 20 XP per app daily limit; internal animations (low battery, no storage) and the SD ok / SD bad animations after saving a file
- Archive: keys saved by the app (.nfc, .ir) with a viewer
- NFC: identifies cards (NTAG213/215/216, Mifare Ultralight 11/21/C, Mifare Classic, ISO14443-4A), reads Ultralight/NTAG pages, saves Flipper `.nfc` files; no key attacks
- Infrared: remotes with a device type, brand and model, a standard remote layout (Power, Vol, Ch, arrows, OK, Back, Menu, digits) and the other buttons of the file; built-in common codes for Samsung, LG, Sony and Philips TVs or import a `.ir` file for exact codes of a model; shows whether the phone has an IR transmitter and its carrier ranges, and lets you pick the carrier frequency; all 14 firmware protocols (NEC, NECext, NEC42, NEC42ext, Samsung32, RC5, RC5X, RC6, SIRC, SIRC15, SIRC20, Kaseikyo, Pioneer, RCA) plus RAW signals; manual signal screen for advanced use
- Sub-GHz, 125 kHz RFID, iButton, Bad USB: no such hardware in a phone, so these sections explain that and open `.sub`, `.rfid`, `.ibtn` files and scripts in a viewer (frequency and protocol, EM4100 card numbers, iButton CRC check)
- GPIO, U2F: not possible on a phone (use Flipper Link for a real Flipper)
- Apps: add-ons are single `.html` files that run in the app and can send IR and read NFC; create them in the editor, import a file or install from a GitHub link (see `ADDONS.md`)
- Settings: reset the dolphin, delete saved keys, about
- Flipper icons from firmware assets

## Flipper Link (`companion`)
Connects to a real Flipper over Bluetooth LE and uses the whole Flipper RPC command set that the protocol files describe:
- Device: device info, power info, protocol version, Flipper time, storage space (SD and internal), ping, play alert, sync time, unlock screen, reboot, backup / restore of internal storage, factory reset (double confirmation)
- Archive: NFC, Sub-GHz, RFID, iButton, Infrared, BadUSB key folders; view a file, run a key on the Flipper (emulate NFC / RFID / iButton, send Sub-GHz and Infrared signals), rename, delete, MD5
- Files: SD card browser, new folder, rename, delete, import files from the phone (destination folder by extension)
- Remote control: live screen and D-pad keys; Stop app button
- GPIO: pin mode, read, write, pull up / down, 5 V (OTG) on pin 1
- Apps: install a `.fap` from a GitHub link or from the phone and start it

Protocol: Flipper RPC over the BLE serial service (UUIDs from firmware serial_service_uuid.inc, messages from flipperzero-protobuf: flipper.proto, system.proto, storage.proto, gui.proto, application.proto, gpio.proto, desktop.proto). Not implemented: virtual display, app data exchange, tar extract, properties, firmware update.

## Flipper Desk (`desktop`)
A PC app (Python, tkinter) with the same Flipper management features, connected either to the Flipper by USB cable or to a phone running Flipper Link (phone connected to the PC by USB, `adb forward`). See `desktop/README.md`. A GitHub workflow (`build-pc.yml`) builds `FlipperDesk.exe` for Windows.

## Not possible on a phone
Sub-GHz radio, 125 kHz RFID, GPIO, iButton, BadUSB (no such hardware).

## Build
Open in Android Studio, or push to GitHub: the Actions workflow builds both APKs
(`app/build/outputs/apk/debug/app-debug.apk` and `companion/build/outputs/apk/debug/companion-debug.apk`).

## License and credits
Dolphin animations, icons, file formats and XP thresholds come from https://github.com/flipperdevices/flipperzero-firmware, licensed GPLv3. Because of that, this project is distributed under GPLv3 too (see `LICENSE`). The Flipper name and logo are trademarks of Flipper Devices Inc.; this is an unofficial fan project.
