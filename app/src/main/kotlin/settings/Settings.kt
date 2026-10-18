// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import javax.inject.Inject
import com.stoyko.smessenger.BootReceiver
import com.stoyko.smessenger.domain.tox.ProxyType

enum class FtAutoAccept {
    None,
    Images,
    All,
}

enum class BootstrapNodeSource {
    BuiltIn,
    UserProvided,
}

class Settings @Inject constructor(private val ctx: Context) {
    /** Контекст приложения (для режимов оформления). */
    fun appContext(): Context = ctx.applicationContext

    private val preferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    var theme: Int
        // По умолчанию - тёмная тема (светлую можно выбрать в Настройки → Оформление → Тема).
        get() = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_YES)
        set(theme) {
            preferences.edit { putInt("theme", theme) }
            AppCompatDelegate.setDefaultNightMode(theme)
        }

    var udpEnabled: Boolean
        get() = preferences.getBoolean("udp_enabled", true)
        set(enabled) = preferences.edit { putBoolean("udp_enabled", enabled) }

    var runAtStartup: Boolean
        get() = ctx.packageManager.getComponentEnabledSetting(
            ComponentName(ctx, BootReceiver::class.java),
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        set(runAtStartup) {
            val state = if (runAtStartup) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, BootReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }

    var autoAwayEnabled: Boolean
        get() = preferences.getBoolean("auto_away_enabled", false)
        set(enabled) = preferences.edit { putBoolean("auto_away_enabled", enabled) }

    var autoAwaySeconds: Long
        get() = preferences.getLong("auto_away_seconds", 180)
        set(seconds) = preferences.edit { putLong("auto_away_seconds", seconds) }

    var proxyType: ProxyType
        get() = ProxyType.entries[preferences.getInt("proxy_type", 0)]
        set(type) = preferences.edit { putInt("proxy_type", type.ordinal) }

    var proxyAddress: String
        get() = preferences.getString("proxy_address", null) ?: ""
        set(address) = preferences.edit { putString("proxy_address", address) }

    var proxyPort: Int
        get() = preferences.getInt("proxy_port", 0)
        set(port) = preferences.edit { putInt("proxy_port", port) }

    var ftAutoAccept: FtAutoAccept
        get() = FtAutoAccept.entries[preferences.getInt("ft_auto_accept", FtAutoAccept.All.ordinal)]
        set(autoAccept) = preferences.edit { putInt("ft_auto_accept", autoAccept.ordinal) }

    var bootstrapNodeSource: BootstrapNodeSource
        get() = BootstrapNodeSource.entries[preferences.getInt("bootstrap_node_source", 0)]
        set(source) = preferences.edit { putInt("bootstrap_node_source", source.ordinal) }

    var disableScreenshots: Boolean
        get() = preferences.getBoolean("disable_screenshots", false)
        set(disable) = preferences.edit { putBoolean("disable_screenshots", disable) }

    var confirmQuitting: Boolean
        get() = preferences.getBoolean("confirm_quitting", true)
        set(confirm) = preferences.edit { putBoolean("confirm_quitting", confirm) }

    var confirmCalling: Boolean
        get() = preferences.getBoolean("confirm_calling", false)
        set(confirm) = preferences.edit { putBoolean("confirm_calling", confirm) }

    /** Не давать телефону «усыплять» связь при выключенном экране (надёжнее, но расходует больше заряда). */
    var stableConnection: Boolean
        get() = batteryMode != 2
        set(on) = preferences.edit {
            putBoolean("stable_connection", on)
            putInt("battery_mode", if (on) 1 else 2)
        }

    /**
     * Расход батареи в фоне: 0 - максимальная надёжность (процессор и Wi-Fi не засыпают),
     * 1 - сбалансированный (процессор не засыпает, Wi-Fi в обычном режиме), 2 - экономия
     * (телефон может усыплять связь - сообщения придут с задержкой).
     */
    var batteryMode: Int
        get() = preferences.getInt("battery_mode", if (preferences.getBoolean("stable_connection", true)) 1 else 2)
        set(v) = preferences.edit {
            putInt("battery_mode", v)
            putBoolean("stable_connection", v != 2)
        }

    /** Уже спрашивали про отключение экономии заряда. */
    var batteryPromptShown: Boolean
        get() = preferences.getBoolean("battery_prompt_shown", false)
        set(v) = preferences.edit { putBoolean("battery_prompt_shown", v) }

    // ---------- Конфиденциальность ----------

    /** Хэш PIN-кода блокировки (пусто - блокировка выключена). */
    var appLockPinHash: String
        get() = preferences.getString("app_lock_pin", "") ?: ""
        set(v) = preferences.edit { putString("app_lock_pin", v) }

    val appLockEnabled: Boolean get() = appLockPinHash.isNotEmpty()

    /** Через сколько секунд в фоне требовать PIN (0 - сразу). */
    var appLockTimeoutSeconds: Int
        get() = preferences.getInt("app_lock_timeout", 60)
        set(v) = preferences.edit { putInt("app_lock_timeout", v) }

    /** Не показывать имя и текст в уведомлениях. */
    var hideNotificationText: Boolean
        get() = preferences.getBoolean("hide_notification_text", false)
        set(v) = preferences.edit { putBoolean("hide_notification_text", v) }

    /** Показывать собеседнику «печатает…». */
    var sendTyping: Boolean
        get() = preferences.getBoolean("send_typing", true)
        set(v) = preferences.edit { putBoolean("send_typing", v) }

    /** Клавиатура не запоминает то, что вы пишете (режим инкогнито). */
    var incognitoKeyboard: Boolean
        get() = preferences.getBoolean("incognito_keyboard", false)
        set(v) = preferences.edit { putBoolean("incognito_keyboard", v) }

    // ---------- Оформление ----------

    /** «Стекло» (размытие под панелями, как на iPhone). На слабых телефонах по умолчанию выключено. */
    var glassEffect: Boolean
        get() = preferences.getBoolean("glass_effect", !isLowEnd())
        set(v) = preferences.edit { putBoolean("glass_effect", v) }

    private fun isLowEnd(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return am.isLowRamDevice || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O
    }

    // ---------- Звонки ----------

    /**
     * Звонок начинается через разговорный динамик (у уха), а не громкую связь.
     * Новый ключ (3.3.1): у всех, у кого в старых версиях осталась громкая связь, звонки
     * снова начинаются у уха - как в обычных мессенджерах.
     */
    var callEarpiece: Boolean
        get() = preferences.getBoolean("call_earpiece_v2", true)
        set(v) = preferences.edit { putBoolean("call_earpiece_v2", v) }

    // ---------- Связь и защита ----------

    /** Скрывать свой IP: только через TCP-релеи (без прямых UDP-соединений). */
    var hideIp: Boolean
        get() = preferences.getBoolean("hide_ip", false)
        set(v) = preferences.edit { putBoolean("hide_ip", v) }

    /** Отправлять собеседнику отметку «прочитано». */
    var sendReadReceipts: Boolean
        get() = preferences.getBoolean("send_read_receipts", true)
        set(v) = preferences.edit { putBoolean("send_read_receipts", v) }

    // ---------- Уведомления ----------

    var notifyPrivate: Boolean
        get() = preferences.getBoolean("notify_private", true)
        set(v) = preferences.edit { putBoolean("notify_private", v) }

    var notifyGroups: Boolean
        get() = preferences.getBoolean("notify_groups", true)
        set(v) = preferences.edit { putBoolean("notify_groups", v) }

    var notifyChannels: Boolean
        get() = preferences.getBoolean("notify_channels", true)
        set(v) = preferences.edit { putBoolean("notify_channels", v) }

    /** Звук и вибрация уведомлений о сообщениях (выключено - приходят беззвучно). */
    var notifySound: Boolean
        get() = preferences.getBoolean("notify_sound", true)
        set(v) = preferences.edit { putBoolean("notify_sound", v) }

    // ---------- Оформление и чаты ----------

    /** Размер текста сообщений, sp. */
    var messageTextSize: Int
        get() = preferences.getInt("message_text_size", 16)
        set(v) = preferences.edit { putInt("message_text_size", v.coerceIn(12, 26)) }

    /** Отправка сообщения клавишей Enter (перенос строки - Shift+Enter на внешней клавиатуре). */
    var sendByEnter: Boolean
        get() = preferences.getBoolean("send_by_enter", false)
        set(v) = preferences.edit { putBoolean("send_by_enter", v) }

    /** Анимации интерфейса: «жидкое стекло», переходы окон. */
    var animations: Boolean
        get() = preferences.getBoolean("animations", true)
        set(v) = preferences.edit { putBoolean("animations", v) }

    /** Качество видео в звонках: 0 - авто, 1 - экономия трафика, 2 - высокое. */
    var callVideoQuality: Int
        get() = preferences.getInt("call_video_quality", 0)
        set(v) = preferences.edit { putInt("call_video_quality", v) }
}
