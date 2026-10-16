"""Flipper Desk: manage a Flipper from a PC, like the original desktop app.

Connect either the Flipper itself with a USB cable, or an Android phone with the Flipper Link app
(phone connected to the PC by USB, the phone talks to the Flipper over Bluetooth).
"""
import datetime
import subprocess
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import flipper_formats
import flipper_rpc as rpc
import github_installer

ORANGE = "#FF8200"
BLACK = "#000000"
MONO = ("Courier", 10)
SCALE = 5
ADB_PORT = 8765

QUICK = ["/ext", "/ext/nfc", "/ext/subghz", "/ext/lfrfid", "/ext/ibutton", "/ext/infrared",
         "/ext/badusb", "/ext/apps", "/int"]
PINS = [("PC0 (header pin 16)", 0), ("PC1 (header pin 15)", 1), ("PC3 (header pin 7)", 2),
        ("PB2 (header pin 6)", 3), ("PB3 (header pin 5)", 4), ("PA4 (header pin 4)", 5),
        ("PA6 (header pin 3)", 6), ("PA7 (header pin 2)", 7)]
APP_BY_EXT = {"nfc": "NFC", "sub": "Sub-GHz", "rfid": "125 kHz RFID", "ibtn": "iButton", "ir": "Infrared"}
DIR_BY_EXT = {"nfc": "/ext/nfc", "sub": "/ext/subghz", "rfid": "/ext/lfrfid", "ibtn": "/ext/ibutton",
              "ir": "/ext/infrared", "fap": "/ext/apps/Misc"}


def button(parent, text, command, **kw):
    return tk.Button(parent, text=text, command=command, bg=BLACK, fg=ORANGE, activebackground="#333333",
                     activeforeground=ORANGE, relief="flat", font=MONO, **kw)


def label(parent, text, **kw):
    return tk.Label(parent, text=text, bg=ORANGE, fg=BLACK, font=MONO, **kw)


class PickDialog(tk.Toplevel):
    """Small list dialog; calls on_pick(index) after a double click or OK."""

    def __init__(self, parent, title, items, on_pick):
        super().__init__(parent)
        self.title(title)
        self.configure(bg=ORANGE)
        self.geometry("420x360")
        self.on_pick = on_pick
        self.box = tk.Listbox(self, font=MONO)
        self.box.pack(fill="both", expand=True, padx=8, pady=8)
        for it in items:
            self.box.insert("end", it)
        self.box.bind("<Double-Button-1>", lambda e: self.ok())
        button(self, "OK", self.ok).pack(pady=6)

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
        self.configure(bg=ORANGE)
        self.geometry("960x700")
        self.fl = None
        self.path = "/ext"
        self.entries = {}
        self.port_map = {}
        self.screen_on = False
        self.screen_img = None
        self.status = tk.StringVar(value="Not connected")
        self._build()
        self.refresh_ports()

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
            messagebox.showinfo("Flipper Desk", "Connect to a Flipper first.")
            return False
        return True

    # ---------- layout ----------
    def _build(self):
        top = tk.Frame(self, bg=ORANGE)
        top.pack(fill="x", padx=8, pady=6)
        self.mode = tk.StringVar(value="usb")
        for text, val in (("Flipper by USB cable", "usb"), ("Phone by USB (adb)", "adb")):
            tk.Radiobutton(top, text=text, variable=self.mode, value=val, bg=ORANGE, fg=BLACK, font=MONO,
                           activebackground=ORANGE, selectcolor=ORANGE, command=self.refresh_ports).pack(side="left")
        self.port_var = tk.StringVar()
        self.port_box = ttk.Combobox(top, textvariable=self.port_var, width=30, state="readonly")
        self.port_box.pack(side="left", padx=6)
        button(top, "Refresh", self.refresh_ports).pack(side="left", padx=2)
        button(top, "Set up phone (adb)", self.setup_adb).pack(side="left", padx=2)
        self.connect_btn = button(top, "Connect", self.toggle_connect)
        self.connect_btn.pack(side="left", padx=6)
        button(top, "Stop app", self.stop_app).pack(side="right")

        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=8, pady=4)
        self.tab_device = tk.Frame(self.nb, bg=ORANGE)
        self.tab_files = tk.Frame(self.nb, bg=ORANGE)
        self.tab_remote = tk.Frame(self.nb, bg=ORANGE)
        self.tab_gpio = tk.Frame(self.nb, bg=ORANGE)
        self.tab_apps = tk.Frame(self.nb, bg=ORANGE)
        for frame, name in ((self.tab_device, "Device"), (self.tab_files, "Archive / Files"),
                            (self.tab_remote, "Remote control"), (self.tab_gpio, "GPIO"), (self.tab_apps, "Apps")):
            self.nb.add(frame, text=name)
        self._build_device()
        self._build_files()
        self._build_remote()
        self._build_gpio()
        self._build_apps()
        tk.Label(self, textvariable=self.status, bg=BLACK, fg=ORANGE, font=MONO, anchor="w").pack(fill="x")
        self.bind("<Key>", self.on_key)

    # ---------- connection ----------
    def refresh_ports(self):
        if self.mode.get() == "usb":
            ports = rpc.list_serial_ports()
            self.port_map = {text: dev for dev, text, _ in ports}
            self.port_box["values"] = list(self.port_map.keys())
            if self.port_map:
                self.port_box.current(0)
            else:
                self.port_var.set("")
            self.port_box.config(state="readonly")
            if not ports:
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
                                "app on the phone, then press Connect." % (ADB_PORT, ADB_PORT, out))
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
            self.connect_btn.config(text="Disconnect")
            self.set_status("Connected")
            self.refresh_device()
            self.list_path("/ext")

        self.bg(work, done)

    def disconnect(self):
        if self.fl is not None:
            self.fl.close()
        self.fl = None
        self.connect_btn.config(text="Connect")
        self.set_status("Disconnected")

    def on_lost(self):
        self.fl = None
        self.connect_btn.config(text="Connect")
        self.set_status("Connection lost")

    def stop_app(self):
        if self.need_flipper():
            self.fl.app_exit()
            self.set_status("Stop sent")

    # ---------- device tab ----------
    def _build_device(self):
        bar = tk.Frame(self.tab_device, bg=ORANGE)
        bar.pack(fill="x", padx=6, pady=6)
        for text, cmd in (("Refresh", self.refresh_device), ("Ping", self.ping), ("Play alert", self.alert),
                          ("Sync time", self.sync_time), ("Unlock screen", self.unlock), ("Reboot", self.reboot)):
            button(bar, text, cmd).pack(side="left", padx=2)
        bar2 = tk.Frame(self.tab_device, bg=ORANGE)
        bar2.pack(fill="x", padx=6)
        for text, cmd in (("Back up to SD", self.backup), ("Restore backup", self.restore),
                          ("Factory reset", self.factory_reset)):
            button(bar2, text, cmd).pack(side="left", padx=2)
        self.info = tk.Text(self.tab_device, bg="#FFD9A8", fg=BLACK, font=MONO, state="disabled")
        self.info.pack(fill="both", expand=True, padx=6, pady=6)

    def refresh_device(self):
        if not self.need_flipper():
            return
        fl = self.fl

        def work():
            out = []

            def section(title, fn):
                out.append(title)
                try:
                    r = fn()
                    out.extend(r if isinstance(r, list) else [r])
                except Exception as e:
                    out.append("  failed: %s" % e)
                out.append("")

            section("DEVICE", lambda: ["%s: %s" % kv for kv in fl.device_info()])
            section("POWER", lambda: ["%s: %s" % kv for kv in fl.power_info()])

            def mb(x):
                return "%.1f MB" % (x / 1048576.0)

            def storage(path):
                total, free = fl.storage_info(path)
                return "%s: %s free of %s" % (path, mb(free), mb(total))

            section("STORAGE", lambda: [storage("/ext"), storage("/int")])
            section("OTHER", lambda: ["Protobuf version: " + fl.protobuf_version(), "Flipper time: " + fl.datetime(),
                                      "App running in RPC mode: " + ("yes" if fl.app_locked() else "no")])
            return "\n".join(out)

        def done(text):
            self.info.config(state="normal")
            self.info.delete("1.0", "end")
            self.info.insert("end", text)
            self.info.config(state="disabled")
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
            self.bg(lambda: self.fl.backup_create("/ext/backup.tar"), lambda _: self.set_status("Backup saved: /ext/backup.tar"))

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

    # ---------- files tab ----------
    def _build_files(self):
        bar = tk.Frame(self.tab_files, bg=ORANGE)
        bar.pack(fill="x", padx=6, pady=6)
        self.quick = ttk.Combobox(bar, values=QUICK, width=14, state="readonly")
        self.quick.pack(side="left")
        self.quick.bind("<<ComboboxSelected>>", lambda e: self.list_path(self.quick.get()))
        self.path_var = tk.StringVar(value="/ext")
        entry = tk.Entry(bar, textvariable=self.path_var, font=MONO, width=40)
        entry.pack(side="left", padx=6)
        entry.bind("<Return>", lambda e: self.list_path(self.path_var.get()))
        button(bar, "Go", lambda: self.list_path(self.path_var.get())).pack(side="left", padx=2)
        button(bar, "Up", self.go_up).pack(side="left", padx=2)

        mid = tk.Frame(self.tab_files, bg=ORANGE)
        mid.pack(fill="both", expand=True, padx=6)
        self.tree = ttk.Treeview(mid, columns=("size",), selectmode="browse")
        self.tree.heading("#0", text="Name")
        self.tree.heading("size", text="Size")
        self.tree.column("size", width=90, anchor="e")
        self.tree.pack(side="left", fill="both", expand=True)
        self.tree.bind("<Double-Button-1>", lambda e: self.open_selected())
        side = tk.Frame(mid, bg=ORANGE)
        side.pack(side="right", fill="y", padx=6)
        for text, cmd in (("View / Run", self.open_selected), ("Download", self.download), ("Upload file", self.upload),
                          ("New folder", self.new_folder), ("Rename", self.rename), ("Delete", self.delete),
                          ("MD5", self.md5)):
            button(side, text, cmd, width=14).pack(pady=2)

    def go_up(self):
        p = self.path.rsplit("/", 1)[0] or "/ext"
        self.list_path(p)

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
                self.tree.insert("", "end", iid=name, text=("[DIR] " if is_dir else "") + name,
                                 values=("" if is_dir else "%d B" % size))
            self.set_status(path)

        self.bg(lambda: fl.list_dir(path), done)

    def selected(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo("Flipper Desk", "Select a file or folder first.")
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
        win = tk.Toplevel(self)
        win.title(name)
        win.configure(bg=ORANGE)
        win.geometry("620x520")
        t = tk.Text(win, bg="#FFD9A8", fg=BLACK, font=MONO, wrap="none")
        t.pack(fill="both", expand=True, padx=8, pady=8)
        t.insert("end", flipper_formats.summarize(name, text) + "\n\n----- file -----\n" + text[:20000])
        t.config(state="disabled")
        ext = name.rsplit(".", 1)[-1].lower()
        if ext in APP_BY_EXT:
            button(win, "Run on the Flipper", lambda: (win.destroy(), self.run_key(name, text))).pack(pady=6)

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
            messagebox.showerror("Upload", "The Flipper accepts ASCII-only file paths.")
            return
        fl = self.fl
        self.set_status("Uploading to %s..." % dest)

        def work():
            with open(src, "rb") as f:
                data = f.read()
            fl.write_file(dest, data)
            return "Uploaded %s (%d B)" % (dest, len(data))

        self.bg(work, lambda msg: (self.set_status(msg), self.list_path(self.path)))

    def new_folder(self):
        if not self.need_flipper():
            return
        top = tk.Toplevel(self)
        top.title("New folder")
        top.configure(bg=ORANGE)
        var = tk.StringVar()
        tk.Entry(top, textvariable=var, font=MONO, width=30).pack(padx=10, pady=10)

        def ok():
            name = var.get().strip()
            top.destroy()
            if name:
                self.bg(lambda: self.fl.mkdir(self.path + "/" + name), lambda _: self.list_path(self.path))

        button(top, "Create", ok).pack(pady=6)

    def rename(self):
        name = self.selected()
        if name is None:
            return
        top = tk.Toplevel(self)
        top.title("Rename")
        top.configure(bg=ORANGE)
        var = tk.StringVar(value=name)
        tk.Entry(top, textvariable=var, font=MONO, width=30).pack(padx=10, pady=10)

        def ok():
            new = var.get().strip()
            top.destroy()
            if new and new != name:
                self.bg(lambda: self.fl.rename(self.path + "/" + name, self.path + "/" + new),
                        lambda _: self.list_path(self.path))

        button(top, "Rename", ok).pack(pady=6)

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

    # ---------- remote control ----------
    def _build_remote(self):
        bar = tk.Frame(self.tab_remote, bg=ORANGE)
        bar.pack(fill="x", padx=6, pady=6)
        self.screen_btn = button(bar, "Start screen", self.toggle_screen)
        self.screen_btn.pack(side="left")
        label(bar, "  Keys: arrows, Enter = OK, Esc = Back").pack(side="left")
        self.canvas = tk.Canvas(self.tab_remote, width=128 * SCALE, height=64 * SCALE, bg=ORANGE,
                                highlightthickness=2, highlightbackground=BLACK)
        self.canvas.pack(pady=8)
        pad = tk.Frame(self.tab_remote, bg=ORANGE)
        pad.pack()
        for text, key, r, c in (("UP", 0, 0, 1), ("LEFT", 3, 1, 0), ("OK", 4, 1, 1), ("RIGHT", 2, 1, 2),
                                ("DOWN", 1, 2, 1), ("BACK", 5, 2, 2)):
            button(pad, text, lambda k=key: self.press(k), width=7).grid(row=r, column=c, padx=3, pady=3)

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
            rows.append([BLACK if (data[page * 128 + x] >> bit) & 1 else ORANGE for x in range(128)])
        if orient == 1:                                   # HORIZONTAL_FLIP
            rows = [r[::-1] for r in rows[::-1]]
        img = tk.PhotoImage(width=128, height=64)
        img.put(" ".join("{" + " ".join(r) + "}" for r in rows))
        self.screen_img = img.zoom(SCALE, SCALE)
        self.canvas.create_image(0, 0, anchor="nw", image=self.screen_img)

    def press(self, key):
        if self.fl is not None:
            self.fl.key(key)

    def on_key(self, event):
        if self.nb.select() != str(self.tab_remote) or self.fl is None:
            return
        keys = {"Up": 0, "Down": 1, "Right": 2, "Left": 3, "Return": 4, "Escape": 5}
        if event.keysym in keys:
            self.press(keys[event.keysym])

    # ---------- GPIO ----------
    def _build_gpio(self):
        label(self.tab_gpio, "Pins are 3.3 V. Wrong wiring can damage the Flipper.").pack(anchor="w", padx=8, pady=6)
        otg = tk.Frame(self.tab_gpio, bg=ORANGE)
        otg.pack(anchor="w", padx=8)
        label(otg, "5 V on pin 1:").pack(side="left")
        button(otg, "Check", self.otg_check).pack(side="left", padx=2)
        button(otg, "On", lambda: self.otg_set(True)).pack(side="left", padx=2)
        button(otg, "Off", lambda: self.otg_set(False)).pack(side="left", padx=2)
        for name, pin in PINS:
            row = tk.Frame(self.tab_gpio, bg=ORANGE)
            row.pack(anchor="w", padx=8, pady=3)
            lab = label(row, name, width=22, anchor="w")
            lab.pack(side="left")
            for text, fn in (("IN", lambda p=pin: self.gpio(lambda f: f.gpio_set_mode(p, 1), "input")),
                             ("OUT", lambda p=pin: self.gpio(lambda f: f.gpio_set_mode(p, 0), "output")),
                             ("LOW", lambda p=pin: self.gpio(lambda f: f.gpio_write(p, 0), "= 0")),
                             ("HIGH", lambda p=pin: self.gpio(lambda f: f.gpio_write(p, 1), "= 1")),
                             ("NOPULL", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 0), "no pull")),
                             ("UP", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 1), "pull up")),
                             ("DOWN", lambda p=pin: self.gpio(lambda f: f.gpio_set_pull(p, 2), "pull down"))):
                button(row, text, fn, width=7).pack(side="left", padx=1)
            button(row, "READ", lambda p=pin, l=lab, n=name: self.gpio_read(p, l, n), width=7).pack(side="left", padx=1)

    def gpio(self, action, what):
        if self.need_flipper():
            fl = self.fl
            self.bg(lambda: action(fl), lambda _: self.set_status("GPIO: " + what))

    def gpio_read(self, pin, lab, name):
        if self.need_flipper():
            fl = self.fl
            self.bg(lambda: fl.gpio_read(pin), lambda v: lab.config(text="%s = %d" % (name, v)))

    def otg_check(self):
        if self.need_flipper():
            self.bg(self.fl.otg_get, lambda on: self.set_status("5 V on pin 1: " + ("ON" if on else "OFF")))

    def otg_set(self, on):
        if self.need_flipper() and (not on or messagebox.askyesno("5 V", "Turn 5 V on pin 1 on?")):
            self.bg(lambda: self.fl.otg_set(on), lambda _: self.set_status("5 V " + ("ON" if on else "OFF")))

    # ---------- apps ----------
    def _build_apps(self):
        label(self.tab_apps, "Install a .fap from GitHub (file link, release asset or a repo with a release):").pack(
            anchor="w", padx=8, pady=8)
        self.link_var = tk.StringVar()
        tk.Entry(self.tab_apps, textvariable=self.link_var, font=MONO, width=80).pack(anchor="w", padx=8)
        row = tk.Frame(self.tab_apps, bg=ORANGE)
        row.pack(anchor="w", padx=8, pady=8)
        button(row, "Install + run", lambda: self.install_github(True)).pack(side="left", padx=2)
        button(row, "Install only", lambda: self.install_github(False)).pack(side="left", padx=2)
        label(self.tab_apps, "The .fap runs on your Flipper: install only add-ons you trust, built for your firmware "
              "version.\nKeep the Flipper on its main screen.", justify="left").pack(anchor="w", padx=8, pady=8)
        label(self.tab_apps, "Or install a .fap from this computer:").pack(anchor="w", padx=8, pady=8)
        button(self.tab_apps, "Choose .fap file...", self.install_file).pack(anchor="w", padx=8)

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
