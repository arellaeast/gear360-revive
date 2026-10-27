package com.jacobeast.gear360revive.osc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The Gear 360 hotspot has no internet, and modern Android prefers to route
 * traffic over mobile data / a "better" network. Binding the process to the
 * Wi-Fi network keeps OSC requests on the camera's LAN.
 *
 * This is best-effort: if no Wi-Fi network is found we simply report that and
 * the app falls back to default routing (which works on many devices as long
 * as the phone stays joined to the camera SSID).
 */
object NetworkBinding {

    /** Returns a human-readable result of the bind attempt. */
    fun bindToWifi(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return "No Wi-Fi network found. Join the Gear 360 hotspot first, then bind."
        return if (cm.bindProcessToNetwork(wifi)) {
            "App traffic is now pinned to Wi-Fi (the camera network)."
        } else {
            "Could not pin traffic to Wi-Fi; continuing with default routing."
        }
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
    }
}
