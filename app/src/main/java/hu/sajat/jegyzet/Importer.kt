package hu.sajat.jegyzet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject

/**
 * Import fájlokból: .txt / .md (pl. Xiaomi Jegyzetekből exportálva vagy megosztva),
 * és Google Keep Takeout .json fájlok.
 */
object Importer {
    fun import(ctx: Context, store: Store, uris: List<Uri>): Int {
        var n = 0
        for (u in uris) {
            val name = displayName(ctx, u)
            val raw = ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: continue
            if (name.endsWith(".json", ignoreCase = true)) {
                runCatching {
                    val j = JSONObject(raw)
                    val title = j.optString("title")
                    val sb = StringBuilder(j.optString("textContent"))
                    j.optJSONArray("listContent")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            sb.append(if (o.optBoolean("isChecked")) "- [x] " else "- [ ] ").append(o.optString("text")).append('\n')
                        }
                    }
                    store.importText(title.ifBlank { name.removeSuffix(".json") }, sb.toString().trim())
                    n++
                }
            } else {
                val title = name.substringBeforeLast('.')
                store.importText(title, raw.trim())
                n++
            }
        }
        return n
    }

    private fun displayName(ctx: Context, u: Uri): String =
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "importalt"
}
