// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context

/**
 * Полные ID контактов (76 символов: ключ 64 + nospam 8 + контрольная сумма 4).
 *
 * Протокол сам знает только ключ (64). Полный ID появляется у нас двумя путями:
 *  - мы сами добавили человека по его ID - запоминаем его сразу;
 *  - собеседник прислал свой ID в служебной карточке профиля (уходит при каждом подключении).
 * Если собеседник сменит nospam - новая карточка перезапишет ID.
 */
object ContactIds {
    private const val PREFS = "contact_full_ids"
    const val LENGTH = 76

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Корректный полный ID: 76 hex-символов и верная контрольная сумма. */
    fun isValid(id: String?): Boolean {
        if (id == null || id.length != LENGTH) return false
        if (!id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        val bytes = ByteArray(LENGTH / 2) { i -> id.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        // Контрольная сумма Tox: XOR двухбайтовых слов ключа и nospam (первые 36 байт).
        var c0 = 0
        var c1 = 0
        for (i in 0 until 36) {
            if (i % 2 == 0) c0 = c0 xor (bytes[i].toInt() and 0xFF) else c1 = c1 xor (bytes[i].toInt() and 0xFF)
        }
        return c0 == (bytes[36].toInt() and 0xFF) && c1 == (bytes[37].toInt() and 0xFF)
    }

    /** Запомнить ID; принимается, только если он принадлежит этому ключу. */
    fun save(ctx: Context, publicKey: String, fullId: String): Boolean {
        val id = fullId.trim().uppercase()
        if (!isValid(id) || !id.startsWith(publicKey.uppercase())) return false
        if (prefs(ctx).getString(publicKey.uppercase(), null) != id) {
            prefs(ctx).edit().putString(publicKey.uppercase(), id).apply()
        }
        return true
    }

    /** Запомнить ID, по которому мы сами добавили человека. */
    fun saveAdded(ctx: Context, fullId: String) {
        val id = fullId.trim().uppercase()
        if (isValid(id)) save(ctx, id.take(64), id)
    }

    fun get(ctx: Context, publicKey: String): String? =
        prefs(ctx).getString(publicKey.uppercase(), null)?.takeIf { isValid(it) }

    fun forget(ctx: Context, publicKey: String) = prefs(ctx).edit().remove(publicKey.uppercase()).apply()
}
