package com.supun.sweeper.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.supun.sweeper.BuildConfig

/** Only asks the system what kind of network is active; the download itself stays in net/SignatureDownloader.kt. */
object ScheduledNetwork {
    private var override: Boolean? = null

    /** Debug builds only: pretend to be on (true) or off (false) an unmetered network, so tests never download. */
    @Synchronized
    fun overrideForTests(onUnmetered: Boolean?) {
        check(BuildConfig.DEBUG) { "Only available in debug builds" }
        override = onUnmetered
    }

    /** True on an unmetered Wi-Fi (or Ethernet) network that has internet access. */
    @Synchronized
    fun onUnmeteredNetwork(context: Context): Boolean {
        override?.let { return it }
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return lan &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
