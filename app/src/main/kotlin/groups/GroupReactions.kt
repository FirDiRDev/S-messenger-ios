// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Реакции в группах и каналах: каждый участник может поставить несколько реакций на
 * любое сообщение или пост (текст, фото, видео, файл, голосовое). Реакции рассылаются всем
 * участникам пакетом группы, поэтому у всех видно одно и то же: «❤️ 5  👍 2  😂».
 *
 * Хранится: для каждого сообщения - кто (ключ участника, «me» - я) какие реакции поставил.
 */
object GroupReactions {
    const val ME = "me"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("group_reactions", Context.MODE_PRIVATE)
    private fun key(chatId: String, uid: String) = "${chatId.take(16)}_$uid"

    private fun load(ctx: Context, chatId: String, uid: String): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(key(chatId, uid), "{}")!!) }.getOrDefault(JSONObject())

    /** Набор реакций участника (или null). */
    private fun setFor(ctx: Context, chatId: String, uid: String, who: String, emojis: String?) {
        if (uid.isBlank()) return
        val o = load(ctx, chatId, uid)
        if (emojis.isNullOrBlank()) o.remove(who) else o.put(who, emojis)
        prefs(ctx).edit().apply {
            if (o.length() == 0) remove(key(chatId, uid)) else putString(key(chatId, uid), o.toString())
        }.apply()
        changed()
    }

    fun mine(ctx: Context, chatId: String, uid: String): String? = load(ctx, chatId, uid).optString(ME).ifBlank { null }

    /** Поставить/снять мою реакцию. Возвращает мой новый набор (его нужно разослать). */
    fun toggleMine(ctx: Context, chatId: String, uid: String, emoji: String): String? {
        val set = com.stoyko.smessenger.chat.ChatExtras.toggle(mine(ctx, chatId, uid), emoji)
        setFor(ctx, chatId, uid, ME, set)
        return set
    }

    /** Пришёл набор реакций участника. */
    fun onRemote(ctx: Context, chatId: String, peerKey: String, payload: String) {
        runCatching {
            val o = JSONObject(payload)
            setFor(ctx, chatId, o.getString("u"), peerKey.ifBlank { "?" }, o.optString("e"))
        }
    }

    fun payload(uid: String, emojis: String?): String = JSONObject().put("u", uid).put("e", emojis ?: "").toString()

    /** Реакции сообщения: эмодзи -> (сколько, есть ли моя). По убыванию количества. */
    fun counts(ctx: Context, chatId: String, uid: String): List<Triple<String, Int, Boolean>> {
        val o = load(ctx, chatId, uid)
        val counts = LinkedHashMap<String, Int>()
        val mineSet = HashSet<String>()
        for (who in o.keys()) {
            val list = com.stoyko.smessenger.chat.ChatExtras.split(o.optString(who))
            list.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            if (who == ME) mineSet += list
        }
        return counts.entries.sortedByDescending { it.value }.map { Triple(it.key, it.value, it.key in mineSet) }
    }
}
