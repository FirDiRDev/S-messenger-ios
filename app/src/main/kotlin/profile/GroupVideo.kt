// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Видео профиля в общих чатах (группы, комментарии): его видят все участники, а не только друзья.
 *
 * Как доставляется: участник сообщает в группу «моё видео - <отпечаток>»; у кого такого файла ещё
 * нет, просит его личным пакетом внутри группы и получает кусками (всё шифрует ядро, сквозным
 * образом). Файлы хранятся по ключу участника; новое видео заменяет старое, «нет видео» - удаляет.
 * Передача - в [com.stoyko.smessenger.groups.GroupManager], здесь - хранение и показ.
 */
object GroupVideo {
    /** Больше не рассылаем (10 секунд видео почти всегда меньше). */
    const val MAX_BYTES = 6 * 1024 * 1024

    private val HASH = Regex("[0-9a-f]{16}")
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Принимаемые сейчас файлы: ключ участника → (отпечаток, буфер, сколько пришло). */
    class Pending(val hash: String, val data: ByteArray) {
        var received = 0
        val got = java.util.BitSet()
    }

    val pending = ConcurrentHashMap<String, Pending>()

    /** Кому и что уже отправляли (не шлём одно и то же чаще раза в 10 минут). */
    private val sentAt = ConcurrentHashMap<String, Long>()

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun bump() = main.post { listeners.toList().forEach { runCatching { it() } } }

    fun isHash(s: String) = s.matches(HASH)

    private fun safeKey(key: String) = key.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(64)

    private fun dir(ctx: Context, key: String) =
        File(File(ctx.filesDir, "group_video"), safeKey(key)).apply { mkdirs() }

    /** Отпечаток моего видео (или null - видео нет / слишком большое). */
    fun myHash(ctx: Context): String? {
        val f = ProfileVideo.mine(ctx) ?: return null
        if (f.length() > MAX_BYTES) return null
        return f.name.removePrefix("smpv_").substringBefore('.').takeIf { isHash(it) }
    }

    fun myFile(ctx: Context): File? = ProfileVideo.mine(ctx)?.takeIf { it.length() in 1..MAX_BYTES.toLong() }

    /** Видео участника по его ключу в группе (или null). */
    fun of(ctx: Context, key: String): File? {
        if (key.isBlank()) return null
        return File(File(ctx.filesDir, "group_video"), safeKey(key)).listFiles()
            ?.firstOrNull { it.extension == "mp4" && it.length() > 0 }
    }

    /**
     * Участник объявил отпечаток. true - такого файла у нас нет, надо запросить.
     * Пусто - у участника больше нет видео: удаляем.
     */
    fun onAnnounce(ctx: Context, key: String, hash: String): Boolean {
        val d = dir(ctx, key)
        val h = hash.takeIf { isHash(it) }
        var changed = false
        d.listFiles()?.forEach { f -> if (f.nameWithoutExtension != h) changed = f.delete() || changed }
        if (changed) bump()
        if (h == null) {
            pending.remove(key)
            return false
        }
        return !File(d, "$h.mp4").exists() && pending[key]?.hash != h
    }

    /** Начать приём файла [hash] размером [size] от участника [key]. */
    fun begin(key: String, hash: String, size: Int): Pending? {
        if (!isHash(hash) || size <= 0 || size > MAX_BYTES) return null
        val cur = pending[key]
        if (cur != null && cur.hash == hash && cur.data.size == size) return cur
        return Pending(hash, ByteArray(size)).also { pending[key] = it }
    }

    /** Кусок пришёл; когда файл собран целиком - сохраняем и обновляем аватарки на экране. */
    fun chunk(ctx: Context, key: String, hash: String, total: Int, off: Int, bytes: ByteArray, from: Int, len: Int) {
        val p = begin(key, hash, total) ?: return
        if (off < 0 || off + len > p.data.size || len <= 0) return
        // Повторный кусок (при пересылке) не считаем дважды.
        System.arraycopy(bytes, from, p.data, off, len)
        if (!p.got.get(off)) {
            p.got.set(off)
            p.received += len
        }
        if (p.received >= p.data.size) {
            pending.remove(key)
            val d = dir(ctx, key)
            d.listFiles()?.forEach { it.delete() }
            val tmp = File(d, "$hash.tmp")
            runCatching {
                tmp.writeBytes(p.data)
                tmp.renameTo(File(d, "$hash.mp4"))
            }
            bump()
        }
    }

    /** Можно ли сейчас отправить своё видео участнику [peer] (защита от повторов). */
    fun mayServe(peer: String, hash: String): Boolean {
        val k = "$peer|$hash"
        val now = System.currentTimeMillis()
        val last = sentAt[k] ?: 0L
        if (now - last < 10 * 60_000L) return false
        sentAt[k] = now
        return true
    }

    /**
     * Аватарка участника в [box]: цветной кружок с буквой имени, поверх - живое видео профиля,
     * если оно у нас есть. Повторный вызов для того же участника ничего не перезапускает.
     */
    fun bindAvatar(box: FrameLayout, key: String, name: String) {
        val letter = (box.findViewWithTag<TextView>("ga_letter") ?: TextView(box.context).apply {
            tag = "ga_letter"
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            box.addView(this, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        val size = box.layoutParams?.width?.takeIf { it > 0 } ?: (34 * box.resources.displayMetrics.density).toInt()
        letter.textSize = size / box.resources.displayMetrics.density * 0.42f
        letter.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        val palette = intArrayOf(0xFF5B4BDB.toInt(), 0xFF0EA5E9.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFFEF4444.toInt())
        val color = palette[(key.hashCode() and 0x7FFFFFFF) % palette.size]
        if (letter.getTag(com.stoyko.smessenger.R.id.tag_emoji_motion) != color) {
            letter.setTag(com.stoyko.smessenger.R.id.tag_emoji_motion, color)
            letter.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        ProfileVideo.attach(box, of(box.context, key))
    }
}
