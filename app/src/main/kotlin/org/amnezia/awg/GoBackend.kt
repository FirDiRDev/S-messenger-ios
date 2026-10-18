// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package org.amnezia.awg

/**
 * Мост к libwg-go.so (AmneziaWG - WireGuard с маскировкой от DPI). Имена методов и пакет
 * должны совпадать с теми, под которые собрана библиотека.
 */
object GoBackend {
    @Volatile
    private var loaded: Boolean? = null

    /** Библиотека есть в сборке и загрузилась. */
    fun load(): Boolean {
        loaded?.let { return it }
        // Xray (другой рантайм Go) уже загружен - вместе они роняют процесс.
        if (!com.stoyko.smessenger.vpn.GoGuard.claim(com.stoyko.smessenger.vpn.GoGuard.WG)) return false
        val ok = runCatching { System.loadLibrary("wg-go") }.isSuccess
        loaded = ok
        return ok
    }

    @JvmStatic
    external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int

    @JvmStatic
    external fun awgTurnOff(handle: Int)

    @JvmStatic
    external fun awgGetSocketV4(handle: Int): Int

    @JvmStatic
    external fun awgGetSocketV6(handle: Int): Int

    @JvmStatic
    external fun awgGetConfig(handle: Int): String?

    @JvmStatic
    external fun awgVersion(): String
}
