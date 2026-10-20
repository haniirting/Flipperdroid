# Add-ons for Flipper Phone ("Apps" section)

An add-on is **one `.html` file** (HTML + CSS + JavaScript). It runs inside the app in a WebView and can use a global
object `Flipper`. There are no external files: put CSS and JavaScript inside the same `.html`.

## How to add one
- **New add-on**: the built-in editor with a working template (Save, Save + Run).
- **Install from GitHub link**: paste a link to
  - a file page: `github.com/user/repo/blob/main/addon.html`
  - a raw link: `raw.githubusercontent.com/...`
  - a release file: `github.com/user/repo/releases/download/v1/addon.html`
  - a repo or folder (`/tree/main/folder`): the app looks for `addon.html`, `index.html` or `main.html`
    (for a repo, the latest release is checked first).
- **Import .html file** from the phone.

Tap an add-on in the list: Run, Edit, Delete.

## What the add-on can use
| Call | What it does |
| --- | --- |
| `Flipper.toast("text")` | short message |
| `Flipper.hasIr()` | `true` if the phone has an IR transmitter |
| `Flipper.irSend("NEC", "04", "08")` | send a protocol signal (protocol, address hex, command hex); returns `true` if sent. Protocols: NEC, NECext, NEC42, NEC42ext, Samsung32, RC5, RC5X, RC6, SIRC, SIRC15, SIRC20, Kaseikyo, Pioneer, RCA |
| `Flipper.irSendRaw(38000, "9000 4500 560 560 ...")` | send raw timings in microseconds (mark first), carrier 20000..60000 Hz, at most 2000 values |
| `Flipper.level()`, `Flipper.getXp()` | read the dolphin level and XP (read only: add-ons cannot give XP) |
| `function onNfc(uid) { ... }` | you define it; it is called with the card UID (hex, no spaces) when a card touches the back of the phone |

## Limits and safety
- Network loading is blocked: no images, scripts or requests from the internet, no `fetch`.
- No file access, no storage of data between runs except browser `localStorage` inside the page, which may be cleared.
- An add-on can send IR signals and read NFC UIDs, so install only add-ons you trust or have read (Edit shows the code).
- A phone has no Sub-GHz, RFID or GPIO hardware, so add-ons cannot use them.

## Minimal example
```html
<!DOCTYPE html>
<html><body style="font-family:monospace">
<h3>TV power</h3>
<button onclick="Flipper.irSend('NEC','04','08')">Power</button>
<p id="c">Hold a card...</p>
<script>function onNfc(uid){ document.getElementById('c').textContent = 'Card: ' + uid; }</script>
</body></html>
```

## Not the same as Flipper `.fap` apps
`.fap` files are native apps for a real Flipper. They are installed with Flipper Link or Flipper Desk, not here.
