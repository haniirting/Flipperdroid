package com.example.nfcuid

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Turns a GitHub link into the bytes of a file with the given extension (call from a background thread).
// .html add-ons for this app and .fap files for a real Flipper both use it.
// Accepted links:
//   github.com/<user>/<repo>/blob/<branch>/path/file      (file page)
//   github.com/<user>/<repo>/raw/...  and raw.githubusercontent.com/...
//   github.com/<user>/<repo>/releases/download/<tag>/file (release asset)
//   github.com/<user>/<repo>, /releases, /releases/tag/<tag>  (first matching asset of the release)
// For .html add-ons a repo (or /tree/<branch>/<folder>) link also works if it contains index.html or addon.html.
object GithubInstaller {

    class Result(val name: String, val data: ByteArray, val repo: String = "")

    private val htmlNames = listOf("addon.html", "index.html", "main.html")

    private fun http(url: String, accept: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "FlipperPhone")
        if (accept != null) c.setRequestProperty("Accept", accept)
        try {
            if (c.responseCode != 200) throw Exception("GitHub answered HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 8_000_000) throw Exception("File is larger than 8 MB")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, ext: String, repo: String): Result {
        val name = url.substringAfterLast("/")
        if (!name.endsWith(ext)) throw Exception("The link must point to a $ext file")
        return Result(name, http(url), repo)
    }

    fun fetch(link: String, ext: String = ".fap"): Result {
        val u = link.trim().substringBefore("#").substringBefore("?")
        if (u.startsWith("https://raw.githubusercontent.com/")) return download(u, ext, "")
        val m = Regex("^https://github\\.com/([^/]+)/([^/]+)(/.*)?$").find(u)
            ?: throw Exception("Only github.com links are supported")
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        val rest = m.groupValues[3]

        if (rest.contains("/releases/download/") || rest.startsWith("/raw/")) return download(u, ext, repo)
        if (rest.startsWith("/blob/")) {
            return download("https://github.com/$owner/$repo/raw/" + rest.removePrefix("/blob/"), ext, repo)
        }
        if (rest.startsWith("/tree/") && ext != ".fap") {
            val base = "https://raw.githubusercontent.com/$owner/$repo/" + rest.removePrefix("/tree/").trimEnd('/')
            for (f in htmlNames) {
                try { return Result(f, http("$base/$f"), repo) } catch (_: Exception) {}
            }
            throw Exception("No addon.html / index.html in that folder")
        }

        // repo root, releases or a release page: look at the release assets first
        val api = if (rest.startsWith("/releases/tag/")) {
            "https://api.github.com/repos/$owner/$repo/releases/tags/" + rest.removePrefix("/releases/tag/")
        } else {
            "https://api.github.com/repos/$owner/$repo/releases/latest"
        }
        var releaseError: Exception? = null
        try {
            val json = JSONObject(String(http(api, "application/vnd.github+json")))
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.getString("name")
                if (n.endsWith(ext)) return Result(n, http(a.getString("browser_download_url")), repo)
            }
        } catch (e: Exception) {
            releaseError = e
        }
        if (ext != ".fap") {
            for (f in htmlNames) {
                try { return Result(f, http("https://raw.githubusercontent.com/$owner/$repo/HEAD/$f"), repo) } catch (_: Exception) {}
            }
            throw Exception("No $ext file in the latest release and no addon.html / index.html in the repo")
        }
        throw Exception(releaseError?.message ?: "No $ext file found in that release")
    }
}
