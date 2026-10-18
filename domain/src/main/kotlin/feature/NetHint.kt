// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Какая сейчас сеть. На Wi-Fi (и проводной сети) звонок идёт с меньшим запасом буфера
 * (меньше задержка) и лучшим качеством звука; на мобильной - с запасом от рывков сети.
 */
object NetHint {
    /** Wi-Fi или Ethernet (в т. ч. под VPN поверх Wi-Fi). */
    @Volatile
    var wifi = false
        private set

    fun refresh(ctx: Context) {
        wifi = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT < 23) {
                @Suppress("DEPRECATION")
                val t = cm.activeNetworkInfo?.type
                @Suppress("DEPRECATION")
                return@runCatching t == ConnectivityManager.TYPE_WIFI || t == ConnectivityManager.TYPE_ETHERNET
            }
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return@runCatching true
            }
            // Через VPN: Android ведёт его по Wi-Fi, если Wi-Fi подключён и с интернетом.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                @Suppress("DEPRECATION")
                return@runCatching cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.any {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            false
        }.getOrDefault(false)
    }
}
