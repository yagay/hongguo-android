package com.hongguo.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Safe snapshot of the active app network when registration fails.
 * It does NOT capture SSID, host names, IP addresses, gateway, DNS servers,
 * VPN package, HTTP proxy host/port, or account/device identifiers.
 *
 * These signals do not reveal root iptables, UID firewall policy or
 * transparent proxy rules: they must not be used as proof of connectivity.
 */
data class PlaybackNetworkSnapshot(
    val transport: String,
    val vpn: Boolean?,
    val systemProxy: Boolean?,
    val privateDns: Boolean?,
    val validated: Boolean?,
) {
    fun appendSafeReport(builder: StringBuilder) {
        if (transport in setOf("wifi", "cellular", "ethernet", "other", "none", "unknown")) {
            builder.append("network_transport=").append(transport).append('\n')
        }
        vpn?.let { builder.append("vpn_active=").append(if (it) "yes" else "no").append('\n') }
        systemProxy?.let { builder.append("system_proxy=").append(if (it) "yes" else "no").append('\n') }
        privateDns?.let { builder.append("private_dns=").append(if (it) "yes" else "no").append('\n') }
        validated?.let { builder.append("network_validated=").append(if (it) "yes" else "no").append('\n') }
    }

    companion object {
        fun capture(context: Context): PlaybackNetworkSnapshot {
            return runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return PlaybackNetworkSnapshot("unknown", null, null, null, null)
                val network = manager.activeNetwork
                    ?: return PlaybackNetworkSnapshot("none", null, manager.defaultProxy != null, null, false)
                val capabilities = manager.getNetworkCapabilities(network)
                val transport = when {
                    capabilities == null -> "unknown"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.getLinkProperties(network)?.isPrivateDnsActive
                } else null
                PlaybackNetworkSnapshot(transport, vpn, manager.defaultProxy != null, privateDns, validated)
            }.getOrDefault(PlaybackNetworkSnapshot("unknown", null, null, null, null))
        }
    }
}
