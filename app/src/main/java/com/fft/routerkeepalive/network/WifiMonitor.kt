package com.fft.routerkeepalive.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fft.routerkeepalive.domain.WifiConnectivity
import com.fft.routerkeepalive.domain.normalizeSsid

/**
 * Wi-Fi monitor, ported 1:1 from the HarmonyOS `WifiMonitor`.
 *
 * `available` comes from the bearer being Wi-Fi (network callback + current network enumeration)
 * and never depends on whether the SSID can be read; `ssid` is best-effort.
 */
class WifiMonitor(
    private val context: Context,
    private val changed: (available: Boolean, ssid: String) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var ssid: String = ""
        private set

    /** Human-readable description of the last Wi-Fi read, shown in the UI as "why are we waiting". */
    @Volatile
    var diagnostic: String = "尚未读取 Wi-Fi 信息"
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network.bearerWifi()) {
                available = true
                readSsid()
            }
        }

        override fun onLost(network: Network) {
            if (network.bearerWifi()) {
                markUnavailable()
            }
        }

        override fun onUnavailable() {
            markUnavailable()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWifi && !available) {
                available = true
                readSsid()
            }
        }
    }

    fun start() {
        if (callback != null) {
            return
        }
        // No NET_CAPABILITY_INTERNET requirement: a captive-portal network is exactly the target
        // scenario of this app, and Android also reports it as "not validated".
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
        } catch (_: Exception) {
            callback = null
        }

        wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.NETWORK_STATE_CHANGED_ACTION,
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> detectCurrent()
                }
            }
        }
        try {
            context.registerReceiver(
                wifiStateReceiver,
                IntentFilter().apply {
                    addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                }
            )
        } catch (_: Exception) {
            wifiStateReceiver = null
        }

        // Callbacks only cover later changes; probe the current state once at startup.
        detectCurrent()
        readSsid()
    }

    fun stop() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        wifiStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        wifiStateReceiver = null
        available = false
        ssid = ""
    }

    private fun Network.bearerWifi(): Boolean {
        val capabilities = try {
            connectivityManager.getNetworkCapabilities(this)
        } catch (_: Exception) {
            null
        }
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Reads the current Wi-Fi name. Failure only affects `ssid` and `diagnostic`, not `available`. */
    fun readSsid() {
        val outcome = readLinkedInfo()
        ssid = outcome.ssid
        diagnostic = outcome.diagnostic
        // A valid SSID from LinkedInfo is itself strong evidence that Wi-Fi is connected, so a
        // slightly earlier `false` must not overwrite it.
        available = WifiConnectivity.reconcile(available, ssid)
        notifyChanged()
    }

    private data class SsidOutcome(val ssid: String, val diagnostic: String)

    /**
     * Returns a structured result instead of throwing: timeouts and permission failures can be
     * described precisely, and a thrown SecurityException never leaks a stack trace into the UI.
     *
     * Two sources are tried because they are authorised by different grants and each can be the
     * only one available:
     * 1. `NetworkCapabilities.getTransportInfo()` — the Android 12+ path, reachable with the
     *    Wi-Fi network permission and not dependent on the location group.
     * 2. `WifiManager.connectionInfo` — the traditional path, kept as a fallback for older
     *    releases.
     */
    private fun readLinkedInfo(): SsidOutcome {
        val fromCapabilities = readSsidFromCapabilities()
        if (fromCapabilities.isNotEmpty()) {
            return SsidOutcome(fromCapabilities, "已读取 Wi-Fi 名称「$fromCapabilities」")
        }
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info: WifiInfo? = wifiManager.connectionInfo
            val normalized = normalizeSsid(info?.ssid.orEmpty())
            Log.i(
                TAG,
                "readSsid: raw='${info?.ssid}' normalized='$normalized' " +
                    "networkId=${info?.networkId} supplicant=${info?.supplicantState}"
            )
            SsidOutcome(
                ssid = normalized,
                diagnostic = if (normalized.isNotEmpty()) {
                    "已读取 Wi-Fi 名称「$normalized」"
                } else {
                    "系统未返回 Wi-Fi 名称（Android 可能对第三方应用隐藏 SSID）"
                }
            )
        } catch (error: Exception) {
            Log.w(TAG, "readSsid failed", error)
            SsidOutcome("", "读取 Wi-Fi 名称失败：${error.javaClass.simpleName} ${error.message}")
        }
    }

    /** Android 12+ path: the WifiInfo carried by the active network's capabilities. */
    private fun readSsidFromCapabilities(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        return try {
            val network = connectivityManager.activeNetwork ?: return ""
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ""
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ""
            val info = capabilities.transportInfo as? WifiInfo ?: return ""
            normalizeSsid(info.ssid.orEmpty()).also {
                Log.i(TAG, "readSsid(capabilities): raw='${info.ssid}' normalized='$it'")
            }
        } catch (error: Exception) {
            Log.w(TAG, "readSsid(capabilities) failed", error)
            ""
        }
    }
    private fun detectCurrent() {
        try {
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val wifiFound = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            available = wifiFound
            if (!wifiFound) {
                diagnostic = "当前没有已连接的 Wi-Fi 网络"
            }
        } catch (error: Exception) {
            available = false
            diagnostic = "枚举当前网络失败：${error.javaClass.simpleName} ${error.message}"
        }
    }

    private fun markUnavailable() {
        // The disappearing network may be another Wi-Fi; re-enumerate before declaring none.
        detectCurrent()
        if (!available) {
            ssid = ""
            diagnostic = "当前没有可用的 Wi-Fi 网络"
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        mainHandler.post { changed(available, if (available) ssid else "") }
    }

    private companion object {
        const val TAG = "RouterKeepAlive"
    }
}
