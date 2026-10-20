"""Flipper RPC client for a PC.

Talks the Flipper RPC protocol (protobuf, length-delimited) over
  * the Flipper USB serial port (needs pyserial), like qFlipper, or
  * a TCP port that reaches the Flipper Link app on an Android phone through "adb forward".
Field numbers are taken from flipperzero-protobuf: flipper.proto, system.proto, storage.proto,
application.proto, gpio.proto, gui.proto, desktop.proto.
"""
import queue
import socket
import threading
import time

STATUS_TEXT = {
    1: "error", 4: "the Flipper is busy", 5: "storage not ready", 6: "already exists",
    7: "not found", 9: "access denied", 10: "invalid name", 15: "invalid parameters",
    16: "cannot start the app (wrong firmware version or damaged file)",
    17: "another app is running on the Flipper, close it first", 18: "folder is not empty",
    21: "no app is running", 22: "app command error",
}


class RpcError(Exception):
    def __init__(self, status, text=""):
        self.status = status
        super().__init__(text or STATUS_TEXT.get(status, "error %d" % status))


# ---------- protobuf helpers ----------
def varint(n):
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def read_varint(buf, pos):
    result = 0
    shift = 0
    while pos < len(buf):
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
    return None


def walk(buf):
    """Yield (field, value) where value is an int (varint) or bytes (length-delimited)."""
    pos = 0
    while pos < len(buf):
        r = read_varint(buf, pos)
        if r is None:
            return
        tag, pos = r
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            r = read_varint(buf, pos)
            if r is None:
                return
            v, pos = r
            yield field, v
        elif wire == 2:
            r = read_varint(buf, pos)
            if r is None:
                return
            ln, pos = r
            yield field, bytes(buf[pos:pos + ln])
            pos += ln
        elif wire == 1:
            pos += 8
        elif wire == 5:
            pos += 4
        else:
            return


def str_field(s, number=1):
    b = s.encode("utf-8")
    return bytes([(number << 3) | 2]) + varint(len(b)) + b


def build_main(cmd_id, field, payload=b"", has_next=False):
    body = b"\x08" + varint(cmd_id)
    if has_next:
        body += b"\x18\x01"
    body += varint((field << 3) | 2) + varint(len(payload)) + payload
    return varint(len(body)) + body


class Msg:
    __slots__ = ("id", "status", "has_next", "field", "payload")

    def __init__(self):
        self.id = 0
        self.status = 0
        self.has_next = False
        self.field = 0
        self.payload = b""


def parse_main(body):
    m = Msg()
    for f, v in walk(body):
        if isinstance(v, int):
            if f == 1:
                m.id = v
            elif f == 2:
                m.status = v
            elif f == 3:
                m.has_next = bool(v)
        elif f >= 4 and f != 19:
            m.field = f
            m.payload = v
    return m


# ---------- transports ----------
class TcpTransport:
    def __init__(self, host="127.0.0.1", port=8765):
        self.host, self.port, self.sock = host, port, None

    def open(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        self.sock.settimeout(0.2)

    def read(self):
        try:
            data = self.sock.recv(4096)
            return None if data == b"" else data
        except socket.timeout:
            return b""
        except OSError:
            return None

    def write(self, data):
        self.sock.sendall(data)

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


class SerialTransport:
    """Flipper USB serial port: starts the RPC session from the CLI like qFlipper does."""

    def __init__(self, port):
        self.port, self.ser = port, None

    def open(self):
        import serial  # pyserial
        self.ser = serial.Serial(self.port, 230400, timeout=0.2)
        self.ser.reset_input_buffer()
        self.ser.write(b"\r")
        self._read_until(b">: ", 1.5)
        self.ser.write(b"start_rpc_session\r")
        self._read_until(b"\n", 2.0)       # the echo of the command; RPC messages follow

    def _read_until(self, marker, timeout):
        end = time.time() + timeout
        data = b""
        while time.time() < end and marker not in data:
            data += self.ser.read(256)
        return data

    def read(self):
        try:
            return self.ser.read(1024)
        except Exception:
            return None

    def write(self, data):
        self.ser.write(data)
        self.ser.flush()

    def close(self):
        try:
            self.ser.close()
        except Exception:
            pass


def list_serial_ports():
    try:
        from serial.tools import list_ports
    except ImportError:
        return []
    out = []
    for p in list_ports.comports():
        flipper = (p.vid == 0x0483 and p.pid == 0x5740)
        out.append((p.device, "%s%s" % (p.device, "  (Flipper)" if flipper else ""), flipper))
    out.sort(key=lambda x: not x[2])
    return out


# ---------- client ----------
class Flipper:
    def __init__(self, transport):
        self.t = transport
        self._next_id = 1
        self._id_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self.waiters = {}
        self.buf = bytearray()
        self.running = False
        self.frame_cb = None
        self.on_close = None

    # connection
    def open(self):
        self.t.open()
        self.running = True
        threading.Thread(target=self._reader, daemon=True).start()

    def close(self):
        self.on_close = None
        self.running = False
        self.t.close()

    def _reader(self):
        while self.running:
            try:
                data = self.t.read()
            except Exception:
                data = None
            if data is None:
                break
            if data:
                self.buf += data
                self._drain()
        was_running = self.running
        self.running = False
        if was_running and self.on_close:
            self.on_close()

    def _drain(self):
        while True:
            r = read_varint(self.buf, 0)
            if r is None:
                return
            ln, p = r
            if len(self.buf) - p < ln:
                return
            body = bytes(self.buf[p:p + ln])
            del self.buf[:p + ln]
            self._dispatch(parse_main(body))

    def _dispatch(self, m):
        if m.field == 22:                       # gui_screen_frame
            cb = self.frame_cb
            if cb:
                data, orient = b"", 0
                for f, v in walk(m.payload):
                    if f == 1 and isinstance(v, bytes):
                        data = v
                    elif f == 2 and isinstance(v, int):
                        orient = v
                cb(data, orient)
            return
        q = self.waiters.get(m.id)
        if q is not None:
            q.put(m)

    def _new_id(self):
        with self._id_lock:
            i = self._next_id
            self._next_id += 1
            return i

    def _send(self, data):
        with self._write_lock:
            self.t.write(data)

    # generic request
    def call(self, field, payload=b"", timeout=10.0, check=True):
        cid = self._new_id()
        q = queue.Queue()
        self.waiters[cid] = q
        try:
            self._send(build_main(cid, field, payload))
            return self._collect(q, timeout, check)
        finally:
            self.waiters.pop(cid, None)

    def _collect(self, q, timeout, check):
        msgs = []
        while True:
            try:
                m = q.get(timeout=timeout)
            except queue.Empty:
                raise RpcError(-1, "no answer from the Flipper (timeout)")
            msgs.append(m)
            if m.status != 0 or not m.has_next:
                break
        if check and msgs[-1].status != 0:
            raise RpcError(msgs[-1].status)
        return msgs

    def fire(self, field, payload=b""):
        self._send(build_main(self._new_id(), field, payload))

    # ---------- system ----------
    def ping(self):
        self.call(5)

    def _key_values(self, field):
        out = []
        for m in self.call(field):
            k = v = ""
            for f, val in walk(m.payload):
                if f == 1 and isinstance(val, bytes):
                    k = val.decode("utf-8", "replace")
                elif f == 2 and isinstance(val, bytes):
                    v = val.decode("utf-8", "replace")
            if k:
                out.append((k, v))
        return out

    def device_info(self):
        return self._key_values(32)

    def power_info(self):
        return self._key_values(44)

    def protobuf_version(self):
        m = self.call(39)[-1]
        major = minor = 0
        for f, v in walk(m.payload):
            if f == 1:
                major = v
            elif f == 2:
                minor = v
        return "%d.%d" % (major, minor)

    def datetime(self):
        m = self.call(35)[-1]
        t = [0] * 7
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, bytes):
                for f2, v2 in walk(v):
                    if isinstance(v2, int) and 1 <= f2 <= 7:
                        t[f2 - 1] = v2
        return "%04d-%02d-%02d %02d:%02d:%02d" % (t[5], t[4], t[3], t[0], t[1], t[2])

    def set_datetime(self, dt):
        wd = dt.isoweekday()                   # Monday = 1 ... Sunday = 7
        body = b"".join(bytes([(n << 3)]) + varint(v) for n, v in
                        ((1, dt.hour), (2, dt.minute), (3, dt.second), (4, dt.day), (5, dt.month), (6, dt.year), (7, wd)))
        self.call(37, b"\x0a" + varint(len(body)) + body)

    def play_alert(self):
        self.fire(38)

    def unlock(self):
        self.fire(67)

    def reboot(self, mode=0):
        self.fire(31, b"\x08" + varint(mode))

    def factory_reset(self):
        self.fire(34)

    def backup_create(self, path):
        self.call(42, str_field(path), timeout=60)

    def backup_restore(self, path):
        self.call(43, str_field(path), timeout=60)

    # ---------- storage ----------
    def storage_info(self, path):
        m = self.call(28, str_field(path))[-1]
        total = free = 0
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, int):
                total = v
            elif f == 2 and isinstance(v, int):
                free = v
        return total, free

    def list_dir(self, path):
        out = []
        for m in self.call(7, str_field(path)):
            for f, v in walk(m.payload):
                if f == 1 and isinstance(v, bytes):
                    typ, name, size = 0, "", 0
                    for f2, v2 in walk(v):
                        if f2 == 1 and isinstance(v2, int):
                            typ = v2
                        elif f2 == 2 and isinstance(v2, bytes):
                            name = v2.decode("utf-8", "replace")
                        elif f2 == 3 and isinstance(v2, int):
                            size = v2
                    if name:
                        out.append((name, typ == 1, size))
        return out

    def read_file(self, path):
        data = bytearray()
        for m in self.call(9, str_field(path), timeout=30):
            for f, v in walk(m.payload):
                if f == 1 and isinstance(v, bytes):
                    for f2, v2 in walk(v):
                        if f2 == 4 and isinstance(v2, bytes):
                            data += v2
        return bytes(data)

    def mkdir(self, path):
        self.call(13, str_field(path))

    def delete(self, path, recursive=False):
        self.call(12, str_field(path) + (b"\x10\x01" if recursive else b""))

    def rename(self, old, new):
        self.call(30, str_field(old) + str_field(new, 2))

    def md5(self, path):
        m = self.call(14, str_field(path))[-1]
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, bytes):
                return v.decode("ascii", "replace")
        return ""

    def write_file(self, path, data, progress=None):
        parts = [p for p in path.split("/") if p]
        for i in range(2, len(parts)):         # create missing folders (errors for existing ones are ignored)
            self.call(13, str_field("/" + "/".join(parts[:i])), check=False)
        cid = self._new_id()
        q = queue.Queue()
        self.waiters[cid] = q
        try:
            pb = path.encode("utf-8")
            offsets = list(range(0, max(len(data), 1), 512))
            for n, off in enumerate(offsets):
                part = data[off:off + 512]
                last = n == len(offsets) - 1
                file = b"\x22" + varint(len(part)) + part
                wr = b"\x0a" + varint(len(pb)) + pb + b"\x12" + varint(len(file)) + file
                self._send(build_main(cid, 11, wr, has_next=not last))
                if progress:
                    progress(min(off + 512, len(data)), len(data))
            self._collect(q, 30, True)
        finally:
            self.waiters.pop(cid, None)

    # ---------- apps ----------
    def start_app(self, name, args=""):
        payload = str_field(name) + (str_field(args, 2) if args else b"")
        msgs = self.call(16, payload, check=False)
        return msgs[-1].status

    def app_load_file(self, path):
        self.call(48, str_field(path))

    def app_press_release(self, args=""):
        self.call(75, str_field(args) if args else b"")

    def app_exit(self):
        self.fire(47)

    def app_error(self):
        m = self.call(63, check=False)[-1]
        code, text = 0, ""
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, int):
                code = v
            elif f == 2 and isinstance(v, bytes):
                text = v.decode("utf-8", "replace")
        return text or "error %d" % code

    def app_locked(self):
        m = self.call(17)[-1]
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, int):
                return bool(v)
        return False

    # ---------- screen and keys ----------
    def start_screen(self, cb):
        self.frame_cb = cb
        self.call(20)

    def stop_screen(self):
        self.frame_cb = None
        try:
            self.call(21, timeout=3)
        except RpcError:
            pass

    def key(self, key):
        """key: 0 up, 1 down, 2 right, 3 left, 4 ok, 5 back"""
        for typ in (0, 2, 1):                  # press, short, release
            self.fire(23, bytes([0x08, key, 0x10, typ]))

    # ---------- GPIO ----------
    def gpio_set_mode(self, pin, mode):
        self.call(51, bytes([0x08, pin, 0x10, mode]))

    def gpio_set_pull(self, pin, pull):
        self.call(52, bytes([0x08, pin, 0x10, pull]))

    def gpio_write(self, pin, value):
        self.call(57, bytes([0x08, pin, 0x10, value]))

    def gpio_read(self, pin):
        m = self.call(55, bytes([0x08, pin]))[-1]
        for f, v in walk(m.payload):
            if f == 2 and isinstance(v, int):
                return v
        return 0

    def otg_get(self):
        m = self.call(72)[-1]
        for f, v in walk(m.payload):
            if f == 1 and isinstance(v, int):
                return bool(v)
        return False

    def otg_set(self, on):
        self.call(74, bytes([0x08, 1 if on else 0]))
