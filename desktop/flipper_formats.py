"""Human-readable summaries of Flipper files (.sub, .rfid, .ibtn, .nfc, .ir, BadUSB scripts)."""


def hex_bytes(v):
    out = []
    for s in (v or "").split():
        try:
            out.append(int(s, 16))
        except ValueError:
            pass
    return out


def dallas_crc(data):
    """Dallas / Maxim CRC-8 (polynomial 0x8C, reflected) used by iButton ROM codes."""
    crc = 0
    for b in data:
        x = b
        for _ in range(8):
            mix = (crc ^ x) & 1
            crc >>= 1
            if mix:
                crc ^= 0x8C
            x >>= 1
    return crc


def summarize(name, text):
    lower = name.lower()
    kv = {}
    for raw in text.splitlines():
        l = raw.strip()
        if not l or l.startswith("#") or ":" not in l:
            continue
        k, v = l.split(":", 1)
        kv.setdefault(k.strip(), v.strip())
    out = []
    if lower.endswith(".sub"):
        out.append("Sub-GHz file")
        try:
            out.append("Frequency: %.2f MHz" % (int(kv["Frequency"]) / 1e6))
        except (KeyError, ValueError):
            pass
        for k in ("Preset", "Protocol", "Bit", "Key", "TE"):
            if k in kv:
                out.append("%s: %s" % (k, kv[k]))
        raw_lines = [l for l in text.splitlines() if l.startswith("RAW_Data:")]
        if raw_lines:
            n = sum(len([t for t in l.split(":", 1)[1].split() if t.lstrip("-").isdigit()]) for l in raw_lines)
            out.append("RAW: %d samples in %d lines" % (n, len(raw_lines)))
        out.append("\nRun it from the file list to transmit it with the Flipper.")
    elif lower.endswith(".rfid"):
        out.append("RFID 125 kHz key")
        out += ["%s: %s" % kvp for kvp in list(kv.items())[:12]]
        data = hex_bytes(kv.get("Data"))
        # same layout as the firmware renders EM4100: FC = byte 2, card = bytes 3..4
        if kv.get("Key type") == "EM4100" and len(data) >= 5:
            out.append("\nFC: %03d\nCard: %05d" % (data[2], (data[3] << 8) | data[4]))
        out.append("\nRun it from the file list to emulate this key on the Flipper.")
    elif lower.endswith(".ibtn"):
        out.append("iButton key")
        out += ["%s: %s" % kvp for kvp in list(kv.items())[:12]]
        rom = hex_bytes(kv.get("Rom Data") or kv.get("Data"))
        if len(rom) == 8:
            crc = dallas_crc(rom[:7])
            out.append("\nFamily code: %02X\nSerial: %s\nCRC: %s" % (
                rom[0], " ".join("%02X" % b for b in rom[1:7]),
                "valid" if crc == rom[7] else "wrong (expected %02X)" % crc))
        out.append("\nRun it from the file list to emulate this key on the Flipper.")
    elif lower.endswith(".nfc"):
        out.append("NFC card")
        out += ["%s: %s" % kvp for kvp in list(kv.items()) if not kvp[0].startswith(("Block", "Page"))][:12]
    elif lower.endswith(".ir"):
        names = [l.strip().split(":", 1)[1].strip() for l in text.splitlines() if l.strip().startswith("name:")]
        out.append("Infrared file: %d signals" % len(names))
        out += ["- " + n for n in names[:15]]
    elif lower.endswith(".txt") and any(w in text for w in ("STRING", "REM ", "DELAY")):
        lines = text.splitlines()
        out.append("BadUSB script: %d lines" % len(lines))
        out.append("Comments: %d, typed strings: %d" % (
            sum(1 for l in lines if l.startswith("REM")), sum(1 for l in lines if l.startswith("STRING"))))
    else:
        out += ["%s: %s" % kvp for kvp in list(kv.items())[:15]]
    return "\n".join(out)
