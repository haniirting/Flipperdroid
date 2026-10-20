"""Flipper Desk: manage a Flipper from a PC, in the spirit of the original desktop app.

Connect either the Flipper itself with a USB cable, or an Android phone with the Flipper Link app
(phone connected to the PC by USB, the phone talks to the Flipper over Bluetooth).
"""
import datetime
import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import flipper_formats
import flipper_rpc as rpc
import github_installer

# ---------- look ----------
T = {
    "bg": "#161616", "panel": "#212121", "panel2": "#2B2B2B", "line": "#3A3A3A",
    "text": "#F2F2F2", "muted": "#9A9A9A", "accent": "#FF8200", "accent_hover": "#FF9A2E",
    "ok": "#3DDC84", "bad": "#FF5252", "screen": "#FF8200", "ink": "#000000",
}
UI = ("Segoe UI", 10)
UI_B = ("Segoe UI", 10, "bold")
UI_S = ("Segoe UI", 9)
H1 = ("Segoe UI", 20, "bold")
H2 = ("Segoe UI", 13, "bold")
MONO = ("Consolas", 10)

ADB_PORT = 8765
SCALE = 3                                   # Flipper screen scale in the remote control
QUICK = [("NFC", "/ext/nfc"), ("Sub-GHz", "/ext/subghz"), ("RFID", "/ext/lfrfid"), ("iButton", "/ext/ibutton"),
         ("Infrared", "/ext/infrared"), ("BadUSB", "/ext/badusb"), ("Apps", "/ext/apps"), ("SD card", "/ext"),
         ("Internal", "/int")]
PINS = [("PC0", "header pin 16", 0), ("PC1", "header pin 15", 1), ("PC3", "header pin 7", 2),
        ("PB2", "header pin 6", 3), ("PB3", "header pin 5", 4), ("PA4", "header pin 4", 5),
        ("PA6", "header pin 3", 6), ("PA7", "header pin 2", 7)]
APP_BY_EXT = {"nfc": "NFC", "sub": "Sub-GHz", "rfid": "125 kHz RFID", "ibtn": "iButton", "ir": "Infrared"}
DIR_BY_EXT = {"nfc": "/ext/nfc", "sub": "/ext/subghz", "rfid": "/ext/lfrfid", "ibtn": "/ext/ibutton",
              "ir": "/ext/infrared", "fap": "/ext/apps/Misc"}
NAV = [("connect", "Connect", "subghz"), ("device", "Device", "device"), ("files", "Archive & Files", "files"),
       ("remote", "Remote control", "remote"), ("gpio", "GPIO", "gpio"), ("apps", "Apps", "apps")]
KEYS = {"up": 0, "down": 1, "right": 2, "left": 3, "ok": 4, "back": 5}


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, "assets", name)


def load_img(name, zoom=1):
    try:
        img = tk.PhotoImage(file=resource(name))
        return img.zoom(zoom, zoom) if zoom > 1 else img
    except Exception:
        return None


def apply_style(root):
    st = ttk.Style(root)
    try:
        st.theme_use("clam")
    except tk.TclError:
        pass
    st.configure("Treeview", background=T["panel"], fieldbackground=T["panel"], foreground=T["text"],
                 rowheight=28, borderwidth=0, font=UI)
    st.map("Treeview", background=[("selected", T["accent"])], foreground=[("selected", "#000000")])
    st.configure("Treeview.Heading", background=T["panel2"], foreground=T["muted"], relief="flat", font=UI_B)
    st.map("Treeview.Heading", background=[("active", T["panel2"])])
    st.configure("TCombobox", fieldbackground=T["panel2"], background=T["panel2"], foreground=T["text"],
                 arrowcolor=T["text"], bordercolor=T["line"], lightcolor=T["panel2"], darkcolor=T["panel2"])
    st.map("TCombobox", fieldbackground=[("readonly", T["panel2"])], foreground=[("readonly", T["text"])],
           selectbackground=[("readonly", T["panel2"])], selectforeground=[("readonly", T["text"])])
    st.configure("Flipper.Horizontal.TProgressbar", troughcolor=T["panel2"], background=T["accent"],
                 bordercolor=T["panel"], lightcolor=T["accent"], darkcolor=T["accent"])
    st.configure("Vertical.TScrollbar", background=T["panel2"], troughcolor=T["panel"], bordercolor=T["panel"],
                 arrowcolor=T["muted"])
    root.option_add("*TCombobox*Listbox.background", T["panel2"])
    root.option_add("*TCombobox*Listbox.foreground", T["text"])
    root.option_add("*TCombobox*Listbox.selectBackground", T["accent"])
    root.option_add("*TCombobox*Listbox.selectForeground", "#000000")


# ---------- widgets ----------
class Btn(tk.Label):
    """Flat button with a hover effect. kind: accent, ghost or danger."""
    KINDS = {"accent": (T["accent"], "#000000", T["accent_hover"]),
             "ghost": (T["panel2"], T["text"], T["line"]),
             "danger": ("#3A1F1F", "#FF8A8A", "#5A2A2A")}

    def __init__(self, parent, text, command, kind="ghost", **kw):
        bg, fg, hover = self.KINDS[kind]
        super().__init__(parent, text=text, bg=bg, fg=fg, font=UI_B, padx=14, pady=7, cursor="hand2", **kw)
        self.command = command
        self.base_bg = bg
        self.hover_bg = hover
        self.bind("<Enter>", lambda e: self.config(bg=self.hover_bg))
        self.bind("<Leave>", lambda e: self.config(bg=self.base_bg))
        self.bind("<Button-1>", lambda e: self.command())


class Card(tk.Frame):
    def __init__(self, parent, title=None, **kw):
        super().__init__(parent, bg=T["panel"], highlightbackground=T["line"], highlightthickness=1, **kw)
        if title:
            tk.Label(self, text=title, bg=T["panel"], fg=T["muted"], font=UI_B, anchor="w").pack(
                fill="x", padx=16, pady=(12, 2))
        self.body = tk.Frame(self, bg=T["panel"])
        self.body.pack(fill="both", expand=True, padx=16, pady=(4, 14))


def lbl(parent, text, font=UI, color=None, **kw):
    return tk.Label(parent, text=text, bg=kw.pop("bg", T["panel"]), fg=color or T["text"], font=font, **kw)


def entry(parent, var, width=40):
    return tk.Entry(parent, textvariable=var, bg=T["panel2"], fg=T["text"], insertbackground=T["text"],
                    relief="flat", font=UI, width=width, highlightthickness=1, highlightbackground=T["line"],
                    highlightcolor=T["accent"])


def textbox(parent, **kw):
    return tk.Text(parent, bg=T["panel2"], fg=T["text"], insertbackground=T["text"], relief="flat",
                   font=MONO, highlightthickness=1, highlightbackground=T["line"], **kw)


def dark_window(parent, title, size):
    win = tk.Toplevel(parent)
    win.title(title)
    win.configure(bg=T["bg"])
    win.geometry(size)
    return win


class PickDialog(tk.Toplevel):
    """List dialog; calls on_pick(index) after a double click or OK."""

    def __init__(self, parent, title, items, on_pick):
        super().__init__(parent)
        self.title(title)
        self.configure(bg=T["bg"])
        self.geometry("460x420")
        self.on_pick = on_pick
        self.box = tk.Listbox(self, font=UI, bg=T["panel"], fg=T["text"], selectbackground=T["accent"],
                              selectforeground="#000000", relief="flat", highlightthickness=0, activestyle="none")
        self.box.pack(fill="both", expand=True, padx=14, pady=14)
        for it in items:
            self.box.insert("end", it)
        self.box.bind("<Double-Button-1>", lambda e: self.ok())
        Btn(self, "OK", self.ok, kind="accent").pack(pady=(0, 14))

    def ok(self):
        sel = self.box.curselection()
        if sel:
            idx = sel[0]
            self.destroy()
            self.on_pick(idx)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Flipper Desk")
        self.configure(bg=T["bg"])
        self.geometry("1120x740")
        self.minsize(1000, 660)
        apply_style(self)
        self.fl = None
        self.path = "/ext"
        self.entries = {}
        self.port_map = {}
        self.screen_on = False
        self.screen_img = None
        self.page_name = "connect"
        self.status = tk.StringVar(value="Not connected")
        self.mode = tk.StringVar(value="usb")
        self.port_var = tk.StringVar()
        self.path_var = tk.StringVar(value="/ext")
        self.link_var = tk.StringVar()
        self.dolphin = [load_img("dolphin_0.png", 3), load_img("dolphin_1.png", 3)]
        self.dolphin_i = 0
        self._build_shell()
        self.show_page("connect")
        self.refresh_ports()
        self._animate_dolphin()

    # ---------- helpers ----------
    def bg(self, fn, done=None):
        def run():
            try:
                result = fn()
            except Exception as e:           # shown in the status bar
                msg = str(e) or e.__class__.__name__
                self.after(0, lambda: self.set_status("Error: " + msg))
                return
            if done:
                self.after(0, lambda: done(result))
        threading.Thread(target=run, daemon=True).start()

    def set_status(self, text):
        self.status.set(text)

    def need_flipper(self):
        if self.fl is None:
            self.show_page("connect")
            self.set_status("Connect to a Flipper first")
            return False
        return True

    # ---------- shell: sidebar, header, pages ----------
    def _build_shell(self):
        side = tk.Frame(self, bg=T["panel"], width=220)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        logo = tk.Frame(side, bg=T["panel"])
        logo.pack(fill="x", padx=20, pady=(22, 18))
        tk.Label(logo, text="FLIPPER", bg=T["panel"], fg=T["accent"], font=("Segoe UI", 18, "bold")).pack(anchor="w")
        tk.Label(logo, text="Desk", bg=T["panel"], fg=T["muted"], font=("Segoe UI", 11)).pack(anchor="w")

        self.nav = {}
        for key, text, icon in NAV:
            row = tk.Frame(side, bg=T["panel"], cursor="hand2")
            row.pack(fill="x", pady=1)
            bar = tk.Frame(row, bg=T["panel"], width=4)
            bar.pack(side="left", fill="y")
            img_off, img_on = load_img(icon + ".png", 2), load_img(icon + "_on.png", 2)
            ico = tk.Label(row, image=img_off, bg=T["panel"]) if img_off else tk.Label(row, text=" ", bg=T["panel"])
            ico.pack(side="left", padx=(14, 10), pady=10)
            name = tk.Label(row, text=text, bg=T["panel"], fg=T["muted"], font=UI_B, anchor="w")
            name.pack(side="left", fill="x", expand=True)
            self.nav[key] = (row, bar, ico, name, img_off, img_on)
            for w in (row, bar, ico, name):
                w.bind("<Button-1>", lambda e, k=key: self.show_page(k))
        tk.Label(side, text="Unofficial fan project", bg=T["panel"], fg=T["muted"], font=UI_S).pack(
            side="bottom", pady=14)

        main = tk.Frame(self, bg=T["bg"])
        main.pack(side="left", fill="both", expand=True)
        head = tk.Frame(main, bg=T["bg"])
        head.pack(fill="x", padx=28, pady=(22, 6))
        self.title_lbl = tk.Label(head, text="Connect", bg=T["bg"], fg=T["text"], font=H1)
        self.title_lbl.pack(side="left")
        right = tk.Frame(head, bg=T["bg"])
        right.pack(side="right")
        self.pill = tk.Label(right, text="●  Not connected", bg=T["bg"], fg=T["bad"], font=UI_B)
        self.pill.pack(side="left", padx=10)
        self.stop_btn = Btn(right, "Stop app", self.stop_app, kind="danger")
        self.disc_btn = Btn(right, "Disconnect", self.disconnect)

        self.content = tk.Frame(main, bg=T["bg"])
        self.content.pack(fill="both", expand=True, padx=28, pady=(6, 8))
        tk.Label(main, textvariable=self.status, bg=T["panel"], fg=T["muted"], font=UI_S, anchor="w",
                 padx=16, pady=6).pack(fill="x", side="bottom")

        self.pages = {}
        for key, _, _ in NAV:
            f = tk.Frame(self.content, bg=T["bg"])
            f.place(x=0, y=0, relwidth=1, relheight=1)
            self.pages[key] = f
        self._build_connect(self.pages["connect"])
        self._build_device(self.pages["device"])
        self._build_files(self.pages["files"])
        self._build_remote(self.pages["remote"])
        self._build_gpio(self.pages["gpio"])
        self._build_apps(self.pages["apps"])
        self.bind("<Key>", self.on_key)

    def show_page(self, key):
        if key != "connect" and self.fl is None:
            key = "connect"
            self.status.set("Connect to a Flipper first")
        self.page_name = key
        self.pages[key].tkraise()
        titles = {k: t for k, t, _ in NAV}
        self.title_lbl.config(text=titles[key])
        for k, (row, bar, ico, name, off, on) in self.nav.items():
            active = k == key
            bgc = T["panel2"] if active else T["panel"]
            for w in (row, ico, name):
                w.config(bg=bgc)
            bar.config(bg=T["accent"] if active else T["panel"])
            name.config(fg=T["accent"] if active else T["muted"])
            img = on if active else off
            if img:
                ico.config(image=img)

    def _animate_dolphin(self):
        frames = [f for f in self.dolphin if f]
        if frames and hasattr(self, "dolphin_canvas"):
            self.dolphin_i = (self.dolphin_i + 1) % len(frames)
            self.dolphin_canvas.itemconfig(self.dolphin_item, image=frames[self.dolphin_i])
        self.after(700, self._animate_dolphin)

    def _set_connected(self, on):
        if on:
            self.pill.config(text="●  Connected", fg=T["ok"])
            self.stop_btn.pack(side="left", padx=4)
            self.disc_btn.pack(side="left", padx=4)
        else:
            self.pill.config(text="●  Not connected", fg=T["bad"])
            self.stop_btn.pack_forget()
            self.disc_btn.pack_forget()

    # ---------- connect page ----------
    def _build_connect(self, page):
        card = Card(page)
        card.place(relx=0.5, rely=0.46, anchor="center")
        body = card.body
        c = tk.Canvas(body, width=400, height=170, bg=T["panel"], highlightthickness=0)
        c.pack(pady=(10, 0))
        self.dolphin_canvas = c
        first = next((f for f in self.dolphin if f), None)
        self.dolphin_item = c.create_image(200, 85, image=first)
        tk.Label(body, text="Connect your Flipper", bg=T["panel"], fg=T["text"], font=H2).pack(pady=(8, 2))
        tk.Label(body, text="Choose how the Flipper is connected to this computer", bg=T["panel"],
                 fg=T["muted"], font=UI).pack(pady=(0, 12))
        for text, val in (("Flipper with a USB cable", "usb"), ("Phone connected by USB (Flipper Link app)", "adb")):
            tk.Radiobutton(body, text=text, variable=self.mode, value=val, bg=T["panel"], fg=T["text"], font=UI,
                           activebackground=T["panel"], activeforeground=T["text"], selectcolor=T["panel2"],
                           command=self.refresh_ports).pack(anchor="w", padx=40)
        row = tk.Frame(body, bg=T["panel"])
        row.pack(pady=14)
        self.port_box = ttk.Combobox(row, textvariable=self.port_var, width=34, state="readonly")
        self.port_box.pack(side="left", padx=4)
        Btn(row, "Refresh", self.refresh_ports).pack(side="left", padx=4)
        Btn(row, "Set up phone (adb)", self.setup_adb).pack(side="left", padx=4)
        self.connect_btn = Btn(body, "Connect", self.toggle_connect, kind="accent")
        self.connect_btn.pack(pady=(4, 6), ipadx=40)
        tk.Label(body, text="Phone: in Flipper Link open \"PC bridge (USB)\" and press Start bridge first.",
                 bg=T["panel"], fg=T["muted"], font=UI_S).pack(pady=(4, 10))

    def refresh_ports(self):
        if self.mode.get() == "usb":
            ports = rpc.list_serial_ports()
            self.port_map = {text: dev for dev, text, _ in ports}
            self.port_box["values"] = list(self.port_map.keys())
            if self.port_map:
                self.port_box.current(0)
            else:
                self.port_var.set("")
                self.set_status("No serial ports found. Plug in the Flipper (pyserial must be installed).")
        else:
            self.port_box["values"] = ["Phone: localhost:%d" % ADB_PORT]
            self.port_box.current(0)

    def setup_adb(self):
        try:
            r = subprocess.run(["adb", "forward", "tcp:%d" % ADB_PORT, "tcp:%d" % ADB_PORT],
                               capture_output=True, text=True, timeout=20)
            out = (r.stdout + r.stderr).strip() or "done"
            messagebox.showinfo("adb", "adb forward tcp:%d tcp:%d\n\n%s\n\nNow start the PC bridge in the Flipper Link "
                                "app on the phone, choose \"Phone connected by USB\" and press Connect." % (ADB_PORT, ADB_PORT, out))
        except FileNotFoundError:
            messagebox.showerror("adb", "adb was not found.\nInstall Android platform-tools, enable USB debugging "
                                 "on the phone and run:\n\nadb forward tcp:%d tcp:%d" % (ADB_PORT, ADB_PORT))
        except Exception as e:
            messagebox.showerror("adb", str(e))

    def toggle_connect(self):
        if self.fl is not None:
            self.disconnect()
            return
        if self.mode.get() == "usb":
            port = self.port_map.get(self.port_var.get())
            if not port:
                messagebox.showinfo("Flipper Desk", "Choose the Flipper serial port first.")
                return
            transport = rpc.SerialTransport(port)
        else:
            transport = rpc.TcpTransport("127.0.0.1", ADB_PORT)
        self.set_status("Connecting...")

        def work():
            fl = rpc.Flipper(transport)
            try:
                fl.open()
                fl.ping()
            except Exception:
                fl.close()
                raise
            return fl

        def done(fl):
            self.fl = fl
            fl.on_close = lambda: self.after(0, self.on_lost)
            self._set_connected(True)
            self.set_status("Connected")
            self.show_page("device")
            self.refresh_device()
            self.list_path("/ext")

        self.bg(work, done)

    def disconnect(self):
        if self.fl is not None:
            self.fl.close()
        self.fl = None
        self.screen_on = False
        self._set_connected(False)
        self.show_page("connect")
        self.set_status("Disconnected")

    def on_lost(self):
        self.fl = None
        self.screen_on = False
        self._set_connected(False)
        self.show_page("connect")
        self.set_status("Connection lost")

    def stop_app(self):
        if self.need_flipper():
            self.fl.app_exit()
            self.set_status("Stop sent")

    # ---------- device page ----------
    def _build_device(self, page):
        page.columnconfigure(0, weight=1, uniform="c")
        page.columnconfigure(1, weight=1, uniform="c")
        self.card_dev = Card(page, "FLIPPER")
        self.card_dev.grid(row=0, column=0, sticky="nsew", padx=(0, 8), pady=(0, 12))
        self.card_pow = Card(page, "POWER")
        self.card_pow.grid(row=0, column=1, sticky="nsew", padx=(8, 0), pady=(0, 12))
        self.card_sto = Card(page, "STORAGE")
        self.card_sto.grid(row=1, column=0, sticky="nsew", padx=(0, 8), pady=(0, 12))
        act = Card(page, "ACTIONS")
        act.grid(row=1, column=1, sticky="nsew", padx=(8, 0), pady=(0, 12))
        buttons = (("Refresh", self.refresh_device, "ghost"), ("Ping", self.ping, "ghost"),
                   ("Play alert", self.alert, "ghost"), ("Sync time", self.sync_time, "ghost"),
                   ("Unlock screen", self.unlock, "ghost"), ("Reboot", self.reboot, "ghost"),
                   ("Back up to SD", self.backup, "ghost"), ("Restore backup", self.restore, "ghost"),
                   ("Factory reset", self.factory_reset, "danger"))
        for i, (text, cmd, kind) in enumerate(buttons):
            Btn(act.body, text, cmd, kind=kind).grid(row=i // 2, column=i % 2, padx=4, pady=4, sticky="ew")
        act.body.columnconfigure(0, weight=1)
        act.body.columnconfigure(1, weight=1)
        page.rowconfigure(0, weight=1)
        page.rowconfigure(1, weight=1)

    @staticmethod
    def clear(frame):
        for w in frame.winfo_children():
            w.destroy()

    def fill_rows(self, card, pairs, empty="(no data)"):
        self.clear(card.body)
        if not pairs:
            lbl(card.body, empty, color=T["muted"]).pack(anchor="w")
        for k, v in pairs:
            row = tk.Frame(card.body, bg=T["panel"])
            row.pack(fill="x", pady=1)
            lbl(row, k, font=UI_S, color=T["muted"], width=24, anchor="w").pack(side="left")
            lbl(row, v, font=UI, wraplength=230, justify="left", anchor="w").pack(side="left", fill="x")

    def add_bar(self, card, title, used, total):
        wrap = tk.Frame(card.body, bg=T["panel"])
        wrap.pack(fill="x", pady=8)
        lbl(wrap, title, font=UI_B).pack(anchor="w")
        pct = 0 if total <= 0 else 100.0 * (total - used) / total
        ttk.Progressbar(wrap, style="Flipper.Horizontal.TProgressbar", maximum=100, value=pct).pack(fill="x", pady=4)
        mb = lambda x: "%.1f MB" % (x / 1048576.0)
        lbl(wrap, "%s used of %s" % (mb(total - used), mb(total)) if total else "not available",
            font=UI_S, color=T["muted"]).pack(anchor="w")

    def refresh_device(self):
        if not self.need_flipper():
            return
        fl = self.fl

        def work():
            out = {}
            for key, fn in (("dev", fl.device_info), ("pow", fl.power_info),
                            ("ext", lambda: fl.storage_info("/ext")), ("int", lambda: fl.storage_info("/int")),
                            ("proto", fl.protobuf_version), ("time", fl.datetime), ("lock", fl.app_locked)):
                try:
                    out[key] = fn()
                except Exception as e:
                    out[key] = None
            return out

        def done(d):
            dev = list(d["dev"] or [])
            extra = []
            if d.get("proto"):
                extra.append(("Protocol version", d["proto"]))
            if d.get("time"):
                extra.append(("Flipper time", d["time"]))
            if d.get("lock") is not None:
                extra.append(("App running in RPC mode", "yes" if d["lock"] else "no"))
            self.fill_rows(self.card_dev, dev + extra, "Device info is not available")
            self.fill_rows(self.card_pow, list(d["pow"] or []), "Power info is not available")
            self.clear(self.card_sto.body)
            for title, key in (("SD card", "ext"), ("Internal storage", "int")):
                if d.get(key):
                    total, free = d[key]
                    self.add_bar(self.card_sto, title, free, total)
                else:
                    lbl(self.card_sto.body, "%s: not available" % title, color=T["muted"]).pack(anchor="w", pady=6)
            self.set_status("Device info updated")

        self.bg(work, done)

    def ping(self):
        if self.need_flipper():
            self.bg(self.fl.ping, lambda _: self.set_status("Ping OK"))

    def alert(self):
        if self.need_flipper():
            self.fl.play_alert()
            self.set_status("Alert sent")

    def sync_time(self):
        if self.need_flipper():
            self.bg(lambda: self.fl.set_datetime(datetime.datetime.now()), lambda _: self.set_status("Time synced"))

    def unlock(self):
        if self.need_flipper():
            self.fl.unlock()
            self.set_status("Unlock sent")

    def reboot(self):
        if self.need_flipper() and messagebox.askyesno("Reboot", "Reboot the Flipper?"):
            self.fl.reboot(0)
            self.set_status("Reboot sent")

    def backup(self):
        if self.need_flipper():
            self.set_status("Creating backup...")
            self.bg(lambda: self.fl.backup_create("/ext/backup.tar"),
                    lambda _: self.set_status("Backup saved: /ext/backup.tar"))

    def restore(self):
        if self.need_flipper() and messagebox.askyesno(
                "Restore", "Restore internal storage from /ext/backup.tar? Current data will be replaced."):
            self.set_status("Restoring...")
            self.bg(lambda: self.fl.backup_restore("/ext/backup.tar"), lambda _: self.set_status("Restored"))

    def factory_reset(self):
        if not self.need_flipper():
            return
        if messagebox.askyesno("Factory reset", "ERASE all data in the Flipper internal storage and reset it?\n"
                               "This cannot be undone.") and messagebox.askyesno("Factory reset", "Are you really sure?"):
            self.fl.factory_reset()
            self.set_status("Factory reset sent")

    # ---------- files page ----------
    def _build_files(self, page):
        quick = tk.Frame(page, bg=T["bg"])
        quick.pack(fill="x", pady=(0, 10))
        for text, p in QUICK:
            Btn(quick, text, lambda p=p: self.list_path(p)).pack(side="left", padx=(0, 6))

        bar = tk.Frame(page, bg=T["bg"])
        bar.pack(fill="x", pady=(0, 10))
        e = entry(bar, self.path_var, 60)
        e.pack(side="left", ipady=6)
        e.bind("<Return>", lambda ev: self.list_path(self.path_var.get()))
        Btn(bar, "Go", lambda: self.list_path(self.path_var.get()), kind="accent").pack(side="left", padx=6)
        Btn(bar, "Up", self.go_up).pack(side="left")

        mid = tk.Frame(page, bg=T["bg"])
        mid.pack(fill="both", expand=True)
        table = tk.Frame(mid, bg=T["panel"], highlightbackground=T["line"], highlightthickness=1)
        table.pack(side="left", fill="both", expand=True)
        self.tree = ttk.Treeview(table, columns=("size",), selectmode="browse")
        self.tree.heading("#0", text="Name", anchor="w")
        self.tree.heading("size", text="Size", anchor="e")
        self.tree.column("#0", width=420)
        self.tree.column("size", width=100, anchor="e")
        sb = ttk.Scrollbar(table, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.tree.pack(side="left", fill="both", expand=True)
        self.tree.bind("<Double-Button-1>", lambda ev: self.open_selected())
        side = tk.Frame(mid, bg=T["bg"])
        side.pack(side="right", fill="y", padx=(12, 0))
        for text, cmd, kind in (("View / Run", self.open_selected, "accent"), ("Download", self.download, "ghost"),
                                ("Upload file", self.upload, "ghost"), ("New folder", self.new_folder, "ghost"),
                                ("Rename", self.rename, "ghost"), ("MD5", self.md5, "ghost"),
                                ("Delete", self.delete, "danger")):
            Btn(side, text, cmd, kind=kind).pack(fill="x", pady=3)

    def go_up(self):
        self.list_path(self.path.rsplit("/", 1)[0] or "/ext")

    def list_path(self, path):
        if not self.need_flipper():
            return
        path = path.rstrip("/") or "/ext"
        fl = self.fl

        def done(entries):
            self.path = path
            self.path_var.set(path)
            self.entries = {name: (is_dir, size) for name, is_dir, size in entries}
            self.tree.delete(*self.tree.get_children())
            for name, is_dir, size in sorted(entries, key=lambda e: (not e[1], e[0].lower())):
                self.tree.insert("", "end", iid=name, text=("▸  " if is_dir else "     ") + name,
                                 values=("" if is_dir else "%d B" % size))
            self.set_status(path)

        self.bg(lambda: fl.list_dir(path), done)

    def selected(self):
        sel = self.tree.selection()
        if not sel:
            self.set_status("Select a file or folder first")
            return None
        return sel[0]

    def open_selected(self):
        name = self.selected()
        if name is None:
            return
        is_dir, _ = self.entries[name]
        if is_dir:
            self.list_path(self.path + "/" + name)
            return
        full = self.path + "/" + name
        fl = self.fl
        self.set_status("Reading %s..." % name)
        self.bg(lambda: fl.read_file(full), lambda data: self.show_file(name, data.decode("utf-8", "replace")))

    def show_file(self, name, text):
        self.set_status(self.path)
        win = dark_window(self, name, "680x560")
        tk.Label(win, text=name, bg=T["bg"], fg=T["text"], font=H2).pack(anchor="w", padx=18, pady=(16, 6))
        summary = flipper_formats.summarize(name, text)
        card = Card(win)
        card.pack(fill="x", padx=18, pady=6)
        tk.Label(card.body, text=summary, bg=T["panel"], fg=T["text"], font=UI, justify="left", anchor="w",
                 wraplength=600).pack(fill="x")
        t = textbox(win, wrap="none")
        t.pack(fill="both", expand=True, padx=18, pady=8)
        t.insert("end", text[:20000])
        t.config(state="disabled")
        bar = tk.Frame(win, bg=T["bg"])
        bar.pack(pady=(0, 14))
        ext = name.rsplit(".", 1)[-1].lower()
        if ext in APP_BY_EXT:
            Btn(bar, "Run on the Flipper", lambda: (win.destroy(), self.run_key(name, text)), kind="accent").pack(
                side="left", padx=4)
        Btn(bar, "Close", win.destroy).pack(side="left", padx=4)

    def run_key(self, name, text):
        ext = name.rsplit(".", 1)[-1].lower()
        app = APP_BY_EXT[ext]
        full = self.path + "/" + name
        if ext == "ir":
            names = []
            for l in text.splitlines():
                l = l.strip()
                if l.startswith("name:"):
                    n = l.split(":", 1)[1].strip()
                    if n not in names:
                        names.append(n)
            if not names:
                self.set_status("No signals in this file")
                return
            PickDialog(self, "Send signal", names, lambda i: self.launch_key(app, full, names[i], True))
        else:
            self.launch_key(app, full, "", ext != "ibtn")    # iButton emulates as soon as the file is loaded

    def launch_key(self, app, full, button_name, press):
        fl = self.fl
        self.set_status("Starting %s..." % app)

        def work():
            st = fl.start_app(app, "RPC")
            if st != 0:
                raise rpc.RpcError(st)
            try:
                fl.app_load_file(full)
                if press:
                    fl.app_press_release(button_name)
            except rpc.RpcError:
                raise Exception("%s failed: %s" % (app, fl.app_error()))
            return "%s: %s (press Stop app to end)" % (app, full.rsplit("/", 1)[-1])

        self.bg(work, self.set_status)

    def download(self):
        name = self.selected()
        if name is None or self.entries[name][0]:
            return
        dest = filedialog.asksaveasfilename(initialfile=name)
        if not dest:
            return
        full = self.path + "/" + name
        fl = self.fl

        def work():
            data = fl.read_file(full)
            with open(dest, "wb") as f:
                f.write(data)
            return "Saved %s (%d B)" % (dest, len(data))

        self.bg(work, self.set_status)

    def upload(self):
        if not self.need_flipper():
            return
        src = filedialog.askopenfilename()
        if not src:
            return
        name = src.replace("\\", "/").rsplit("/", 1)[-1]
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        dest = DIR_BY_EXT.get(ext, self.path) + "/" + name
        if not dest.isascii():
            self.set_status("The Flipper accepts ASCII-only file paths")
            return
        fl = self.fl
        self.set_status("Uploading to %s..." % dest)

        def work():
            with open(src, "rb") as f:
                data = f.read()
            fl.write_file(dest, data)
            return "Uploaded %s (%d B)" % (dest, len(data))

        self.bg(work, lambda msg: (self.set_status(msg), self.list_path(self.path)))

    def ask_text(self, title, initial, ok_text, on_ok):
        win = dark_window(self, title, "420x170")
        tk.Label(win, text=title, bg=T["bg"], fg=T["text"], font=H2).pack(anchor="w", padx=18, pady=(16, 8))
        var = tk.StringVar(value=initial)
        e = entry(win, var, 44)
        e.pack(padx=18, ipady=6)

        def ok():
            value = var.get().strip()
            win.destroy()
            if value:
                on_ok(value)

        e.bind("<Return>", lambda ev: ok())
        Btn(win, ok_text, ok, kind="accent").pack(pady=14)

    def new_folder(self):
        if self.need_flipper():
            self.ask_text("New folder", "", "Create", lambda n: self.bg(
                lambda: self.fl.mkdir(self.path + "/" + n), lambda _: self.list_path(self.path)))

    def rename(self):
        name = self.selected()
        if name is None:
            return
        self.ask_text("Rename", name, "Rename", lambda n: self.bg(
            lambda: self.fl.rename(self.path + "/" + name, self.path + "/" + n), lambda _: self.list_path(self.path)))

    def delete(self):
        name = self.selected()
        if name is None or not messagebox.askyesno("Delete", "Delete %s?" % name):
            return
        is_dir = self.entries[name][0]
        self.bg(lambda: self.fl.delete(self.path + "/" + name, is_dir), lambda _: self.list_path(self.path))

    def md5(self):
        name = self.selected()
        if name is None or self.entries[name][0]:
            return
        self.bg(lambda: self.fl.md5(self.path + "/" + name), lambda s: self.set_status("MD5 %s: %s" % (name, s)))

    # ---------- remote control: a drawn Flipper with a live screen ----------
    @staticmethod
    def round_rect(c, x1, y1, x2, y2, r, **kw):
        pts = [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2, x2 - r, y2,
               x1 + r, y2, x1, y2, x1, y2 - r, x1, y1 + r, x1, y1]
        return c.create_polygon(pts, smooth=True, **kw)

    def _build_remote(self, page):
        wrap = tk.Frame(page, bg=T["bg"])
        wrap.pack(fill="both", expand=True)
        c = tk.Canvas(wrap, width=480, height=560, bg=T["bg"], highlightthickness=0)
        c.pack(side="left", padx=(0, 24))
        self.remote = c
        self.round_rect(c, 10, 10, 470, 550, 70, fill="#EDEDED", outline="#C9C9C9", width=2)
        self.round_rect(c, 34, 34, 446, 262, 26, fill="#1B1B1B", outline="")
        c.create_rectangle(48, 48, 432, 240, fill=T["screen"], outline="")
        c.create_text(240, 22, text="FLIPPER", fill="#8A8A8A", font=("Segoe UI", 8, "bold"))
        cx, cy, r = 190, 405, 95
        for name, start in (("up", 45), ("left", 135), ("down", 225), ("right", 315)):
            c.create_arc(cx - r, cy - r, cx + r, cy + r, start=start, extent=90, style="pieslice", fill="#2B2B2B",
                         outline="#EDEDED", width=3, activefill="#454545", tags=("key_" + name,))
        glyphs = {"up": [cx - 10, cy - 58, cx + 10, cy - 58, cx, cy - 76], "down": [cx - 10, cy + 58, cx + 10, cy + 58, cx, cy + 76],
                  "left": [cx - 58, cy - 10, cx - 58, cy + 10, cx - 76, cy], "right": [cx + 58, cy - 10, cx + 58, cy + 10, cx + 76, cy]}
        for name, pts in glyphs.items():
            c.create_polygon(pts, fill="#CFCFCF", outline="", tags=("key_" + name,))
        c.create_oval(cx - 36, cy - 36, cx + 36, cy + 36, fill="#2B2B2B", outline="#EDEDED", width=3,
                      activefill="#454545", tags=("key_ok",))
        c.create_oval(cx - 6, cy - 6, cx + 6, cy + 6, fill="#CFCFCF", outline="", tags=("key_ok",))
        c.create_oval(340, 420, 400, 480, fill="#2B2B2B", outline="#EDEDED", width=3, activefill="#454545",
                      tags=("key_back",))
        c.create_text(370, 450, text="BACK", fill="#CFCFCF", font=("Segoe UI", 8, "bold"), tags=("key_back",))
        for name, key in KEYS.items():
            c.tag_bind("key_" + name, "<Button-1>", lambda e, k=key: self.press(k))
            c.tag_bind("key_" + name, "<Enter>", lambda e: c.config(cursor="hand2"))
            c.tag_bind("key_" + name, "<Leave>", lambda e: c.config(cursor=""))

        card = Card(wrap, "SCREEN")
        card.pack(side="left", fill="y", anchor="n")
        self.screen_btn = Btn(card.body, "Start screen", self.toggle_screen, kind="accent")
        self.screen_btn.pack(fill="x", pady=(4, 12))
        lbl(card.body, "Keyboard", font=UI_B).pack(anchor="w")
        lbl(card.body, "Arrow keys: move\nEnter: OK\nEsc: Back", color=T["muted"], justify="left").pack(
            anchor="w", pady=(2, 12))
        lbl(card.body, "Click the buttons on the\ndrawn Flipper or use the keys.", color=T["muted"],
            justify="left").pack(anchor="w")

    def toggle_screen(self):
        if not self.need_flipper():
            return
        fl = self.fl
        if self.screen_on:
            self.screen_on = False
            self.screen_btn.config(text="Start screen")
            self.bg(fl.stop_screen)
            return
        self.screen_on = True
        self.screen_btn.config(text="Stop screen")
        self.bg(lambda: fl.start_screen(lambda data, orient: self.after(0, self.draw_frame, data, orient)))

    def draw_frame(self, data, orient):
        if not self.screen_on or len(data) < 1024:
            return
        rows = []
        for y in range(64):
            page, bit = y >> 3, y & 7
            rows.append([T["ink"] if (data[page * 128 + x] >> bit) & 1 else T["screen"] for x in range(128)])
        if orient == 1:                                   # HORIZONTAL_FLIP
            rows = [r[::-1] for r in rows[::-1]]
        img = tk.PhotoImage(width=128, height=64)
        img.put(" ".join("{" + " ".join(r) + "}" for r in rows))
        self.screen_img = img.zoom(SCALE, SCALE)
        self.remote.delete("screen")
        self.remote.create_image(48, 48, anchor="nw", image=self.screen_img, tags="screen")

    def press(self, key):
        if self.fl is not None:
            self.fl.key(key)

    def on_key(self, event):
        if self.page_name != "remote" or self.fl is None:
            return
        keys = {"Up": 0, "Down": 1, "Right": 2, "Left": 3, "Return": 4, "Escape": 5}
        if event.keysym in keys:
            self.press(keys[event.keysym])

    # ---------- GPIO ----------
    def _build_gpio(self, page):
        warn = tk.Frame(page, bg=T["bg"])
        warn.pack(fill="x", pady=(0, 10))
        tk.Label(warn, text="Pins are 3.3 V. Wrong wiring can damage the Flipper.", bg=T["bg"], fg=T["muted"],
                 font=UI).pack(side="left")
        otg = tk.Frame(warn, bg=T["bg"])
        otg.pack(side="right")
        tk.Label(otg, text="5 V on pin 1", bg=T["bg"], fg=T["text"], font=UI_B).pack(side="left", padx=8)
        Btn(otg, "Check", self.otg_check).pack(side="left", padx=3)
        Btn(otg, "On", lambda: self.otg_set(True)).pack(side="left", padx=3)
        Btn(otg, "Off", lambda: self.otg_set(False)).pack(side="left", padx=3)
        grid = tk.Frame(page, bg=T["bg"])
        grid.pack(fill="both", expand=True)
        grid.columnconfigure(0, weight=1, uniform="p")
        grid.columnconfigure(1, weight=1, uniform="p")
        for i, (name, hdr, pin) in enumerate(PINS):
            card = Card(grid)
            card.grid(row=i // 2, column=i % 2, sticky="nsew", padx=6, pady=6)
            head = tk.Frame(card.body, bg=T["panel"])
            head.pack(fill="x")
            title = lbl(head, name, font=H2)
            title.pack(side="left")
            lbl(head, "  " + hdr, font=UI_S, color=T["muted"]).pack(side="left")
            Btn(head, "READ", lambda p=pin, t=title, n=name: self.gpio_read(p, t, n)).pack(side="right")
            row1 = tk.Frame(card.body, bg=T["panel"])
            row1.pack(fill="x", pady=(8, 3))
            for text, fn in (("IN", lambda p=pin: self.gpio(lambda f: f.gpio_set_mode(p, 1), "input")),
                             ("OUT", lambda p=pin: self.gpio(lambda f: f.gpio_set_mode(p, 0), "output")),
                             ("LOW", lambda p=pin: self.gpio(lambda f: f.gpio_write(p, 0), "= 0")),
                             ("HIGH", lambda p=pin: self.gpio(lambda f: f.gpio_write(p, 1), "= 1"))):
                Btn(row1, text, fn).pack(side="left", padx=2)
            row2 = tk.Frame(card.body, bg=T["panel"])
            row2.pack(fill="x")
            for text, fn in (("NO PULL", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 0), "no pull")),
                             ("UP", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 1), "pull up")),
                             ("DOWN", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 2), "pull down"))):
                Btn(row2, text, fn).pack(side="left", padx=2)

    def gpio(self, action, what):
        if self.need_flipper():
            fl = self.fl
            self.bg(lambda: action(fl), lambda _: self.set_status("GPIO: " + what))

    def gpio_read(self, pin, title, name):
        if self.need_flipper():
            fl = self.fl
            self.bg(lambda: fl.gpio_read(pin), lambda v: title.config(text="%s = %d" % (name, v)))

    def otg_check(self):
        if self.need_flipper():
            self.bg(self.fl.otg_get, lambda on: self.set_status("5 V on pin 1: " + ("ON" if on else "OFF")))

    def otg_set(self, on):
        if self.need_flipper() and (not on or messagebox.askyesno("5 V", "Turn 5 V on pin 1 on?")):
            self.bg(lambda: self.fl.otg_set(on), lambda _: self.set_status("5 V " + ("ON" if on else "OFF")))

    # ---------- apps ----------
    def _build_apps(self, page):
        gh = Card(page, "INSTALL FROM GITHUB")
        gh.pack(fill="x", pady=(0, 14))
        lbl(gh.body, "Paste a link to a .fap file, a release asset or a repository with a release.",
            color=T["muted"]).pack(anchor="w", pady=(0, 8))
        entry(gh.body, self.link_var, 90).pack(anchor="w", ipady=6)
        row = tk.Frame(gh.body, bg=T["panel"])
        row.pack(anchor="w", pady=10)
        Btn(row, "Install + run", lambda: self.install_github(True), kind="accent").pack(side="left", padx=(0, 6))
        Btn(row, "Install only", lambda: self.install_github(False)).pack(side="left")
        lbl(gh.body, "The .fap runs on your Flipper: install only add-ons you trust, built for your firmware version.\n"
            "Keep the Flipper on its main screen.", font=UI_S, color=T["muted"], justify="left").pack(anchor="w")

        pc = Card(page, "INSTALL FROM THIS COMPUTER")
        pc.pack(fill="x")
        Btn(pc.body, "Choose .fap file...", self.install_file).pack(anchor="w", pady=4)

    def install_github(self, launch):
        if not self.need_flipper():
            return
        link = self.link_var.get().strip()
        fl = self.fl
        self.set_status("Downloading...")

        def work():
            name, data = github_installer.fetch(link)
            return self.send_fap(fl, name, data, launch)

        self.bg(work, self.set_status)

    def install_file(self):
        if not self.need_flipper():
            return
        src = filedialog.askopenfilename(filetypes=[("Flipper apps", "*.fap"), ("All files", "*.*")])
        if not src:
            return
        fl = self.fl
        name = src.replace("\\", "/").rsplit("/", 1)[-1]

        def work():
            with open(src, "rb") as f:
                return self.send_fap(fl, name, f.read(), True)

        self.bg(work, self.set_status)

    def send_fap(self, fl, name, data, launch):
        dest = "/ext/apps/Misc/" + name
        if not dest.isascii():
            raise Exception("The Flipper accepts ASCII-only file names")
        self.after(0, lambda: self.set_status("Sending %s (%d B)..." % (name, len(data))))
        fl.write_file(dest, data)
        if not launch:
            return "Installed: " + dest
        st = fl.start_app(dest, "")
        if st != 0:
            return "Installed, but did not start: " + rpc.STATUS_TEXT.get(st, "error %d" % st)
        return "Running on the Flipper: " + name


def main():
    App().mainloop()


if __name__ == "__main__":
    main()
