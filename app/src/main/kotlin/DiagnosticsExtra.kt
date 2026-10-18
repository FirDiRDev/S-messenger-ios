// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.stoyko.smessenger.domain.Diagnostics

/**
 * Подробный журнал ошибок:
 *  - к каждой ошибке - состояние приложения: версия, телефон, Android, память, сеть, VPN,
 *    подключение к сети, экран;
 *  - «события» - последние действия (экраны, смена сети, звонки, VPN) перед ошибкой;
 *  - зависания главного потока (экран не отвечает > 5 с) - с тем, где именно он застрял;
 *  - отчёты системы о прошлых завершениях (Android 11+): вылеты, «приложение не отвечает»
 *    с трассировкой, нехватка памяти, сбои нативной библиотеки.
 */
object DiagnosticsExtra {
    @Volatile
    var screen: String = ""

    @Volatile
    var toxStatus: String = "-"

    fun install(app: App) {
        Diagnostics.context = { stateLine(app) }
        Diagnostics.crumb("запуск приложения ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        startHangWatchdog()
        Thread({ runCatching { collectExitReasons(app) } }, "diag-exits").apply { isDaemon = true }.start()
    }

    private fun stateLine(ctx: Context): String = buildString {
        append("v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
        append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
        runCatching {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            append(" · память ").append(used).append("/").append(rt.maxMemory() / 1_048_576).append(" МБ")
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            append(", свободно в системе ").append(mi.availMem / 1_048_576).append(" МБ")
            if (mi.lowMemory) append(" (мало памяти!)")
        }
        append(" · сеть ").append(networkType(ctx))
        append(" · VPN ").append(runCatching { com.stoyko.smessenger.vpn.VpnState.status.name }.getOrDefault("-"))
        append(" · Tox ").append(toxStatus)
        if (screen.isNotBlank()) append(" · экран ").append(screen)
    }

    fun networkType(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT < 23) {
            @Suppress("DEPRECATION")
            return@runCatching cm.activeNetworkInfo?.typeName ?: "нет"
        }
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "нет"
        val parts = ArrayList<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "моб."
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "Ethernet"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts += "без интернета?"
        parts.joinToString("+").ifEmpty { "?" }
    }.getOrDefault("?")

    // ---------- зависания главного потока ----------

    @Volatile
    private var lastBeat = SystemClock.uptimeMillis()

    @Volatile
    private var reportedHang = false

    private fun startHangWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val beat = object : Runnable {
            override fun run() {
                lastBeat = SystemClock.uptimeMillis()
                main.postDelayed(this, 1000)
            }
        }
        main.post(beat)
        Thread({
            while (true) {
                val before = SystemClock.uptimeMillis()
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // Процесс был «заморожен» системой (приложение в фоне) - это не зависание.
                if (SystemClock.uptimeMillis() - before > 3500) {
                    lastBeat = SystemClock.uptimeMillis()
                    continue
                }
                val stalled = SystemClock.uptimeMillis() - lastBeat
                if (stalled > 5000 && !reportedHang) {
                    reportedHang = true
                    val st = Looper.getMainLooper().thread.stackTrace
                    Diagnostics.warn(
                        "экран не отвечает ${stalled / 1000} с (главный поток занят)",
                        "Где застрял главный поток:\n" + st.take(25).joinToString("\n") { "    at $it" },
                    )
                } else if (stalled < 1500) {
                    reportedHang = false
                }
            }
        }, "diag-hang").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    // ---------- отчёты системы о прошлых завершениях ----------

    private fun collectExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = ctx.getSharedPreferences("diag_exits", Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).filter { it.timestamp > seen }
        if (list.isEmpty()) return
        prefs.edit().putLong("seen", list.maxOf { it.timestamp }).apply()
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        for (e in list.sortedBy { it.timestamp }) {
            val reason = reasonName(e.reason)
            // Штатные завершения (смахнули, обновили, выгрузили) - одной строкой в событиях.
            val important = e.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            )
            if (!important) continue
            val text = buildString {
                append("=== ").append(fmt.format(Date(e.timestamp))).append(" отчёт системы о завершении: ").append(reason).append('\n')
                e.description?.takeIf { it.isNotBlank() }?.let { append("Описание: ").append(it).append('\n') }
                append("Процесс: ").append(e.processName).append(", важность ").append(e.importance)
                append(", память PSS ").append(e.pss / 1024).append(" МБ, RSS ").append(e.rss / 1024).append(" МБ\n")
                // «Приложение не отвечает» - трассировка потоков от системы (что именно зависло).
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        e.traceInputStream?.bufferedReader()?.use { r ->
                            val trace = r.readText()
                            val mainPart = trace.substringAfter("\"main\"", trace).take(6000)
                            append("Трассировка (главный поток):\n\"main\"").append(mainPart).append('\n')
                        }
                    }
                }
            }
            Diagnostics.appendRaw(text)
        }
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "вылет (ошибка Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "вылет нативной библиотеки"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "система закрыла: мало памяти"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "система закрыла: слишком много ресурсов"
        ApplicationExitInfo.REASON_SIGNALED -> "закрыто сигналом"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "закрыто пользователем"
        ApplicationExitInfo.REASON_EXIT_SELF -> "закрылось само"
        else -> "код $r"
    }
}
