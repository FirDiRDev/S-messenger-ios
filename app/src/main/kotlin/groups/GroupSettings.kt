// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Настройки группы/канала, которые задают администраторы и которые видят все участники:
 *  - закреплённое сообщение (панель сверху чата, нажатие - перейти к нему);
 *  - медленный режим: участник может писать не чаще раза в N секунд;
 *  - «медиа только от администраторов» (фото, видео, файлы, голосовые);
 *  - правила группы (текст, показывается новым участникам и в «Информации»);
 *  - для каналов-бизнеса: подписи авторов постов, комментарии вкл/выкл, бизнес-информация
 *    (сайт, телефон, адрес, часы работы, контакт для заказов, валюта цен).
 * Рассылаются пакетом группы; принимаются только от администраторов и основателя.
 * Новым участникам администратор присылает их при входе.
 */
object GroupSettings {
    data class Settings(
        val slowSeconds: Int = 0,
        val adminMediaOnly: Boolean = false,
        val pinnedUid: String = "",
        val pinnedText: String = "",
        val rules: String = "",
        val version: Long = 0,
        val signatures: Boolean = false,
        val comments: Boolean = true,
        val site: String = "",
        val phone: String = "",
        val address: String = "",
        val hours: String = "",
        val contactId: String = "",
        val currency: String = "₽",
    ) {
        val hasBusiness get() = site.isNotBlank() || phone.isNotBlank() || address.isNotBlank() || hours.isNotBlank() || contactId.isNotBlank()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("group_settings", Context.MODE_PRIVATE)

    fun get(ctx: Context, chatId: String): Settings = runCatching {
        fromJson(JSONObject(prefs(ctx).getString(chatId, "{}")!!))
    }.getOrDefault(Settings())

    private fun fromJson(o: JSONObject) = Settings(
        o.optInt("s", 0),
        o.optBoolean("m", false),
        o.optString("pu"),
        o.optString("pt"),
        o.optString("r"),
        o.optLong("v", 0),
        o.optBoolean("sg", false),
        o.optBoolean("c", true),
        o.optString("bs"),
        o.optString("bp"),
        o.optString("ba"),
        o.optString("bh"),
        o.optString("bc"),
        o.optString("bcur", "₽").ifBlank { "₽" },
    )

    fun toJson(s: Settings): String = JSONObject()
        .put("s", s.slowSeconds)
        .put("m", s.adminMediaOnly)
        .put("pu", s.pinnedUid)
        .put("pt", s.pinnedText.take(160))
        .put("r", s.rules.take(600))
        .put("v", s.version)
        .put("sg", s.signatures)
        .put("c", s.comments)
        .put("bs", s.site.take(120))
        .put("bp", s.phone.take(32))
        .put("ba", s.address.take(160))
        .put("bh", s.hours.take(120))
        .put("bc", s.contactId.take(76))
        .put("bcur", s.currency.take(6))
        .toString()

    /** Изменить у себя (администратор) - возвращает JSON для рассылки. */
    fun update(ctx: Context, chatId: String, change: (Settings) -> Settings): String {
        val s = change(get(ctx, chatId)).copy(version = System.currentTimeMillis())
        prefs(ctx).edit().putString(chatId, toJson(s)).apply()
        changed()
        return toJson(s)
    }

    /** Пришло от администратора: применяем, если новее. */
    fun onRemote(ctx: Context, chatId: String, json: String) {
        val s = runCatching { fromJson(JSONObject(json)) }.getOrNull() ?: return
        if (s.version <= get(ctx, chatId).version) return
        prefs(ctx).edit().putString(chatId, toJson(s)).apply()
        changed()
    }

    // ---------- медленный режим (проверяется у отправителя) ----------

    private fun lastSentKey(chatId: String) = "last_$chatId"

    /** Сколько секунд ещё ждать перед следующим сообщением (0 - можно). */
    fun waitSeconds(ctx: Context, chatId: String, isAdmin: Boolean): Int {
        if (isAdmin) return 0
        val slow = get(ctx, chatId).slowSeconds
        if (slow <= 0) return 0
        val last = prefs(ctx).getLong(lastSentKey(chatId), 0L)
        val left = slow - ((System.currentTimeMillis() - last) / 1000).toInt()
        return left.coerceAtLeast(0)
    }

    fun markSent(ctx: Context, chatId: String) = prefs(ctx).edit().putLong(lastSentKey(chatId), System.currentTimeMillis()).apply()

    val SLOW_OPTIONS = intArrayOf(0, 10, 30, 60, 300, 900, 3600)
}
