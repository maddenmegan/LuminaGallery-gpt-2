package com.lumina.gallery.data

import android.graphics.Bitmap
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OcrEngine {
    data class Result(val text: String, val source: String, val details: String = "")

    fun extract(bitmap: Bitmap, apiKey: String?): Result {
        val offline = runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.trim()
            recognizer.close()
            text
        }.getOrElse { "" }
        if (offline.isNotBlank()) return Result(offline, "On-device ML Kit (Latin script)")
        if (apiKey.isNullOrBlank()) {
            return Result("", "Unavailable", "Offline English OCR returned no text. Add a Gemini API key to attempt Bangla, Arabic, and other scripts.")
        }
        return extractWithGemini(bitmap, apiKey)
    }

    private fun extractWithGemini(bitmap: Bitmap, apiKey: String): Result {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val models = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
        val errors = mutableListOf<String>()
        for (model in models) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 45_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray()
                        .put(JSONObject().put("text", "Transcribe every visible word exactly as written. Preserve the original script, language, spelling, line breaks, and punctuation. Do not translate, transliterate, or guess. Return only the extracted text."))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", base64))))
                )).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val root = JSONObject(payload)
                    val candidates = root.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val text = buildString {
                        if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }.trim()
                    if (text.isNotBlank()) return Result(text, "Gemini Vision · $model", errors.joinToString("\n"))
                    errors += "$model: response contained no transcription text"
                } else {
                    val message = runCatching { JSONObject(payload).optJSONObject("error")?.optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: payload.take(300).ifBlank { "HTTP $code" }
                    errors += "$model: HTTP $code — $message"
                }
            } catch (error: Exception) {
                errors += "$model: ${error.javaClass.simpleName}: ${error.message ?: "request failed"}"
            } finally {
                connection?.disconnect()
            }
        }
        return Result("", "Gemini Vision unavailable", errors.joinToString("\n"))
    }
}
