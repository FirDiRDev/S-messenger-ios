// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Base64
import android.util.Log
import com.stoyko.smessenger.App
import com.stoyko.smessenger.domain.feature.FriendRequestHooks
import com.stoyko.smessenger.profile.SelfAvatar
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.firstOrNull

/**
 * Карточка запроса в друзья. В Tox запрос - это только ключ (длинный ID) и текст до 1016 байт,
 * поэтому друг видел вместо имени длинный ID. Теперь в конец текста дописывается невидимая
 * метка с ником и крошечной аватаркой (WebP 24-48 px, сколько влезет): получатель видит
 * нормальное уведомление «Ник хочет добавить вас» с фото, а после «Принять» контакт сразу
 * получает имя и аватарку (настоящие придут от друга, когда он появится в сети).
 *
 * Формат: «текст\n⁣SMR1:<ник base64url>:<аватарка base64url или пусто>».
 */
object RequestCard {
    private const val TAG = "RequestCard"
    private const val MARK = "⁣SMR1:"
    /** Предел текста запроса Tox - 1016 байт; оставляем запас. */
    private const val MAX_BYTES = 1000

    class Card(val text: String, val name: String, val avatar: ByteArray?) {
        fun bitmap(): Bitmap? = avatar?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    fun install(app: App) {
        FriendRequestHooks.decorate = { msg -> wrap(app, msg) }
        FriendRequestHooks.clean = { msg -> parse(msg).text }
        FriendRequestHooks.onAccepted = { pk, raw -> applyToContact(app, pk, raw) }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    @Volatile private var cached: Pair<String, String>? = null

    /** Маленькая аватарка (base64), чтобы влезла в [budget] символов; null - не влезла / нет фото. */
    private fun avatarB64(ctx: Context, budget: Int): String? {
        if (budget < 120) return null
        val f = SelfAvatar.currentFile(ctx) ?: return null
        val key = f.name + ":" + f.length() + ":" + budget
        cached?.let { if (it.first == key) return it.second.ifEmpty { null } }
        val src = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 96 && o.outHeight / (s * 2) >= 96) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull() ?: return null
        var out: String? = null
        loop@ for (size in intArrayOf(48, 40, 32, 24)) {
            val sq = square(src, size)
            for (q in intArrayOf(55, 40, 25, 12)) {
                val bos = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                if (!sq.compress(fmt, q, bos)) continue
                val s = b64(bos.toByteArray())
                if (s.length <= budget) {
                    out = s
                    break@loop
                }
            }
        }
        cached = key to out.orEmpty()
        return out
    }

    private fun square(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val r = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width - side) / 2 + side, (src.height - side) / 2 + side)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(b).drawBitmap(src, r, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        return b
    }

    /** Добавить к тексту запроса карточку (ник + аватарка), не выходя за предел Tox. */
    fun wrap(app: App, message: String): String {
        val base = parse(message).text.trim()
        val name = runCatching { app.component.tox().getName() }.getOrNull().orEmpty().trim().take(48)
        if (name.isEmpty() && SelfAvatar.currentFile(app) == null) return base
        val head = "$base\n$MARK${b64(name.toByteArray())}:"
        val headBytes = head.toByteArray().size
        if (headBytes > MAX_BYTES) return base
        val av = runCatching { avatarB64(app, MAX_BYTES - headBytes) }.onFailure { Log.w(TAG, "avatar: $it") }.getOrNull().orEmpty()
        return head + av
    }

    fun parse(raw: String): Card {
        val i = raw.indexOf(MARK)
        if (i < 0) return Card(raw, "", null)
        val text = raw.substring(0, i).trimEnd()
        val parts = raw.substring(i + MARK.length).trim().split(':')
        val name = runCatching { String(Base64.decode(parts.getOrNull(0).orEmpty(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
            .getOrDefault("").trim().take(64)
        val av = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
            runCatching { Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
        }
        return Card(text, name, av)
    }

    /** Подпись запроса: ник из карточки, иначе короткий ID («A1B2 C3D4»). */
    fun title(card: Card, publicKey: String): String =
        card.name.ifBlank { publicKey.take(4) + " " + publicKey.drop(4).take(4) }

    /** Круглая аватарка для уведомления. */
    fun circle(b: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(b, null, Rect(0, 0, size, size), p)
        return out
    }

    /** Принят запрос: имя и аватарка из карточки - новому контакту (если своих ещё нет). */
    private fun applyToContact(app: App, pk: String, raw: String) {
        val card = parse(raw)
        if (card.name.isBlank() && card.avatar == null) return
        val repo = app.component.contactRepository()
        val cur = runCatching { kotlinx.coroutines.runBlocking { repo.get(pk).firstOrNull() } }.getOrNull()
        if (card.name.isNotBlank() && cur?.name.isNullOrBlank()) repo.setName(pk, card.name)
        val av = card.avatar ?: return
        if (!cur?.avatarUri.isNullOrBlank()) return
        runCatching {
            val f = File(File(app.filesDir, "request_avatars").apply { mkdirs() }, "$pk.webp")
            f.writeBytes(av)
            repo.setAvatarUri(pk, android.net.Uri.fromFile(f).toString())
        }.onFailure { Log.w(TAG, "avatar save: $it") }
    }
}
