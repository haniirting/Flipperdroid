package com.example.flipperlink

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.min

class FlipperEntry(val name: String, val isDir: Boolean, val size: Int)
class FlipperMsg(val id: Int, val status: Int, val hasNext: Boolean, val field: Int, val payload: ByteArray)

// Minimal Flipper RPC client over the BLE serial service.
// UUIDs: firmware 1.4.3 targets/f7/ble_glue/services/serial_service_uuid.inc
// Message layout: flipper.proto / storage.proto from github.com/flipperdevices/flipperzero-protobuf
// (Main: command_id=1, command_status=2, has_next=3; ping=5/6, storage list=7/8, read=9/10), length-delimited.
class FlipperBle(private val ctx: Context, private val onState: (String) -> Unit) {

    companion object {
        val SERVICE: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val TX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")   // Flipper -> phone (indicate)
        val RX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")   // phone -> Flipper (write)
        val FLOW: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var nextId = 1
    private val rxBuf = ByteArrayOutputStream()
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()
    private val writeQ = ArrayDeque<ByteArray>()
    private var writing = false
    @Volatile private var handler: ((FlipperMsg) -> Unit)? = null
    @Volatile private var screenCb: ((ByteArray, Int) -> Unit)? = null
    @Volatile private var flowFree = -1   // free bytes in the Flipper RX buffer (-1 = unknown)
    @Volatile var rawSink: ((ByteArray) -> Unit)? = null   // receives every raw byte chunk from the Flipper (PC bridge)

    private fun state(s: String) { main.post { onState(s) } }

    // sends raw RPC bytes (already framed) to the Flipper; used by the PC bridge
    fun sendRaw(data: ByteArray) = enqueue(data)

    // ---------- connection ----------
    fun connect(dev: BluetoothDevice) {
        state("Connecting...")
        gatt = dev.connectGatt(ctx, false, callback)
    }

    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state("Connected, looking for Flipper service...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state("Disconnected")
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE)
            if (s == null) {
                state("Flipper serial service not found")
                return
            }
            rx = s.getCharacteristic(RX)
            pending.clear()
            s.getCharacteristic(FLOW)?.let { pending.add(it) }
            s.getCharacteristic(TX)?.let { pending.add(it) }
            g.requestMtu(512)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != 0) {
                state("Pairing needed: enter the code shown on the Flipper, then connect again")
                return
            }
            enableNext(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeQ) { writing = false }
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (c.uuid == FLOW) {
                if (v.size >= 4) {
                    flowFree = ((v[0].toInt() and 0xFF) shl 24) or ((v[1].toInt() and 0xFF) shl 16) or
                        ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
                    pump()
                }
                return
            }
            if (c.uuid != TX) return
            rawSink?.invoke(v.copyOf())
            rxBuf.write(v)
            drain()
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        val c = pending.removeFirstOrNull()
        if (c == null) {
            state("Ready")
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            enableNext(g)
            return
        }
        d.value = if (c.uuid == TX) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    // ---------- protobuf helpers ----------
    private fun varint(v: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v
        while (x >= 0x80) {
            o.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        o.write(x)
        return o.toByteArray()
    }

    private fun readVarint(b: ByteArray, pos: IntArray): Long {
        var r = 0L
        var shift = 0
        while (pos[0] < b.size) {
            val x = b[pos[0]++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if ((x and 0x80) == 0) break
            shift += 7
        }
        return r
    }

    private fun walk(b: ByteArray, f: (Int, Long, ByteArray?) -> Unit) {
        val pos = intArrayOf(0)
        while (pos[0] < b.size) {
            val tag = readVarint(b, pos).toInt()
            val fld = tag ushr 3
            when (tag and 7) {
                0 -> f(fld, readVarint(b, pos), null)
                2 -> {
                    val len = readVarint(b, pos).toInt()
                    val s = pos[0]
                    val e = min(s + len, b.size)
                    pos[0] = e
                    f(fld, 0L, b.copyOfRange(s, e))
                }
                1 -> pos[0] += 8
                5 -> pos[0] += 4
                else -> return
            }
        }
    }

    private fun parseMain(b: ByteArray): FlipperMsg {
        var id = 0
        var st = 0
        var next = false
        var field = 0
        var payload = ByteArray(0)
        walk(b) { f, v, bytes ->
            if (bytes == null) {
                when (f) {
                    1 -> id = v.toInt()
                    2 -> st = v.toInt()
                    3 -> next = v != 0L
                }
            } else if (f >= 4 && f != 19) {
                field = f
                payload = bytes
            }
        }
        return FlipperMsg(id, st, next, field, payload)
    }

    // incoming stream = sequence of (varint length, Main message)
    private fun drain() {
        var data = rxBuf.toByteArray()
        while (true) {
            var i = 0
            while (i < data.size && (data[i].toInt() and 0x80) != 0) i++
            if (i >= data.size) break
            val pos = intArrayOf(0)
            val len = readVarint(data, pos).toInt()
            if (data.size - pos[0] < len) break
            val msg = parseMain(data.copyOfRange(pos[0], pos[0] + len))
            data = data.copyOfRange(pos[0] + len, data.size)
            if (msg.field == 22) {
                // screen frame (gui.proto ScreenFrame: data=1, orientation=2)
                var data = ByteArray(0)
                var orient = 0
                walk(msg.payload) { f, v, b ->
                    if (f == 1 && b != null) data = b
                    else if (f == 2 && b == null) orient = v.toInt()
                }
                val sc = screenCb
                if (sc != null) {
                    val d = data
                    val o = orient
                    main.post { sc(d, o) }
                }
            } else {
                val h = handler
                if (h != null) main.post { h(msg) }
            }
        }
        rxBuf.reset()
        rxBuf.write(data)
    }

    // ---------- sending ----------
    private fun enqueue(data: ByteArray) {
        val mx = (mtu - 3).coerceAtLeast(20)
        synchronized(writeQ) {
            var i = 0
            while (i < data.size) {
                writeQ.add(data.copyOfRange(i, min(i + mx, data.size)))
                i += mx
            }
        }
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        val c = rx ?: return
        var chunk: ByteArray? = null
        synchronized(writeQ) {
            if (!writing) {
                val next = writeQ.firstOrNull()
                if (next != null && (flowFree < 0 || next.size <= flowFree)) {
                    chunk = writeQ.removeFirst()
                    writing = true
                    if (flowFree >= 0) flowFree -= next.size
                } else if (next != null) {
                    // wait for the Flipper to report free buffer space; give up waiting after 400 ms
                    main.postDelayed({ flowFree = -1; pump() }, 400)
                }
            }
        }
        val data = chunk ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = data
        if (!g.writeCharacteristic(c)) synchronized(writeQ) { writing = false }
    }

    private fun send(field: Int, payload: ByteArray, cb: (FlipperMsg) -> Unit) {
        val id = nextId++
        val body = ByteArrayOutputStream()
        body.write(0x08)
        body.write(varint(id))
        body.write(varint((field shl 3) or 2))
        body.write(varint(payload.size))
        body.write(payload)
        val msg = body.toByteArray()
        handler = { m ->
            if (m.status != 0 || !m.hasNext) handler = null
            cb(m)
        }
        enqueue(varint(msg.size) + msg)
    }

    private fun strField(s: String): ByteArray {
        val b = s.toByteArray()
        return byteArrayOf(0x0A) + varint(b.size) + b
    }

    // ---------- commands ----------
    fun ping(cb: (Boolean) -> Unit) = send(5, ByteArray(0)) { m -> cb(m.status == 0) }

    fun list(path: String, cb: (List<FlipperEntry>?, String?) -> Unit) {
        val files = ArrayList<FlipperEntry>()
        send(7, strField(path)) { m ->
            if (m.status != 0) {
                cb(null, "error ${m.status}")
            } else {
                walk(m.payload) { f, _, b ->
                    if (f == 1 && b != null) parseFile(b)?.let { files.add(it) }
                }
                if (!m.hasNext) cb(files, null)
            }
        }
    }

    fun read(path: String, cb: (ByteArray?, String?) -> Unit) {
        val out = ByteArrayOutputStream()
        send(9, strField(path)) { m ->
            if (m.status != 0) {
                cb(null, "error ${m.status}")
            } else {
                walk(m.payload) { f, _, b ->
                    if (f == 1 && b != null) walk(b) { f2, _, d -> if (f2 == 4 && d != null) out.write(d) }
                }
                if (!m.hasNext) cb(out.toByteArray(), null)
            }
        }
    }

    // ---------- remote screen, keys, upload ----------
    private fun fire(field: Int, payload: ByteArray) {
        val id = nextId++
        val body = ByteArrayOutputStream()
        body.write(0x08)
        body.write(varint(id))
        body.write(varint((field shl 3) or 2))
        body.write(varint(payload.size))
        body.write(payload)
        val msg = body.toByteArray()
        enqueue(varint(msg.size) + msg)
    }

    fun startScreen(cb: (ByteArray, Int) -> Unit) {
        screenCb = cb
        send(20, ByteArray(0)) { }      // gui_start_screen_stream_request
    }

    fun stopScreen() {
        screenCb = null
        send(21, ByteArray(0)) { }      // gui_stop_screen_stream_request
    }

    // key: 0 up, 1 down, 2 right, 3 left, 4 ok, 5 back (gui.proto InputKey)
    fun key(key: Int) {
        for (type in intArrayOf(0, 2, 1)) {          // PRESS, SHORT, RELEASE (InputType)
            fire(23, byteArrayOf(0x08, key.toByte(), 0x10, type.toByte()))
        }
    }

    // key/value lists (flipper.proto + system.proto): device info 32 -> 33, power info 44 -> 45
    private fun keyValues(reqField: Int, cb: (List<Pair<String, String>>?, String?) -> Unit) {
        val out = ArrayList<Pair<String, String>>()
        send(reqField, ByteArray(0)) { m ->
            if (m.status != 0) {
                cb(null, "error ${m.status}")
            } else {
                var k = ""
                var v = ""
                walk(m.payload) { f, _, b ->
                    if (b != null) {
                        if (f == 1) k = String(b) else if (f == 2) v = String(b)
                    }
                }
                if (k.isNotEmpty()) out.add(Pair(k, v))
                if (!m.hasNext) cb(out, null)
            }
        }
    }

    fun deviceInfo(cb: (List<Pair<String, String>>?, String?) -> Unit) = keyValues(32, cb)

    fun powerInfo(cb: (List<Pair<String, String>>?, String?) -> Unit) = keyValues(44, cb)

    // system_play_audiovisual_alert_request = 38 (makes the Flipper beep and flash)
    fun playAlert() = fire(38, ByteArray(0))

    // system_set_datetime_request = 37, DateTime: hour=1 minute=2 second=3 day=4 month=5 year=6 weekday=7 (1..7, Monday = 1)
    fun setTime(c: java.util.Calendar) {
        val weekday = ((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val dt = ByteArrayOutputStream()
        dt.write(0x08); dt.write(varint(c.get(java.util.Calendar.HOUR_OF_DAY)))
        dt.write(0x10); dt.write(varint(c.get(java.util.Calendar.MINUTE)))
        dt.write(0x18); dt.write(varint(c.get(java.util.Calendar.SECOND)))
        dt.write(0x20); dt.write(varint(c.get(java.util.Calendar.DAY_OF_MONTH)))
        dt.write(0x28); dt.write(varint(c.get(java.util.Calendar.MONTH) + 1))
        dt.write(0x30); dt.write(varint(c.get(java.util.Calendar.YEAR)))
        dt.write(0x38); dt.write(varint(weekday))
        val d = dt.toByteArray()
        fire(37, byteArrayOf(0x0A) + varint(d.size) + d)
    }

    // ---------- more RPC commands ----------
    // field numbers: flipper.proto; messages: storage.proto, system.proto, application.proto, gpio.proto, desktop.proto
    private fun ack(field: Int, payload: ByteArray, cb: (Boolean, Int) -> Unit) =
        send(field, payload) { m -> cb(m.status == 0, m.status) }

    // storage_info_request = 28 -> 29 {total_space = 1, free_space = 2}
    fun storageInfo(path: String, cb: (Long, Long, Int) -> Unit) {
        send(28, strField(path)) { m ->
            var total = 0L
            var free = 0L
            walk(m.payload) { f, v, b ->
                if (b == null) {
                    if (f == 1) total = v else if (f == 2) free = v
                }
            }
            cb(total, free, m.status)
        }
    }

    // storage_delete_request = 12 {path = 1, recursive = 2}
    fun delete(path: String, recursive: Boolean, cb: (Boolean, Int) -> Unit) {
        var p = strField(path)
        if (recursive) p += byteArrayOf(0x10, 1)
        ack(12, p, cb)
    }

    fun mkdir(path: String, cb: (Boolean, Int) -> Unit) = ack(13, strField(path), cb)

    // storage_rename_request = 30 {old_path = 1, new_path = 2}
    fun rename(oldPath: String, newPath: String, cb: (Boolean, Int) -> Unit) {
        val n = newPath.toByteArray()
        ack(30, strField(oldPath) + byteArrayOf(0x12) + varint(n.size) + n, cb)
    }

    // storage_md5sum_request = 14 -> 15 {md5sum = 1}
    fun md5(path: String, cb: (String?, String?) -> Unit) {
        send(14, strField(path)) { m ->
            if (m.status != 0) {
                cb(null, "error ${m.status}")
            } else {
                var s = ""
                walk(m.payload) { f, _, b -> if (f == 1 && b != null) s = String(b) }
                cb(s, null)
            }
        }
    }

    // storage_backup_create_request = 42, storage_backup_restore_request = 43 {archive_path = 1}
    fun backupCreate(path: String, cb: (Boolean, Int) -> Unit) = ack(42, strField(path), cb)
    fun backupRestore(path: String, cb: (Boolean, Int) -> Unit) = ack(43, strField(path), cb)

    // system_reboot_request = 31 {mode = 1: OS 0, DFU 1, UPDATE 2}; system_factory_reset_request = 34
    fun reboot(mode: Int) = fire(31, byteArrayOf(0x08, mode.toByte()))
    fun factoryReset() = fire(34, ByteArray(0))

    // desktop_unlock_request = 67
    fun unlock() = fire(67, ByteArray(0))

    // system_protobuf_version_request = 39 -> 40 {major = 1, minor = 2}
    fun protobufVersion(cb: (String?) -> Unit) {
        send(39, ByteArray(0)) { m ->
            if (m.status != 0) {
                cb(null)
            } else {
                var major = 0L
                var minor = 0L
                walk(m.payload) { f, v, b ->
                    if (b == null) {
                        if (f == 1) major = v else if (f == 2) minor = v
                    }
                }
                cb("$major.$minor")
            }
        }
    }

    // system_get_datetime_request = 35 -> 36 {datetime = 1: hour 1, minute 2, second 3, day 4, month 5, year 6}
    fun dateTime(cb: (String?) -> Unit) {
        send(35, ByteArray(0)) { m ->
            if (m.status != 0) {
                cb(null)
            } else {
                val t = LongArray(7)
                walk(m.payload) { f, _, b ->
                    if (f == 1 && b != null) walk(b) { f2, v2, b2 -> if (b2 == null && f2 in 1..7) t[f2 - 1] = v2 }
                }
                cb("%04d-%02d-%02d %02d:%02d:%02d".format(t[5], t[4], t[3], t[0], t[1], t[2]))
            }
        }
    }

    // app_lock_status_request = 17 -> 18 {locked = 1}
    fun appLocked(cb: (Boolean?) -> Unit) {
        send(17, ByteArray(0)) { m ->
            if (m.status != 0) {
                cb(null)
            } else {
                var locked = false
                walk(m.payload) { f, v, b -> if (f == 1 && b == null) locked = v != 0L }
                cb(locked)
            }
        }
    }

    // app_exit_request = 47, app_load_file_request = 48 {path = 1},
    // app_button_press_release_request = 75 {args = 1, index = 2}, app_get_error_request = 63 -> 64 {code = 1, text = 2}
    fun appExit() = fire(47, ByteArray(0))
    fun appLoadFile(path: String, cb: (Boolean, Int) -> Unit) = ack(48, strField(path), cb)
    fun appPressRelease(args: String, cb: (Boolean, Int) -> Unit) =
        ack(75, if (args.isEmpty()) ByteArray(0) else strField(args), cb)

    fun getAppError(cb: (String) -> Unit) {
        send(63, ByteArray(0)) { m ->
            var code = 0L
            var text = ""
            walk(m.payload) { f, v, b ->
                if (f == 1 && b == null) code = v else if (f == 2 && b != null) text = String(b)
            }
            cb(if (text.isNotEmpty()) text else "error $code")
        }
    }

    // GPIO (gpio.proto): pins PC0 0, PC1 1, PC3 2, PB2 3, PB3 4, PA4 5, PA6 6, PA7 7; mode OUTPUT 0, INPUT 1; pull NO 0, UP 1, DOWN 2
    fun gpioSetMode(pin: Int, mode: Int, cb: (Boolean, Int) -> Unit) =
        ack(51, byteArrayOf(0x08, pin.toByte(), 0x10, mode.toByte()), cb)

    fun gpioSetPull(pin: Int, pull: Int, cb: (Boolean, Int) -> Unit) =
        ack(52, byteArrayOf(0x08, pin.toByte(), 0x10, pull.toByte()), cb)

    fun gpioWrite(pin: Int, value: Int, cb: (Boolean, Int) -> Unit) =
        ack(57, byteArrayOf(0x08, pin.toByte(), 0x10, value.toByte()), cb)

    fun gpioRead(pin: Int, cb: (Int?) -> Unit) {
        send(55, byteArrayOf(0x08, pin.toByte())) { m ->
            if (m.status != 0) {
                cb(null)
            } else {
                var v = 0L
                walk(m.payload) { f, x, b -> if (f == 2 && b == null) v = x }
                cb(v.toInt())
            }
        }
    }

    // gpio_get_otg_mode = 72 -> 73 {mode = 1}, gpio_set_otg_mode = 74 {mode = 1} (5 V on pin 1)
    fun gpioOtg(cb: (Boolean?) -> Unit) {
        send(72, ByteArray(0)) { m ->
            if (m.status != 0) {
                cb(null)
            } else {
                var on = false
                walk(m.payload) { f, v, b -> if (f == 1 && b == null) on = v != 0L }
                cb(on)
            }
        }
    }

    fun gpioSetOtg(on: Boolean, cb: (Boolean, Int) -> Unit) =
        ack(74, byteArrayOf(0x08, if (on) 1 else 0), cb)

    // Starts an app by path (e.g. /ext/apps/Misc/x.fap) or name: app_start_request = 16, StartRequest{name=1, args=2}
    fun startApp(name: String, args: String, cb: (Boolean, Int) -> Unit) {
        val n = name.toByteArray()
        var p = byteArrayOf(0x0A) + varint(n.size) + n
        if (args.isNotEmpty()) {
            val a = args.toByteArray()
            p = p + byteArrayOf(0x12) + varint(a.size) + a
        }
        send(16, p) { m -> cb(m.status == 0, m.status) }
    }

    // Writes a file to the Flipper (storage.proto WriteRequest; chunks share one command id, has_next on all but the last)
    fun upload(path: String, data: ByteArray, cb: (Boolean, String?) -> Unit) {
        val segs = path.split("/").filter { it.isNotEmpty() }
        for (i in 2 until segs.size) {
            fire(13, strField("/" + segs.take(i).joinToString("/")))   // mkdir (errors for existing dirs are ignored)
        }
        val id = nextId++
        val pathBytes = path.toByteArray()
        handler = { m ->
            handler = null
            cb(m.status == 0, if (m.status == 0) null else "error ${m.status}")
        }
        var off = 0
        do {
            val end = min(off + 512, data.size)
            val part = data.copyOfRange(off, end)
            val last = end >= data.size
            val file = byteArrayOf(0x22) + varint(part.size) + part
            val wr = byteArrayOf(0x0A) + varint(pathBytes.size) + pathBytes + byteArrayOf(0x12) + varint(file.size) + file
            val body = ByteArrayOutputStream()
            body.write(0x08)
            body.write(varint(id))
            if (!last) {
                body.write(0x18)
                body.write(1)
            }
            body.write(varint((11 shl 3) or 2))
            body.write(varint(wr.size))
            body.write(wr)
            val msg = body.toByteArray()
            enqueue(varint(msg.size) + msg)
            off = end
        } while (off < data.size)
    }

    private fun parseFile(b: ByteArray): FlipperEntry? {
        var type = 0
        var name = ""
        var size = 0
        walk(b) { f, v, bytes ->
            when (f) {
                1 -> type = v.toInt()
                2 -> name = String(bytes ?: ByteArray(0))
                3 -> size = v.toInt()
            }
        }
        return if (name.isEmpty()) null else FlipperEntry(name, type == 1, size)
    }
}
