package com.juya.promostudio

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiError(msg: String) : Exception(msg)

/** 아주 작은 HTTP 도우미 (추가 라이브러리 없이) */
object Http {

    private fun enc(m: Map<String, String>) =
        m.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 180_000
        }

    private fun read(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val json = try { JSONObject(body) } catch (e: Exception) { JSONObject().put("raw", body) }
        if (code !in 200..299 || json.has("error")) {
            val err = json.optJSONObject("error")
            val msg = err?.optString("error_user_msg")?.takeIf { it.isNotEmpty() }
                ?: err?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: json.optString("raw").take(200).ifEmpty { "HTTP $code" }
            throw ApiError(msg)
        }
        return json
    }

    fun get(url: String, params: Map<String, String> = emptyMap()): JSONObject {
        val full = if (params.isEmpty()) url else url + (if (url.contains("?")) "&" else "?") + enc(params)
        return read(open(full, "GET"))
    }

    fun postForm(url: String, params: Map<String, String>): JSONObject {
        val c = open(url, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        c.outputStream.use { it.write(enc(params).toByteArray()) }
        return read(c)
    }

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val c = open(url, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c)
    }

    /** 파일 + 글자 필드를 multipart로 올리기 */
    fun postMultipart(url: String, fields: Map<String, String>, fileField: String, file: File, mime: String): JSONObject {
        val b = "----promo" + System.currentTimeMillis()
        val c = open(url, "POST")
        c.doOutput = true
        c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
        DataOutputStream(c.outputStream).use { out ->
            fields.forEach { (k, v) ->
                out.write("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                out.write(v.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }
            out.write("--$b\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out, 256 * 1024) }
            out.write("\r\n--$b--\r\n".toByteArray())
        }
        return read(c)
    }

    /** 파일 그대로 올리기 (인스타 릴스 업로드용) */
    fun postBinary(url: String, file: File, headers: Map<String, String>): JSONObject {
        val c = open(url, "POST")
        c.doOutput = true
        c.setFixedLengthStreamingMode(file.length())
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
        return read(c)
    }
}
