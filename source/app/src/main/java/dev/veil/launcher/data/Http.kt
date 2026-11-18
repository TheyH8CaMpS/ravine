package dev.veil.launcher.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP helper. No third-party networking or analytics libraries. */
internal object Http {
    fun call(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Ravine/0.1")
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

internal fun JSONObject.str(k: String): String? =
    if (has(k) && !isNull(k)) getString(k).takeIf { it.isNotBlank() } else null

internal fun JSONObject.int(k: String): Int? =
    if (has(k) && !isNull(k)) optInt(k) else null

internal fun JSONObject.obj(k: String): JSONObject? =
    if (has(k) && !isNull(k)) optJSONObject(k) else null
