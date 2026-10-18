package com.fft.routerkeepalive.network

import com.fft.routerkeepalive.domain.ProbeCategory
import com.fft.routerkeepalive.domain.ProbeResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * HTTPS prober, ported 1:1 from the HarmonyOS `ProbeClient`.
 *
 * The same category mapping is used: 2xx → SUCCESS, 3xx → REDIRECT, other codes → HTTP, and the
 * error categories TIMEOUT / DNS / TLS / UNKNOWN for thrown exceptions.
 *
 * Unlike the HarmonyOS `http` API, `HttpURLConnection` lets us pin the request to the Wi-Fi network
 * and disable redirect following, so a captive portal that 302s to a login page is never counted as
 * a success.
 */
class ProbeClient {

    fun probe(url: String, timeoutSeconds: Int, network: android.net.Network? = null): ProbeResult {
        val started: Double = System.currentTimeMillis().toDouble()
        var connection: HttpURLConnection? = null
        return try {
            val target = URL(url)
            connection = if (network != null) {
                network.openConnection(target) as HttpURLConnection
            } else {
                target.openConnection() as HttpURLConnection
            }
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            val code = connection.responseCode
            val duration = System.currentTimeMillis() - started
            when {
                code in 200..299 -> ProbeResult(
                    success = true,
                    category = ProbeCategory.SUCCESS,
                    durationMs = duration,
                    statusCode = code
                )
                code in 300..399 -> ProbeResult(
                    success = false,
                    category = ProbeCategory.REDIRECT,
                    durationMs = duration,
                    statusCode = code
                )
                else -> ProbeResult(
                    success = false,
                    category = ProbeCategory.HTTP,
                    durationMs = duration,
                    statusCode = code
                )
            }
        } catch (error: Exception) {
            val duration = System.currentTimeMillis() - started
            val category = classify(error)
            ProbeResult(success = false, category = category, durationMs = duration)
        } finally {
            connection?.disconnect()
        }
    }

    private fun classify(error: Exception): String = when (error) {
        is SocketTimeoutException -> ProbeCategory.TIMEOUT
        is UnknownHostException -> ProbeCategory.DNS
        is SSLException -> ProbeCategory.TLS
        is IOException -> {
            val message = (error.message ?: "").lowercase()
            when {
                message.contains("timeout") || message.contains("timed out") -> ProbeCategory.TIMEOUT
                message.contains("dns") || message.contains("resolve") ||
                    message.contains("unable to resolve host") -> ProbeCategory.DNS
                message.contains("ssl") || message.contains("tls") ||
                    message.contains("certificate") -> ProbeCategory.TLS
                message.contains("failed to connect") || message.contains("network is unreachable") ||
                    message.contains("no route to host") -> ProbeCategory.NETWORK_UNAVAILABLE
                else -> ProbeCategory.UNKNOWN
            }
        }
        else -> ProbeCategory.UNKNOWN
    }
}
