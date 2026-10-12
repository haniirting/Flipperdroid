package com.example.nfcuid

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

// Tiny web server: open the link in a browser on a computer in the same Wi-Fi network
// and download the .nfc / .ir files saved by the app. Protected by a random PIN in the link.
class PcServerActivity : Activity() {

    private val bg = Color.rgb(255, 130, 0)
    private val fg = Color.BLACK
    private val port = 8080
    private val pin = (1000..9999).random().toString()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private lateinit var info: TextView
    private lateinit var toggle: Button

    private fun tv(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(bg)
        setBackgroundColor(fg)
        typeface = Typeface.MONOSPACE
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(32, 48, 32, 32)
        }
        root.addView(tv("PC link (Wi-Fi)", 22f, true))
        info = tv(
            "Phone and computer must be on the same Wi-Fi.\n" +
                "Press Start, then open the link in a browser on the computer.\n\n" +
                "Anyone on this Wi-Fi who knows the link can download your saved files. Stop when done.",
            13f
        )
        info.setPadding(0, 24, 0, 24)
        root.addView(info)
        toggle = btn("Start") { if (running) stop() else start() }
        root.addView(toggle)
        root.addView(btn("Back") { finish() })
        setContentView(root)
    }

    private fun ip(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val a = wm.connectionInfo.ipAddress
        return if (a == 0) "" else "%d.%d.%d.%d".format(a and 0xff, a shr 8 and 0xff, a shr 16 and 0xff, a shr 24 and 0xff)
    }

    private fun start() {
        val addr = ip()
        if (addr.isEmpty()) {
            info.text = "No Wi-Fi connection. Connect the phone to Wi-Fi first."
            return
        }
        running = true
        toggle.text = "Stop"
        info.text = "Open on your computer:\n\nhttp://$addr:$port/$pin/\n\nFiles: saved .nfc / .ir files from this app."
        Thread {
            try {
                val s = ServerSocket(port)
                server = s
                while (running) {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                }
            } catch (e: Exception) {
                // closed on stop
            }
        }.start()
    }

    private fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        toggle.text = "Start"
        info.text = "Server stopped."
    }

    private fun respond(o: OutputStream, code: String, type: String, body: ByteArray, extra: String = "") {
        o.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
        o.write(body)
        o.flush()
    }

    private fun handle(s: Socket) {
        s.use {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val line = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val out = s.getOutputStream()
            val parts = line.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(out, "405 Method Not Allowed", "text/plain", "GET only".toByteArray())
                return
            }
            val path = parts[1].substringBefore("?")
            val base = "/$pin"
            val dir = File(filesDir, "export")
            if (path.startsWith("$base/f/")) {
                val name = URLDecoder.decode(path.removePrefix("$base/f/"), "UTF-8")
                val f = File(dir, name)
                if (name.contains("/") || name.contains("\\") || name.contains("..") || !f.isFile) {
                    respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
                } else {
                    respond(out, "200 OK", "application/octet-stream", f.readBytes(),
                        "Content-Disposition: attachment; filename=\"$name\"\r\n")
                }
            } else if (path == base || path == "$base/") {
                val sb = StringBuilder("<html><head><meta charset=utf-8></head><body><h2>Flipper Phone files</h2><ul>")
                val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                if (files.isEmpty()) sb.append("<li>No files yet. Save a .nfc or .ir file in the app first.</li>")
                for (f in files) {
                    val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                    sb.append("<li><a href=\"$base/f/$enc\">${TextUtils.htmlEncode(f.name)}</a> (${f.length()} B)</li>")
                }
                sb.append("</ul></body></html>")
                respond(out, "200 OK", "text/html; charset=utf-8", sb.toString().toByteArray())
            } else {
                respond(out, "403 Forbidden", "text/plain", "Wrong link".toByteArray())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }
}
