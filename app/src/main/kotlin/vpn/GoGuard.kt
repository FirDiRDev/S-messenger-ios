// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * В приложении два ядра, написанных на Go: Xray (libgojni - серверы, обход DPI, фрагменты, проверка
 * пинга) и AmneziaWG (libwg-go - WARP). У каждого свой рантайм Go, и два рантайма в одном процессе
 * несовместимы: вторая загруженная библиотека роняет приложение (SIGSEGV в runtime.mapassign -
 * именно так падало при переключении на WARP и обратно, см. журнал 3.6.0).
 *
 * Поэтому в процессе может работать только одно ядро. Если нужно другое - запуск запоминается,
 * приложение за секунду перезапускается и включает нужный режим уже в чистом процессе.
 */
object GoGuard {
    const val XRAY = "xray"
    const val WG = "wg"
    private const val TAG = "GoGuard"
    private const val PREFS = "go_guard"

    @Volatile
    private var loaded: String? = null

    /** Можно ли загрузить ядро [kind] в этом процессе (и отметить его загруженным). */
    @Synchronized
    fun claim(kind: String): Boolean {
        val cur = loaded
        if (cur == null) {
            loaded = kind
            return true
        }
        return cur == kind
    }

    /** Нужное ядро несовместимо с уже загруженным. */
    fun conflict(kind: String): Boolean = loaded.let { it != null && it != kind }

    /**
     * Запомнить «включить VPN после перезапуска» и перезапустить приложение. Срабатывает, когда
     * человек переключил режим (WARP ↔ остальные) - приложение на виду, перезапуск занимает секунду.
     */
    fun restartAndStart(ctx: Context) {
        val app = ctx.applicationContext
        Log.i(TAG, "Engine switch $loaded -> other: restarting process")
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending_start", true).commit()
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch?.component != null) {
                runCatching { app.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Runtime.getRuntime().exit(0)
        }, 700)
    }

    /** Человек выключил VPN - не включать его после перезапуска для смены ядра. */
    fun cancelPending(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending_start", false).apply()
    }

    /** После перезапуска: включить VPN, если он ждал смены ядра. */
    fun resumePending(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("pending_start", false)) return
        p.edit().putBoolean("pending_start", false).apply()
        Handler(Looper.getMainLooper()).postDelayed({
            // Пока ждали, включили сторонний VPN - не отбираем у него туннель.
            if (!ForeignVpn.active(ctx) && SmVpnService.prepareIntent(ctx) == null) SmVpnService.start(ctx)
        }, 1200)
    }
}
