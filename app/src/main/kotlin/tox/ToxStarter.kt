// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.tox

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import javax.inject.Inject
import com.stoyko.smessenger.ToxService
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.feature.UserManager
import com.stoyko.smessenger.domain.tox.SaveManager
import com.stoyko.smessenger.domain.tox.SaveOptions
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxAvEventListener
import com.stoyko.smessenger.domain.tox.ToxEventListener
import com.stoyko.smessenger.domain.tox.ToxInitException
import com.stoyko.smessenger.domain.tox.ToxSaveStatus

private const val TAG = "ToxStarter"

// Один замок на процесс: профиль открывают и экран, и сервис, и приёмник загрузки.
private val loadLock = Any()

class ToxStarter @Inject constructor(
    private val fileTransferManager: FileTransferManager,
    private val saveManager: SaveManager,
    private val userManager: UserManager,
    private val listenerCallbacks: EventListenerCallbacks,
    private val tox: Tox,
    private val eventListener: ToxEventListener,
    private val avEventListener: ToxAvEventListener,
    private val context: Context,
    private val settings: Settings,
    private val groupManager: com.stoyko.smessenger.groups.GroupManager,
) {
    fun startTox(save: ByteArray? = null, password: String? = null): ToxSaveStatus = synchronized(loadLock) {
        startToxLocked(save, password)
    }

    private fun startToxLocked(save: ByteArray?, password: String?): ToxSaveStatus {
        listenerCallbacks.setUp(eventListener)
        listenerCallbacks.setUp(avEventListener)
        groupManager.attach()
        val viaVpn = com.stoyko.smessenger.vpn.VpnState.toxRouted && com.stoyko.smessenger.vpn.VpnState.proxyReady
        // «Каналы связи»: Tox поверх Tor (SOCKS5) или UDP для локальной сети без интернета.
        val options = com.stoyko.smessenger.transport.TransportHooks.adjust(context, if (viaVpn) {
            // Встроенный VPN включён: Tox идёт через его локальный SOCKS5 (только TCP).
            SaveOptions(save, false, com.stoyko.smessenger.domain.tox.ProxyType.SOCKS5, "127.0.0.1", com.stoyko.smessenger.vpn.VpnLinks.SOCKS_PORT)
        } else {
            // «Скрывать мой IP»: без прямых UDP-соединений, только через TCP-релеи.
            SaveOptions(save, settings.udpEnabled && !settings.hideIp, settings.proxyType, settings.proxyAddress, settings.proxyPort)
        })
        com.stoyko.smessenger.transport.TransportHooks.install(context, eventListener, tox)
        try {
            tox.kickBootstrap()
            tox.start(options, password, eventListener, avEventListener)
        } catch (e: ToxInitException) {
            Log.e(TAG, "Unable to start Tox: ${e.message}")
            return e.status
        }

        // This can stay alive across core restarts and it doesn't work well when toxcore resets its numbers
        fileTransferManager.reset()
        groupManager.syncFromCore()
        com.stoyko.smessenger.domain.Diagnostics.track("запуск фонового сервиса") { startService() }
        return ToxSaveStatus.Ok
    }

    /**
     * Перезапустить ядро с теми же профилем и паролем (в фоне). Нужен, когда меняется маршрут
     * (встроенный VPN включили/выключили).
     */
    fun restartAsync() {
        if (!tox.started) return
        val password = tox.password
        Thread({
            try {
                stopTox()
                var waited = 0
                while (tox.started && waited < 10_000) {
                    Thread.sleep(100)
                    waited += 100
                }
                tryLoadTox(password)
            } catch (e: Throwable) {
                com.stoyko.smessenger.domain.Diagnostics.record("перезапуск Tox", e, fatal = false)
            }
        }, "tox-restart").start()
    }

    fun stopTox() = context.run {
        stopService(Intent(this, ToxService::class.java))
    }

    /**
     * Открыть сохранённый профиль. Безопасно вызывать из разных потоков одновременно
     * (экран и фоновый сервис): ядро запускается только один раз.
     */
    fun tryLoadTox(password: String?): ToxSaveStatus = synchronized(loadLock) {
        if (tox.started) return@synchronized ToxSaveStatus.Ok
        val save = tryLoadSave() ?: return@synchronized ToxSaveStatus.SaveNotFound
        val status = startTox(save, password)
        if (status == ToxSaveStatus.Ok) {
            userManager.verifyExists(tox.publicKey)
        }
        status
    }

    /**
     * То же, но в фоновом потоке: расшифровка профиля и запуск ядра на слабых телефонах занимают
     * секунды, и в главном потоке это давало «тёмный экран», зависание и «приложение не отвечает».
     * [done] вызывается в главном потоке.
     */
    fun tryLoadToxAsync(password: String?, done: (ToxSaveStatus) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val status = try {
                tryLoadTox(password)
            } catch (e: Throwable) {
                com.stoyko.smessenger.domain.Diagnostics.record("открытие профиля", e, fatal = false)
                ToxSaveStatus.Null
            }
            main.post { done(status) }
        }, "tox-load").start()
    }

    private fun startService() = context.run {
        // Сервис уже работает (например, это он сам открывает профиль) - повторный запуск не нужен.
        if (ToxService.running) return@run
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                startService(Intent(this, ToxService::class.java))
            } else {
                startForegroundService(Intent(this, ToxService::class.java))
            }
        } catch (e: Exception) {
            // Android 12+ запрещает запуск из фона в некоторых случаях - это не повод падать.
            com.stoyko.smessenger.domain.Diagnostics.record("запуск фонового сервиса", e, fatal = false)
        }
    }

    private fun tryLoadSave(): ByteArray? = saveManager.run { list().firstOrNull()?.let { load(PublicKey(it)) } }
}
