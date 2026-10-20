"""Self-test of flipper_rpc.py against a fake Flipper RPC server on localhost (no hardware needed)."""
import socket
import threading
import time
import datetime

from flipper_rpc import (Flipper, RpcError, TcpTransport, build_main, parse_main, read_varint, str_field, varint, walk)


def kv(key, value):
    return str_field(key, 1) + str_field(value, 2)


class FakeFlipper:
    def __init__(self):
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.files = {"/ext/nfc/card.nfc": b"Filetype: Flipper NFC device\nUID: 04 AA BB CC\n"}
        self.keys = []
        self.writes = {}
        threading.Thread(target=self.serve, daemon=True).start()

    def reply(self, conn, cid, field, payload=b"", has_next=False, status=0):
        body = b"\x08" + varint(cid)
        if status:
            body += b"\x10" + varint(status)
        if has_next:
            body += b"\x18\x01"
        body += varint((field << 3) | 2) + varint(len(payload)) + payload
        conn.sendall(varint(len(body)) + body)

    def serve(self):
        conn, _ = self.srv.accept()
        buf = bytearray()
        wbuf = {}
        while True:
            data = conn.recv(4096)
            if not data:
                return
            buf += data
            while True:
                r = read_varint(buf, 0)
                if r is None:
                    break
                ln, p = r
                if len(buf) - p < ln:
                    break
                m = parse_main(bytes(buf[p:p + ln]))
                del buf[:p + ln]
                self.handle(conn, m, wbuf)

    def handle(self, conn, m, wbuf):
        f = m.field
        if f == 5:
            self.reply(conn, m.id, 6, m.payload)
        elif f == 32:
            self.reply(conn, m.id, 33, kv("hardware.name", "Fake"), has_next=True)
            self.reply(conn, m.id, 33, kv("firmware.version", "1.4.3"))
        elif f == 7:
            path = [v for ff, v in walk(m.payload) if ff == 1][0].decode()
            if path == "/nope":
                self.reply(conn, m.id, 4, status=7)
                return
            files = b"".join(b"\x0a" + varint(len(x)) + x for x in (
                b"\x08\x01" + str_field("nfc", 2),
                b"\x08\x00" + str_field("card.nfc", 2) + b"\x18" + varint(44)))
            self.reply(conn, m.id, 8, files)
        elif f == 9:
            data = self.files.get([v for ff, v in walk(m.payload) if ff == 1][0].decode())
            if data is None:
                self.reply(conn, m.id, 4, status=7)
                return
            half = len(data) // 2
            for i, part in enumerate((data[:half], data[half:])):
                file = b"\x22" + varint(len(part)) + part
                self.reply(conn, m.id, 10, b"\x0a" + varint(len(file)) + file, has_next=(i == 0))
        elif f == 11:
            wr = dict((ff, v) for ff, v in walk(m.payload))
            path = wr[1].decode()
            file = dict((ff, v) for ff, v in walk(wr[2]))
            wbuf.setdefault(m.id, bytearray()).extend(file.get(4, b""))
            if not m.has_next:
                self.writes[path] = bytes(wbuf.pop(m.id))
                self.reply(conn, m.id, 4)
        elif f in (13, 12, 30, 37, 51, 52, 57):
            self.reply(conn, m.id, 4)
        elif f == 16:
            name = [v for ff, v in walk(m.payload) if ff == 1][0].decode()
            self.reply(conn, m.id, 4, status=16 if name == "bad" else 0)
        elif f == 20:
            self.reply(conn, m.id, 4)
            frame = bytes([0xFF] + [0] * 1023)
            self.reply(conn, m.id, 22, b"\x0a" + varint(len(frame)) + frame)
        elif f == 21:
            self.reply(conn, m.id, 4)
        elif f == 23:
            d = dict((ff, v) for ff, v in walk(m.payload))
            self.keys.append((d.get(1, 0), d.get(2, 0)))
        elif f == 28:
            self.reply(conn, m.id, 29, b"\x08" + varint(8 * 1024 ** 3) + b"\x10" + varint(3 * 1024 ** 3))
        elif f == 55:
            self.reply(conn, m.id, 56, b"\x10\x01")
        else:
            self.reply(conn, m.id, 4, status=3)


def main():
    fake = FakeFlipper()
    fl = Flipper(TcpTransport("127.0.0.1", fake.port))
    fl.open()

    fl.ping()
    assert fl.device_info() == [("hardware.name", "Fake"), ("firmware.version", "1.4.3")]
    assert fl.list_dir("/ext") == [("nfc", True, 0), ("card.nfc", False, 44)]
    assert fl.read_file("/ext/nfc/card.nfc") == fake.files["/ext/nfc/card.nfc"]
    try:
        fl.list_dir("/nope")
        raise SystemExit("expected an error")
    except RpcError as e:
        assert e.status == 7 and "not found" in str(e)

    big = bytes(range(256)) * 7                      # several 512-byte chunks
    fl.write_file("/ext/apps/Misc/test.fap", big)
    assert fake.writes["/ext/apps/Misc/test.fap"] == big
    fl.write_file("/ext/empty.txt", b"")
    assert fake.writes["/ext/empty.txt"] == b""

    assert fl.start_app("NFC", "RPC") == 0
    assert fl.start_app("bad", "") == 16
    assert fl.storage_info("/ext") == (8 * 1024 ** 3, 3 * 1024 ** 3)
    assert fl.gpio_read(2) == 1
    fl.mkdir("/ext/x"); fl.delete("/ext/x", True); fl.rename("/ext/a", "/ext/b")
    fl.set_datetime(datetime.datetime(2026, 10, 13, 12, 30, 5))

    frames = []
    fl.start_screen(lambda data, orient: frames.append((len(data), orient)))
    time.sleep(0.3)
    assert frames and frames[0][0] == 1024, frames
    fl.stop_screen()
    fl.key(4)
    time.sleep(0.3)
    assert fake.keys == [(4, 0), (4, 2), (4, 1)], fake.keys

    fl.close()
    print("ALL RPC TESTS PASSED")


if __name__ == "__main__":
    main()
