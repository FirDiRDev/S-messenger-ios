// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport

import android.content.Context
import android.content.SharedPreferences

/** Режим, выбранный в «Каналы связи». */
enum class TransportMode { AUTO, TOX, TOR, OFFLINE }

/** Чем сейчас реально идёт связь (значок в шапке). */
enum class Channel(val emoji: String) {
    TOX("🌐"),
    TOR("🧅"),
    WIFI("📶"),
    BLUETOOTH("🔵"),
    NONE("⚪"),
}

/** Мосты Tor (обход блокировок). AUTO - как у Briar: по стране (в России - мосты включены). */
enum class BridgeMode { AUTO, SNOWFLAKE, MEEK, OBFS4, CUSTOM, NONE }

/** Настройки каналов связи (отдельный файл настроек - не пересекается с остальными). */
object TransportPrefs {
    private const val FILE = "transport"

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(ctx: Context): TransportMode =
        runCatching { TransportMode.valueOf(sp(ctx).getString("mode", null) ?: "AUTO") }.getOrDefault(TransportMode.AUTO)

    fun setMode(ctx: Context, m: TransportMode) = sp(ctx).edit().putString("mode", m.name).apply()

    /** Через сколько секунд без связи Tox «Авто» переходит на Tor. */
    fun autoTorAfterSec(ctx: Context): Int = sp(ctx).getInt("auto_tor_after", 45).coerceIn(15, 600)

    fun setAutoTorAfterSec(ctx: Context, v: Int) = sp(ctx).edit().putInt("auto_tor_after", v).apply()

    /** «Авто» может включать Tor сам (можно запретить - тогда только прямой Tox и локальные каналы). */
    fun autoTorAllowed(ctx: Context): Boolean = sp(ctx).getBoolean("auto_tor", true)

    fun setAutoTorAllowed(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_tor", v).apply()

    fun bridges(ctx: Context): BridgeMode =
        runCatching { BridgeMode.valueOf(sp(ctx).getString("bridges", null) ?: "AUTO") }.getOrDefault(BridgeMode.AUTO)

    fun setBridges(ctx: Context, m: BridgeMode) = sp(ctx).edit().putString("bridges", m.name).apply()

    /** Свои мосты: по одному на строку («obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0»). */
    fun customBridges(ctx: Context): List<String> =
        (sp(ctx).getString("custom_bridges", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun customBridgesRaw(ctx: Context): String = sp(ctx).getString("custom_bridges", "") ?: ""

    fun setCustomBridges(ctx: Context, raw: String) = sp(ctx).edit().putString("custom_bridges", raw.take(20_000)).apply()

    /** Bluetooth-канал для сообщений без интернета (включает пользователь - нужны разрешения). */
    fun bluetooth(ctx: Context): Boolean = sp(ctx).getBoolean("bt", false)

    fun setBluetooth(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bt", v).apply()
}
