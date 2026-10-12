package com.example.nfcuid

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

    private fun state(s: String) { main.post { onState(s) } }

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
            cb(m)
            if (m.status != 0 || !m.hasNext) handler = null
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
            cb(m.status == 0, if (m.status == 0) null else "error ${m.status}")
            handler = null
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
