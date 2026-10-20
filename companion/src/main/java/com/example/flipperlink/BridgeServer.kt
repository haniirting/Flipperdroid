package com.example.flipperlink

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

// Relays raw Flipper RPC bytes between a TCP client on the phone's localhost (a PC reaches it through
// "adb forward tcp:8765 tcp:8765" over the USB cable) and the Bluetooth connection to the Flipper.
// It listens on 127.0.0.1 only, so nothing on the Wi-Fi network can reach it.
class BridgeServer(private val ble: FlipperBle, private val onState: (String) -> Unit) {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var client: Socket? = null

    fun start(port: Int = 8765) {
        running = true
        Thread {
            try {
                val s = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                server = s
                onState("Waiting for the PC on port $port...")
                while (running) {
                    val c = s.accept()
                    try { client?.close() } catch (_: Exception) {}
                    client = c
                    onState("PC connected")
                    ble.rawSink = { data ->
                        try {
                            c.getOutputStream().write(data)
                            c.getOutputStream().flush()
                        } catch (_: Exception) {
                        }
                    }
                    Thread { pumpFromPc(c) }.start()
                }
            } catch (e: Exception) {
                if (running) onState("Bridge error: ${e.message}")
            }
        }.start()
    }

    private fun pumpFromPc(c: Socket) {
        try {
            val input = c.getInputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                ble.sendRaw(buf.copyOf(n))
            }
        } catch (_: Exception) {
        }
        if (client === c) {
            ble.rawSink = null
            onState("PC disconnected")
        }
    }

    fun stop() {
        running = false
        ble.rawSink = null
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
    }
}
