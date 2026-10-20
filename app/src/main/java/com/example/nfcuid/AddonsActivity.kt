package com.example.nfcuid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

// "Apps" of Flipper Phone: add-ons are single .html files (HTML + JavaScript) that run inside the app.
// See ADDONS.md for the format and the Flipper object an add-on can use.
class AddonsActivity : Activity() {

    private val bg = Color.rgb(255, 130, 0)
    private val fg = Color.BLACK
    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val reqImport = 61
    private var inEditor = false

    private val template = """<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{background:#FF8200;color:#000;font-family:monospace;padding:16px}
button{background:#000;color:#FF8200;border:0;padding:14px;margin:6px 0;width:100%;font-family:monospace;font-size:16px}
</style></head>
<body>
<h2>My add-on</h2>
<p id="ir"></p>
<button onclick="sendPower()">Send IR: NEC power (address 04, command 08)</button>
<button onclick="Flipper.toast('Hello from my add-on')">Toast</button>
<p id="nfc">Hold a card to the back of the phone...</p>
<script>
document.getElementById('ir').textContent = Flipper.hasIr() ? 'IR transmitter: found' : 'IR transmitter: not found';
function sendPower() {
  var ok = Flipper.irSend('NEC', '04', '08');
  Flipper.toast(ok ? 'Sent' : 'Not sent');
}
function onNfc(uid) {
  document.getElementById('nfc').textContent = 'Card: ' + uid;
}
</script>
</body></html>
"""

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
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

    private fun dir() = File(filesDir, "addons").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showList()
    }

    override fun onResume() {
        super.onResume()
        if (!inEditor) showList()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inEditor) showList() else super.onBackPressed()
    }

    // ---------- list ----------
    private fun showList() {
        inEditor = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(32, 48, 32, 32)
        }
        root.addView(tv("Apps", 22f, true))
        root.addView(tv("Add-ons are single .html files (HTML + JavaScript). They can send IR signals and read NFC cards.", 12f))
        root.addView(btn("New add-on") { showEditor(null) })
        root.addView(btn("Install from GitHub link") { askGithub() })
        root.addView(btn("Import .html file") { pickImport() })
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val files = dir().listFiles()?.filter { it.extension == "html" }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) items.addView(tv("\nNo add-ons yet.", 14f))
        for (f in files) {
            items.addView(tv(f.nameWithoutExtension, 18f).apply {
                setPadding(0, 22, 0, 22)
                setOnClickListener { menu(f) }
            })
        }
        root.addView(ScrollView(this).apply { addView(items) }, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(btn("Back") { finish() })
        setContentView(root)
    }

    private fun menu(f: File) {
        AlertDialog.Builder(this)
            .setTitle(f.nameWithoutExtension)
            .setItems(arrayOf("Run", "Edit", "Delete")) { _, i ->
                when (i) {
                    0 -> run(f)
                    1 -> showEditor(f)
                    2 -> { f.delete(); showList() }
                }
            }
            .show()
    }

    private fun run(f: File) {
        startActivity(Intent(this, AddonRunActivity::class.java).putExtra("path", f.absolutePath))
    }

    // ---------- editor ----------
    private fun showEditor(f: File?) {
        inEditor = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(32, 48, 32, 32)
        }
        root.addView(tv(if (f == null) "New add-on" else "Edit add-on", 22f, true))
        val name = EditText(this).apply {
            hint = "name (letters, digits, - _)"
            setText(f?.nameWithoutExtension ?: "my_addon")
            setTextColor(fg)
            setHintTextColor(Color.DKGRAY)
        }
        val code = EditText(this).apply {
            setText(f?.readText() ?: template)
            setTextColor(fg)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP
            isSingleLine = false
            setHorizontallyScrolling(true)
        }
        root.addView(name)
        root.addView(code, LinearLayout.LayoutParams(match, 0, 1f))
        val row = LinearLayout(this)
        row.addView(btn("Save") { save(name, code) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("Save + Run") { save(name, code)?.let { run(it) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("Cancel") { showList() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun save(name: EditText, code: EditText): File? {
        val n = name.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (n.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return null
        }
        val f = File(dir(), "$n.html")
        f.writeText(code.text.toString())
        Toast.makeText(this, "Saved $n", Toast.LENGTH_SHORT).show()
        return f
    }

    // ---------- install from a GitHub link ----------
    private fun askGithub() {
        val input = EditText(this).apply {
            hint = "https://github.com/user/repo/..."
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Install add-on from GitHub")
            .setMessage(
                "Paste a link to a single .html add-on: a file page, a raw link, a release asset, " +
                    "or a repo with addon.html / index.html. Add-ons can send IR and read NFC: " +
                    "install only add-ons you trust."
            )
            .setView(input)
            .setPositiveButton("Install + run") { _, _ -> install(input.text.toString(), true) }
            .setNeutralButton("Install only") { _, _ -> install(input.text.toString(), false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun install(link: String, launch: Boolean) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val r = GithubInstaller.fetch(link, ".html")
                var base = r.name.removeSuffix(".html")
                if (base in listOf("index", "addon", "main") && r.repo.isNotEmpty()) base = r.repo
                base = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val f = File(dir(), "$base.html")
                f.writeBytes(r.data)
                runOnUiThread {
                    Toast.makeText(this, "Installed: $base", Toast.LENGTH_SHORT).show()
                    showList()
                    if (launch) run(f)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- import a file from the phone ----------
    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqImport)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return "imported.html"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) { null } ?: return
        val base = displayName(uri).substringBeforeLast(".").replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "imported" }
        File(dir(), "$base.html").writeText(text)
        Toast.makeText(this, "Imported $base", Toast.LENGTH_SHORT).show()
        showList()
    }
}
