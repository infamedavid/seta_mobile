package com.seta.androidbridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

class NetworkInfoProvider(private val context: Context) {
    fun getLocalIpAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val activeNetwork = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
        val hasSupportedTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!hasSupportedTransport) return null
        val linkProps: LinkProperties = cm.getLinkProperties(activeNetwork) ?: return null
        return linkProps.linkAddresses
            .mapNotNull { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
