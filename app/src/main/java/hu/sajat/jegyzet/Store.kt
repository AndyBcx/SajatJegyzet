package hu.sajat.jegyzet

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Note(val id: String, val title: String, val body: String, val modified: Long)

/** Minden adat sima fájl: jegyzetek = notes/<id>.md, felvételek = recordings/*.m4a */
class Store(ctx: Context) {
    val notesDir = File(ctx.filesDir, "notes").apply { mkdirs() }
    val recDir = File(ctx.filesDir, "recordings").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun notes(): List<Note> =
        notesDir.listFiles { f -> f.extension == "md" }.orEmpty().map { f ->
            val text = f.readText()
            val title = text.lineSequence().firstOrNull()?.trimStart('#', ' ')?.trim().orEmpty()
            Note(f.nameWithoutExtension, title.ifBlank { "(cím nélkül)" }, text, f.lastModified())
        }.sortedByDescending { it.modified }

    fun readNote(id: String): String = File(notesDir, "$id.md").takeIf { it.exists() }?.readText().orEmpty()

    fun saveNote(id: String?, text: String): String? {
        if (text.isBlank()) { id?.let { deleteNote(it) }; return null }
        val nid = id ?: UUID.randomUUID().toString()
        val f = File(notesDir, "$nid.md")
        if (!f.exists() || f.readText() != text) f.writeText(text)
        return nid
    }

    fun deleteNote(id: String) { File(notesDir, "$id.md").delete() }

    fun recordings(): List<File> =
        recDir.listFiles { f -> f.extension == "m4a" }.orEmpty().sortedByDescending { it.lastModified() }

    fun renameRecording(f: File, newName: String): Boolean {
        val clean = newName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isEmpty()) return false
        return f.renameTo(File(recDir, "$clean.m4a"))
    }

    // --- beállítások (WebDAV) ---
    var davUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) { prefs.edit().putString("url", v).apply() }
    var davUser: String
        get() = prefs.getString("user", "") ?: ""
        set(v) { prefs.edit().putString("user", v).apply() }
    var davPass: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) { prefs.edit().putString("pass", v).apply() }

    var autoSync: Boolean
        get() = prefs.getBoolean("autosync", false)
        set(v) { prefs.edit().putBoolean("autosync", v).apply() }
    var asrUrl: String
        get() = prefs.getString("asrUrl", "") ?: ""
        set(v) { prefs.edit().putString("asrUrl", v).apply() }
    var asrKey: String
        get() = prefs.getString("asrKey", "") ?: ""
        set(v) { prefs.edit().putString("asrKey", v).apply() }
    var asrModel: String
        get() = prefs.getString("asrModel", "whisper-1") ?: "whisper-1"
        set(v) { prefs.edit().putString("asrModel", v).apply() }
    var asrLang: String
        get() = prefs.getString("asrLang", "hu") ?: "hu"
        set(v) { prefs.edit().putString("asrLang", v).apply() }

    /** Importált szöveg -> új jegyzet. Cím = fájlnév. */
    fun importText(title: String, body: String) {
        val t = title.trim()
        val text = if (t.isEmpty()) body else "$t\n\n$body"
        saveNote(null, text)
    }

    /** Mindent egy ZIP-be ír: notes/*.md és recordings/*.m4a */
    fun exportZip(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            for ((prefix, dir) in listOf("notes" to notesDir, "recordings" to recDir)) {
                dir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("$prefix/${f.name}").apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
