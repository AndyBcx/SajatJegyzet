package hu.sajat.jegyzet

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Egyszerű kétirányú WebDAV szinkron (Nextcloud, ownCloud, Synology, stb.).
 * Szabály: ami csak az egyik oldalon van, átmásolódik; ha mindkét oldalon van,
 * az újabb módosítási idejű nyer. Törlés NEM szinkronizálódik (biztonsági okból).
 */
object WebDav {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private class Remote(val name: String, val modified: Long)

    fun sync(base: String, user: String, pass: String, store: Store): String {
        require(base.startsWith("https://") || base.startsWith("http://")) { "Hibás URL" }
        val root = base.trimEnd('/')
        val auth = Credentials.basic(user, pass)
        var up = 0
        var down = 0

        fun req(url: String, method: String, body: RequestBody? = null, depth: String? = null, mtime: Long? = null) =
            Request.Builder().url(url).header("Authorization", auth)
                .apply {
                    depth?.let { header("Depth", it) }
                    mtime?.let { header("X-OC-MTime", (it / 1000).toString()) }
                }
                .method(method, body).build()

        // mappák létrehozása (ha már léteznek, 405-öt kapunk – nem baj)
        http.newCall(req("$root/", "MKCOL")).execute().close()

        for ((name, dir) in listOf("notes" to store.notesDir, "recordings" to store.recDir)) {
            http.newCall(req("$root/$name/", "MKCOL")).execute().close()
            val remote = list("$root/$name/") { u -> req(u, "PROPFIND", depth = "1") }
            val remoteMap = remote.associateBy { it.name }

            for (f in dir.listFiles().orEmpty().filter { it.isFile }) {
                val r = remoteMap[f.name]
                if (r == null || f.lastModified() > r.modified + TOL) {
                    val mt = if (f.extension == "md") "text/markdown".toMediaTypeOrNull() else "audio/mp4".toMediaTypeOrNull()
                    http.newCall(req("$root/$name/${enc(f.name)}", "PUT", f.asRequestBody(mt), mtime = f.lastModified()))
                        .execute().use { check(it.isSuccessful) { "Feltöltés sikertelen: ${f.name} (${it.code})" } }
                    up++
                }
            }
            for (r in remote) {
                val local = File(dir, r.name)
                if (!local.exists() || r.modified > local.lastModified() + TOL) {
                    http.newCall(req("$root/$name/${enc(r.name)}", "GET")).execute().use { resp ->
                        check(resp.isSuccessful) { "Letöltés sikertelen: ${r.name} (${resp.code})" }
                        val tmp = File(dir, r.name + ".part")
                        resp.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp.setLastModified(r.modified)
                        tmp.renameTo(local)
                    }
                    down++
                }
            }
        }
        return "Kész: $up feltöltve, $down letöltve"
    }

    private const val TOL = 3000L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun list(url: String, build: (String) -> Request): List<Remote> {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        http.newCall(build(url)).execute().use { resp ->
            check(resp.code == 207) { "Listázás sikertelen (${resp.code})" }
            val xml = resp.body!!.string()
            val out = mutableListOf<Remote>()
            val respRe = Regex("<(?:\\w+:)?response>(.*?)</(?:\\w+:)?response>", RegexOption.DOT_MATCHES_ALL)
            val hrefRe = Regex("<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>", RegexOption.DOT_MATCHES_ALL)
            val modRe = Regex("<(?:\\w+:)?getlastmodified>(.*?)</(?:\\w+:)?getlastmodified>", RegexOption.DOT_MATCHES_ALL)
            for (m in respRe.findAll(xml)) {
                val block = m.groupValues[1]
                val href = hrefRe.find(block)?.groupValues?.get(1) ?: continue
                if (href.endsWith("/")) continue // mappa
                val name = URLDecoder.decode(href.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                if (name.endsWith(".part")) continue
                val mod = modRe.find(block)?.groupValues?.get(1)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: 0L
                out += Remote(name, mod)
            }
            return out
        }
    }
}
