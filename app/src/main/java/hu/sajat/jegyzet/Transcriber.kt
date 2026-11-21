package hu.sajat.jegyzet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Átirat a SAJÁT szervereden futó, OpenAI-kompatibilis STT API-val
 * (pl. faster-whisper-server, whisper.cpp server, LocalAI).
 * URL például: http://192.168.1.10:8000/v1/audio/transcriptions
 */
object Transcriber {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    fun transcribe(url: String, key: String, model: String, lang: String, file: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .apply { if (lang.isNotBlank()) addFormDataPart("language", lang) }
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val req = Request.Builder().url(url).post(body)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "Átirat hiba (${resp.code}): ${txt.take(120)}" }
            return JSONObject(txt).getString("text").trim()
        }
    }
}
