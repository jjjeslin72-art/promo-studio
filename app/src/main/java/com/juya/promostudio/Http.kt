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
    fun postMultipart(url: String, fields: Map<String, String>, fileField: String, file: File, mime: String,
                      onProgress: ((Int) -> Unit)? = null): JSONObject {
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
            file.inputStream().use { copyWithProgress(it, out, 0, file.length(), onProgress) }
            out.write("\r\n--$b--\r\n".toByteArray())
        }
        return read(c)
    }

    /**
     * 파일 그대로 올리기 (인스타 릴스 업로드용).
     * 보내는 도중 서버가 연결을 끊으면(예: 토큰·권한 오류) 서버가 보낸 진짜 이유를 읽어서 알려 준다.
     */
    fun postBinary(url: String, file: File, headers: Map<String, String>, skip: Long = 0,
                   onProgress: ((Int) -> Unit)? = null): JSONObject {
        val c = open(url, "POST")
        c.readTimeout = 300_000
        c.doOutput = true
        c.setFixedLengthStreamingMode(file.length() - skip)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { out ->
                file.inputStream().use { inp ->
                    if (skip > 0) inp.skip(skip)
                    copyWithProgress(inp, out, skip, file.length(), onProgress)
                }
            }
        } catch (e: java.io.IOException) {
            // 서버가 먼저 거절했으면 그 이유를 보여 준다
            val reason = try {
                c.errorStream?.bufferedReader()?.use { it.readText() }?.let { body ->
                    try { JSONObject(body).optJSONObject("debug_info")?.optString("message")
                        ?: JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { body.take(200) }
                }
            } catch (_: Exception) { null }
            throw java.io.IOException(reason?.takeIf { it.isNotBlank() } ?: (e.message ?: "업로드 중 연결이 끊겼어요"))
        }
        return read(c)
    }

    /** 보내면서 몇 % 갔는지 알려 주기 */
    private fun copyWithProgress(inp: java.io.InputStream, out: java.io.OutputStream, start: Long, total: Long, onProgress: ((Int) -> Unit)?) {
        val buf = ByteArray(256 * 1024)
        var sent = start
        var last = -1
        while (true) {
            val n = inp.read(buf); if (n < 0) break
            out.write(buf, 0, n); sent += n
            val pct = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 100) else 0
            if (pct != last) { last = pct; onProgress?.invoke(pct) }
        }
    }

    /** 잠깐 끊김(와이파이·통신) 때문에 실패하면 몇 번 더 시도 */
    fun <T> retry(times: Int = 3, block: (attempt: Int) -> T): T {
        var last: Exception? = null
        for (i in 1..times) {
            try { return block(i) } catch (e: ApiError) { throw e } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(3_000L * i)
            }
        }
        throw last ?: ApiError("업로드 실패")
    }
}
