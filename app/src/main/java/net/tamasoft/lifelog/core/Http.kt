package net.tamasoft.lifelog.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** What the receiver answered. `code` 0 means it could not be reached at all. */
data class Reply(val code: Int, val body: String) {
    val ok get() = code in 200..299
    /** The receiver will not take this as it is: sending it again will not help. */
    val refused get() = code == 400 || code == 413 || code == 422
    /** Wrong or missing token, or a route not configured: nothing goes until that is fixed. */
    val blocked get() = code == 401 || code == 403 || code == 404 || code == 503
}

object Http {
    fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap(),
             timeoutMs: Int = 120_000): Reply = send(url, "POST", contentType, headers, timeoutMs) { out ->
        out.write(body)
    }

    fun postStream(url: String, input: InputStream, contentType: String, headers: Map<String, String>,
                   timeoutMs: Int = 300_000): Reply = send(url, "POST", contentType, headers, timeoutMs) { out ->
        input.copyTo(out, 64 * 1024)
    }

    fun get(url: String, timeoutMs: Int = 30_000): Reply = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        read(c)
    } catch (e: Exception) {
        Reply(0, e.message ?: e.javaClass.simpleName)
    }

    private fun send(url: String, method: String, contentType: String, headers: Map<String, String>,
                     timeoutMs: Int, write: (java.io.OutputStream) -> Unit): Reply = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = timeoutMs
        c.setChunkedStreamingMode(64 * 1024)
        c.setRequestProperty("Content-Type", contentType)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use(write)
        read(c)
    } catch (e: Exception) {
        Reply(0, e.message ?: e.javaClass.simpleName)
    }

    private fun read(c: HttpURLConnection): Reply {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Reply(code, text.take(2000))
    }
}
