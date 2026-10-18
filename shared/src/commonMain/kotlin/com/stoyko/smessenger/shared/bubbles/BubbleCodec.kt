// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.bubbles

import com.stoyko.smessenger.shared.platform.rawDeflate
import com.stoyko.smessenger.shared.platform.rawInflate
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Формат HTML-пузыря (совместим с Android-версией HtmlBubbles.pack/unpack):
 * HTML -> raw deflate (без заголовка zlib) -> Base64 без переносов строк.
 */
@OptIn(ExperimentalEncodingApi::class)
object BubbleCodec {
    /** Предел заголовка сообщения в байтах (сообщение Tox - 1372 байта вместе с подписью). */
    const val MAX_PACKED = 1150

    /** Распакованный HTML - не больше 64 КБ. */
    const val MAX_HTML = 65_536

    fun pack(html: String): String = Base64.Default.encode(rawDeflate(html.encodeToByteArray()))

    fun unpack(packed: String): String? = runCatching {
        rawInflate(Base64.Default.decode(packed), MAX_HTML).decodeToString()
    }.getOrNull()

    /** Упаковать и проверить, что влезает в одно сообщение. */
    fun packOrNull(html: String): String? = pack(html).takeIf { it.length <= MAX_PACKED }
}

/**
 * Правила моста страницы -> приложения, одинаковые на обеих платформах:
 * «ответить в чат», «скопировать», «открыть ссылку», «тост» - только сразу после касания
 * человека (а не сами по себе), ответ - не чаще раза в секунду и не длиннее 300 символов.
 */
class BubbleBridgePolicy(
    private val gestureWindowMs: Long = 1500,
    private val replyIntervalMs: Long = 1000,
) {
    private var lastReply: TimeMark? = null

    /** [touchAgeMs] - сколько мс назад было настоящее касание (isTrusted) пузыря; <0 - не было. */
    fun gesture(touchAgeMs: Long): Boolean = touchAgeMs in 0..gestureWindowMs

    /** Текст для отправки в чат или null, если отправлять нельзя. */
    fun acceptReply(text: String, touchAgeMs: Long): String? {
        val t = text.trim().take(300)
        if (t.isEmpty() || !gesture(touchAgeMs)) return null
        val last = lastReply
        if (last != null && last.elapsedNow().inWholeMilliseconds < replyIntervalMs) return null
        lastReply = TimeSource.Monotonic.markNow()
        return t
    }

    /** Ссылка разрешена только http(s) и только после касания. */
    fun acceptUrl(url: String, touchAgeMs: Long): String? {
        val u = url.trim()
        if (!gesture(touchAgeMs)) return null
        return u.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    }
}
