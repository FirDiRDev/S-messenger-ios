// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Подпись к фото, видео и файлам - вместе с файлом, а не отдельным сообщением.
 * Вместе с файлом уходит скрытое служебное сообщение (сквозное шифрование, как и всё),
 * по имени и размеру файла получатель показывает подпись прямо под ним.
 */
object Captions {
    private const val PREFIX = "⁣📎C:"
    private const val PREFS = "captions"
    const val MAX = 1000

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun isCaption(text: String) = text.startsWith(PREFIX)

    private fun key(pk: String, outgoing: Boolean, name: String, size: Long) = "$pk|${if (outgoing) 1 else 0}|$size|$name"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, pk: String, outgoing: Boolean, name: String, size: Long): String? =
        prefs(ctx).getString(key(pk, outgoing, name, size), null)

    fun put(ctx: Context, pk: String, outgoing: Boolean, name: String, size: Long, caption: String) {
        prefs(ctx).edit().putString(key(pk, outgoing, name, size), caption.take(MAX)).apply()
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    /** Служебное сообщение для собеседника: размер|имя, перевод строки, текст подписи. */
    fun message(name: String, size: Long, caption: String) = PREFIX + size + "|" + name.take(200) + "\n" + caption.take(MAX)

    /** Входящее служебное сообщение с подписью. true - обработано (в чат не пишем). */
    fun onIncoming(ctx: Context, pk: String, msg: String): Boolean {
        if (!isCaption(msg)) return false
        val body = msg.removePrefix(PREFIX)
        val head = body.substringBefore('\n')
        val caption = body.substringAfter('\n', "")
        val size = head.substringBefore('|').toLongOrNull() ?: return true
        val name = head.substringAfter('|', "")
        if (caption.isNotBlank()) put(ctx, pk, false, name, size, caption)
        return true
    }

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
}
