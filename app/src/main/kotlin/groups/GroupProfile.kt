// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Оформление группы/канала: аватарка и версия (время последнего изменения названия/аватарки).
 * Название хранится в базе (как и раньше). Меняют владелец и модераторы, изменения расходятся
 * всем участникам служебными пакетами (см. GroupManager).
 */
object GroupProfile {
    private const val PREFS = "group_profiles"

    /** Аватарка: квадрат до 256 px, JPEG - около 10-30 КБ, быстро доходит по сети. */
    const val AVATAR_PX = 256
    const val MAX_AVATAR_BYTES = 200 * 1024

    private val _changes = MutableStateFlow(0)

    /** Растёт при каждом изменении - экраны перерисовывают аватарки. */
    val changes: StateFlow<Int> = _changes

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "group_avatars").apply { mkdirs() }

    fun avatarFile(ctx: Context, chatId: String): File? =
        File(dir(ctx), "$chatId.jpg").takeIf { it.isFile && it.length() > 0 }

    fun version(ctx: Context, chatId: String): Long = prefs(ctx).getLong("v_$chatId", 0L)

    fun setVersion(ctx: Context, chatId: String, v: Long) = prefs(ctx).edit().putLong("v_$chatId", v).apply()

    /** Название, заданное владельцем после создания (ядро хранит только исходное). */
    fun name(ctx: Context, chatId: String): String? = prefs(ctx).getString("n_$chatId", null)?.takeIf { it.isNotBlank() }

    fun setName(ctx: Context, chatId: String, name: String) = prefs(ctx).edit().putString("n_$chatId", name).apply()

    /** Описание группы/канала («о группе»), до 300 символов. */
    fun description(ctx: Context, chatId: String): String = prefs(ctx).getString("d_$chatId", "").orEmpty()

    fun setDescription(ctx: Context, chatId: String, text: String) {
        prefs(ctx).edit().putString("d_$chatId", text.take(MAX_DESCRIPTION)).apply()
        _changes.value = _changes.value + 1
    }

    const val MAX_DESCRIPTION = 300

    fun saveAvatarBytes(ctx: Context, chatId: String, bytes: ByteArray?) {
        val f = File(dir(ctx), "$chatId.jpg")
        if (bytes == null || bytes.isEmpty()) {
            f.delete()
        } else {
            val tmp = File(dir(ctx), "$chatId.tmp")
            tmp.writeBytes(bytes)
            f.delete()
            tmp.renameTo(f)
        }
        _changes.value = _changes.value + 1
    }

    fun avatarBytes(ctx: Context, chatId: String): ByteArray? = avatarFile(ctx, chatId)?.readBytes()

    fun forget(ctx: Context, chatId: String) {
        File(dir(ctx), "$chatId.jpg").delete()
        prefs(ctx).edit().remove("v_$chatId").remove("n_$chatId").remove("d_$chatId").apply()
        _changes.value = _changes.value + 1
    }

    fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    /** Картинка -> квадратная аватарка (центр), JPEG. null - не удалось прочитать. */
    fun encodeAvatar(ctx: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = if (side > AVATAR_PX) Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true) else square
        var quality = 85
        var out: ByteArray
        do {
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            out = bos.toByteArray()
            quality -= 15
        } while (out.size > 60 * 1024 && quality > 30)
        out
    }.getOrNull()

    /** Круглая аватарка для списков и шапки (null - аватарки нет). */
    fun roundBitmap(ctx: Context, chatId: String, sizePx: Int): Bitmap? {
        val f = avatarFile(ctx, chatId) ?: return null
        val src = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, sizePx, sizePx), paint)
        src.recycle()
        return out
    }
}
