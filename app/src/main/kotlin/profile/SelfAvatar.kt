// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.ImageView
import com.squareup.picasso.Picasso
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.tox.MAX_AVATAR_SIZE

private const val TAG = "SelfAvatar"
private const val AVATAR_PX = 320
private const val PREFIX = "self_"

/**
 * Свой аватар: хранится в папке приложения, собеседники получают его автоматически
 * при подключении (и сразу - при смене), со сквозным шифрованием.
 */
@Singleton
class SelfAvatar @Inject constructor(
    private val context: Context,
    private val contactRepository: ContactRepository,
    private val fileTransferManager: FileTransferManager,
    private val scope: CoroutineScope,
) {
    fun current(): File? = currentFile(context)

    /** Устанавливает фото из галереи/камеры: обрезка по центру в квадрат, сжатие до лимита протокола. */
    suspend fun set(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSquare(uri) ?: return@withContext false
            var quality = 90
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 10
            } while (bytes.size > MAX_AVATAR_SIZE - 1024 && quality > 20)
            if (bytes.size > MAX_AVATAR_SIZE) return@withContext false
            val dir = dir(context)
            val file = File(dir, "$PREFIX${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            dir.listFiles()?.forEach { if (it.name.startsWith(PREFIX) && it != file) it.delete() }
            broadcast()
            true
        } catch (e: Exception) {
            Log.e(TAG, "set avatar", e)
            false
        }
    }

    fun remove() {
        dir(context).listFiles()?.forEach { if (it.name.startsWith(PREFIX)) it.delete() }
        broadcast()
    }

    /** Разослать аватар всем, кто сейчас в сети. */
    fun broadcast() = scope.launch {
        val file = current()
        contactRepository.getAll().first()
            .filter { it.connectionStatus != ConnectionStatus.None }
            .forEach { runCatching { fileTransferManager.sendAvatar(PublicKey(it.publicKey), file) } }
    }

    /** Собеседник подключился - отправляем ему наш аватар (если он есть). */
    fun sendTo(pk: PublicKey) {
        val file = current() ?: return
        runCatching { fileTransferManager.sendAvatar(pk, file) }
    }

    private fun decodeSquare(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = exifRotation(uri)
        val side = min(src.width, src.height)
        val scale = AVATAR_PX.toFloat() / side
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, matrix, true)
    }

    // Снимки с камеры часто хранятся «боком» с пометкой поворота в EXIF.
    private fun exifRotation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                when (
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private fun dir(context: Context) = File(context.filesDir, "avatar").apply { mkdirs() }

        fun currentFile(context: Context): File? =
            dir(context).listFiles()?.filter { it.name.startsWith(PREFIX) }?.maxByOrNull { it.name }

        /** Показать свой аватар (или цветную заглушку с инициалами). */
        fun show(view: ImageView, name: String, publicKey: String, sizePx: Int) {
            val file = currentFile(view.context)
            if (file != null) {
                Picasso.get().load(file).resize(sizePx, sizePx).centerCrop().into(view)
            } else {
                view.setImageBitmap(
                    com.stoyko.smessenger.ui.AvatarFactory.create(
                        view.resources,
                        name.ifEmpty { "?" },
                        publicKey,
                        com.stoyko.smessenger.ui.Px(max(sizePx, 1)),
                    ),
                )
            }
        }
    }
}
