// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.platform

import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------------------------------------
// Контракты платформы (expect). Реализации: androidMain / iosMain.
// ---------------------------------------------------------------------------------------------

/** Сжатие deflate без заголовка zlib (как Deflater(level, nowrap = true) на Android). */
expect fun rawDeflate(input: ByteArray): ByteArray

/**
 * Распаковка raw deflate. [maxOut] - защита от «бомбы»: распаковывается не больше
 * (примерно) указанного числа байт.
 */
expect fun rawInflate(input: ByteArray, maxOut: Int): ByteArray

/** Время с 1970 г. в мс (замена System.currentTimeMillis для перенесённого кода). */
expect fun nowMillis(): Long

/** Монотонное время в нс (замена System.nanoTime: замеры и зерно случайных чисел). */
expect fun nowNanos(): Long

/** Текущий месяц 1..12 по локальному календарю устройства. */
expect fun currentMonth(): Int

/** Простое хранилище настроек: SharedPreferences на Android, NSUserDefaults на iOS. */
expect class KeyValueStore(name: String) {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, def: String = ""): String
    fun putString(key: String, value: String)
}

/** Состояние сети. На iOS «constrained» = режим экономии данных, «expensive» = сотовая/хотспот. */
data class NetState(
    val online: Boolean = true,
    val expensive: Boolean = false,
    val constrained: Boolean = false,
)

/** Наблюдение за сетью: ConnectivityManager на Android, NWPathMonitor на iOS. */
expect class NetworkMonitor() {
    val state: StateFlow<NetState>
    fun start()
    fun stop()
}

/**
 * Фоновая работа - на iOS она устроена совсем иначе, чем на Android:
 *  - постоянный сокет (Tox, Tor, VPN-туннель) в фоне iOS держать НЕ даёт - приложение
 *    замораживается через ~30 с после сворачивания;
 *  - можно «доделать» начатое (отправка файла) - [finishInBackground], до ~30 с;
 *  - можно попросить систему разбудить приложение «когда-нибудь» - [scheduleRefresh]
 *    (BGAppRefreshTask; время выбирает iOS, гарантий нет);
 *  - для мгновенных сообщений в фоне нужен APNs (push) через сервер-ретранслятор.
 * На Android приложение использует свои сервисы, поэтому там это тонкая обёртка.
 */
expect object BackgroundWork {
    /** Вызвать при запуске приложения (на iOS - до конца didFinishLaunching). */
    fun register()

    /** Что делать, когда система разбудила приложение в фоне (короткая синхронизация). */
    var onRefresh: (suspend () -> Unit)?

    /** Попросить разбудить приложение не раньше, чем через [minDelaySec] секунд. */
    fun scheduleRefresh(minDelaySec: Long)

    /** Дать начатой работе доделаться после сворачивания (iOS: beginBackgroundTask). */
    fun finishInBackground(name: String, block: suspend () -> Unit)
}
