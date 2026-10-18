// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Контекст приложения для actual-реализаций. Вызвать один раз из Application.onCreate():
 *   SharedAndroid.init(this)
 */
@SuppressLint("StaticFieldLeak")
object SharedAndroid {
    @Volatile private var ctx: Context? = null
    val context: Context get() = ctx ?: error("SharedAndroid.init(context) не вызван")
    fun init(context: Context) { ctx = context.applicationContext }
}

actual fun rawDeflate(input: ByteArray): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION, true)
    d.setInput(input)
    d.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

actual fun rawInflate(input: ByteArray, maxOut: Int): ByteArray {
    val inf = Inflater(true)
    inf.setInput(input)
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
        while (!inf.finished() && out.size() < maxOut) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
    } finally {
        inf.end()
    }
    return out.toByteArray()
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun nowNanos(): Long = System.nanoTime()

actual fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1

actual class KeyValueStore actual constructor(name: String) {
    private val sp = SharedAndroid.context.getSharedPreferences(name, Context.MODE_PRIVATE)
    actual fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
    actual fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    actual fun getInt(key: String, def: Int) = sp.getInt(key, def)
    actual fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    actual fun getString(key: String, def: String): String = sp.getString(key, def) ?: def
    actual fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
}

actual class NetworkMonitor actual constructor() {
    private val flow = MutableStateFlow(NetState())
    actual val state: StateFlow<NetState> get() = flow
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val cm get() = SharedAndroid.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    actual fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                flow.value = NetState(
                    online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    constrained = false,
                )
            }
            override fun onLost(network: Network) { flow.value = NetState(online = false) }
        }
        // registerNetworkCallback(NetworkRequest) есть с API 21 (minSdk проекта).
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        callback = cb
    }

    actual fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}

/** На Android фоновую работу ведут сервисы приложения (Tox/Tor/VPN) - здесь только мост. */
actual object BackgroundWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    actual var onRefresh: (suspend () -> Unit)? = null
    actual fun register() = Unit
    actual fun scheduleRefresh(minDelaySec: Long) = Unit
    actual fun finishInBackground(name: String, block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
