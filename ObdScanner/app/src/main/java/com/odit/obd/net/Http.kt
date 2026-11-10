package com.odit.obd.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Result of a GET: [code] is -1 when there was no connection at all. */
class HttpResult(val code: Int, val body: ByteArray?) {
    val ok: Boolean get() = code in 200..299 && body != null
    fun text(): String? = body?.toString(Charsets.UTF_8)
}

/**
 * Tiny HTTP client on top of the platform's HttpURLConnection (no extra library in the APK).
 * Short timeouts, runs on the IO dispatcher, never throws: failures come back as a result.
 * Requests carry no identifiers — only a generic User-Agent.
 */
class HttpClient(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 6_000,
    private val userAgent: String = "OditOBD/1.1 (Android)",
) {
    suspend fun get(url: String, maxBytes: Int = 4_000_000): HttpResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return@use null
                }
                out.toByteArray()
            }
            HttpResult(code, body)
        } catch (_: Exception) {
            HttpResult(-1, null)
        } finally {
            conn?.disconnect()
        }
    }
}

object Network {
    /** True when the phone has a validated internet connection (Wi-Fi or mobile data). */
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
