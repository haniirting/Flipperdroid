# Flipper Desk (PC)

A desktop app for managing a Flipper, in the spirit of the original desktop app: dark theme with the Flipper orange, a sidebar, device cards with battery and storage bars, a drawn Flipper with a live screen and clickable buttons, and the official dolphin on the connect page (icons and dolphin frames come from the firmware assets in `assets/`). Two ways to connect:

1. **Flipper by USB cable.** Plug the Flipper into the PC (it shows up as a serial port) and press Connect.
2. **Phone by USB (adb).** The phone with the **Flipper Link** app is connected to the PC by USB and talks to the Flipper over Bluetooth.
   - Phone: turn on USB debugging; in Flipper Link connect to the Flipper, open **PC bridge (USB)** and press *Start bridge*.
   - PC: press *Set up phone (adb)* (runs `adb forward tcp:8765 tcp:8765`; needs Android platform-tools), choose *Phone by USB (adb)* and press Connect.

## Features
- Device: device and power info, storage space, protocol version, time, ping, play alert, sync time, unlock, reboot, backup / restore, factory reset
- Archive / Files: browse the SD card and internal storage, view files (summaries for .sub, .rfid, .ibtn, .nfc, .ir, BadUSB), run saved keys on the Flipper, download, upload, rename, delete, new folder, MD5
- Remote control: live screen and keys (arrows, Enter, Esc)
- GPIO: pin mode, read / write, pull up / down, 5 V on pin 1
- Apps: install a `.fap` from a GitHub link or from the PC and start it

## Run
```
pip install -r requirements.txt
python flipper_desk.py
```
Python 3.8+ with tkinter is required (included in the python.org installers). `pip install pyserial` is only needed for the USB cable mode.

On Linux you may need permission for the serial port (`sudo usermod -aG dialout $USER`, then log in again).

## Test without a Flipper
`python test_rpc.py` runs the protocol layer against a fake Flipper on localhost.

## Notes
- Protocol: Flipper RPC (protobuf, length-delimited). Over USB the app starts it with the CLI command `start_rpc_session`. Field numbers come from flipperzero-protobuf; the USB serial part and the GUI are not tested on real hardware.
- Not implemented: virtual display, app data exchange, tar extract, properties, firmware update.
- A `.fap` runs on your Flipper: install only add-ons you trust, built for your firmware version.
- License: GPLv3 (see the repository LICENSE). The Flipper name and logo are trademarks of Flipper Devices Inc.; this is an unofficial fan project.
