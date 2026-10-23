package com.autoscribe.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit

object Vpn {
    fun isUp(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
}

data class PingResult(val avg: Double?, val loss: Double)

/**
 * Uses the system ping binary, which needs no root. While Windscribe is connected
 * all traffic (ours included) goes through the tunnel, so this measures the tunnel.
 */
object Pinger {
    private val LOSS = Regex("""([\d.]+)% packet loss""")
    private val RTT = Regex("""= ([\d.]+)/([\d.]+)/([\d.]+)""")

    fun run(host: String, count: Int): PingResult? {
        val process = try {
            ProcessBuilder("ping", "-c", count.toString(), "-i", "0.2", "-W", "2", host)
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(count * 3L + 5, TimeUnit.SECONDS)) process.destroy()
        val loss = LOSS.find(output)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val avg = RTT.find(output)?.groupValues?.get(2)?.toDoubleOrNull()
        return PingResult(avg, loss)
    }
}
