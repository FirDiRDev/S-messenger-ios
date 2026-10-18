// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Фото перед отправкой: снимок камеры весит 4-12 МБ, а в чате его смотрят на экране телефона.
 * В режиме «Оптимальное» (по умолчанию) фото больше 2560 точек по длинной стороне или
 * тяжелее 900 КБ пересжимается в JPEG (качество 86, поворот по EXIF учтён) - уходит в 3-8 раз
 * быстрее, особенно по мобильной сети и через VPN. Заодно из файла уходят метаданные
 * (координаты GPS, модель телефона) - собеседник видит только картинку.
 *
 * GIF и анимированные картинки, PNG с прозрачностью и маленькие фото не трогаются; если
 * пересжатый файл не стал меньше - отправляется оригинал. Режим «Оригинал» - без изменений.
 */
object MediaOptimizer {
    private const val TAG = "MediaOptimizer"
    private const val MAX_SIDE = 2560
    private const val MIN_BYTES = 900 * 1024L
    private const val QUALITY = 86

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("media", Context.MODE_PRIVATE)
    fun original(ctx: Context) = prefs(ctx).getBoolean("photo_original", false)
    fun setOriginal(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("photo_original", v).apply()

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "bmp")

    /** Подходит ли файл для пересжатия (по имени). */
    fun isPhotoName(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("sticker_") || n.startsWith("kbgif_")) return false
        return n.substringAfterLast('.', "") in IMAGE_EXT
    }

    /**
     * Пересжать [src] (файл в папке отправки). Возвращает новый файл рядом (имя - то же, но .jpg)
     * или null, если оставить как есть. Исходный файл удаляется, только когда новый готов.
     */
    fun optimize(ctx: Context, src: File): File? {
        if (original(ctx) || !src.isFile || !isPhotoName(src.name)) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val big = maxOf(w, h) > MAX_SIDE
            if (!big && src.length() < MIN_BYTES) return null
            // PNG с прозрачностью в JPEG превратился бы в чёрный фон - пропускаем.
            val png = src.name.endsWith(".png", true)
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (png && bmp.hasAlpha()) {
                bmp.recycle()
                return null
            }
            val rotated = rotateByExif(src, bmp)
            val scale = MAX_SIDE.toFloat() / maxOf(rotated.width, rotated.height)
            val fin = if (scale < 1f) {
                Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== rotated) rotated.recycle() }
            } else {
                rotated
            }
            val base = src.name.substringBeforeLast('.')
            val out = File(src.parentFile, "$base.jpg".let { if (it == src.name) "${base}_s.jpg" else it })
            out.outputStream().use { fin.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            fin.recycle()
            if (out.length() <= 0 || out.length() >= src.length()) {
                out.delete()
                return null
            }
            Log.i(TAG, "photo ${src.length() / 1024} KB -> ${out.length() / 1024} KB")
            if (out.absolutePath != src.absolutePath) src.delete()
            out
        } catch (e: Throwable) {
            // Нехватка памяти, повреждённый файл - отправляем как есть.
            Log.w(TAG, "optimize: $e")
            null
        }
    }

    private fun rotateByExif(src: File, bmp: Bitmap): Bitmap {
        val deg = runCatching {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (deg == 0f) return bmp
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
                .also { if (it !== bmp) bmp.recycle() }
        }.getOrDefault(bmp)
    }

    /** Файл по адресу нашего провайдера (content://…fileprovider/outgoing/…) или null. */
    fun fileOf(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        val seg = uri.pathSegments
        if (seg.size >= 2 && seg[0] == "outgoing") return File(ctx.filesDir, "outgoing/" + seg.drop(1).joinToString("/"))
        return null
    }
}
