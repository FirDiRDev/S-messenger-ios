// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.tox.Tox

/**
 * Несколько фото в профиле: кроме главного (аватара) можно добавить ещё до [MAX] фото.
 * У собеседника они листаются свайпом в профиле и открываются на весь экран (с увеличением).
 *
 * Как доходят до друзей (всё со сквозным шифрованием):
 *  - при подключении друга уходит список фото (короткие отпечатки);
 *  - у друга чего-то нет - он просит недостающие, и они приходят файлами «молча»
 *    (без строк в чате) и сохраняются в его профиле контакта.
 */
@Singleton
class ProfilePhotos @Inject constructor(
    private val context: Context,
    private val tox: Tox,
    private val fileTransferManager: FileTransferManager,
) {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    init {
        // Цепочка обработчиков: истории (StoryStore) и фото профиля работают вместе.
        val prevHidden = fileTransferManager.hiddenIncoming
        fileTransferManager.hiddenIncoming = { pk, name -> isPhotoFile(name) || prevHidden?.invoke(pk, name) == true }
        val prevDone = fileTransferManager.onIncomingComplete
        fileTransferManager.onIncomingComplete = { ft ->
            if (isPhotoFile(ft.fileName)) receive(ft) else prevDone?.invoke(ft)
        }
    }

    /** Отправить другу список своих фото (при подключении и после изменений). */
    fun announce(pk: PublicKey) {
        val hashes = mine(context).map { hashOf(it) }
        runCatching { tox.sendMessage(pk, LIST + hashes.joinToString(","), MessageType.Normal) }
    }

    /** Служебное сообщение о фото. true - обработано (в чат не писать). */
    fun onIncoming(pk: String, text: String): Boolean {
        when {
            text.startsWith(LIST) -> {
                val hashes = text.removePrefix(LIST).split(',').map { it.trim() }.filter { it.matches(HASH) }.take(MAX)
                prefs(context).edit().putString("order_$pk", hashes.joinToString(",")).apply()
                val dir = peerDir(context, pk)
                // Удалённые другом фото - убираем и у себя.
                dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in hashes) f.delete() }
                val missing = hashes.filter { !File(dir, "$it.jpg").exists() }
                if (missing.isNotEmpty()) runCatching { tox.sendMessage(PublicKey(pk), REQUEST + missing.joinToString(","), MessageType.Normal) }
                bump()
                return true
            }
            text.startsWith(REQUEST) -> {
                val wanted = text.removePrefix(REQUEST).split(',').map { it.trim() }.toSet()
                for (f in mine(context)) {
                    if (hashOf(f) !in wanted) continue
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
                        fileTransferManager.create(PublicKey(pk), uri, showInChat = false)
                    }.onFailure { Log.w(TAG, "send photo: $it") }
                }
                return true
            }
        }
        return false
    }

    private fun receive(ft: FileTransfer) {
        val hash = ft.fileName.removePrefix(FILE_PREFIX).substringBefore('.')
        if (!hash.matches(HASH)) return
        runCatching {
            val src = File(Uri.parse(ft.destination).path ?: return)
            val target = File(peerDir(context, ft.publicKey), "$hash.jpg")
            if (!src.renameTo(target)) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            bump()
        }.onFailure { Log.w(TAG, "receive photo: $it") }
    }

    private fun bump() {
        _changes.value = _changes.value + 1
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    companion object {
        private const val TAG = "ProfilePhotos"
        const val MAX = 6
        private const val LIST = "⁣🖼L:"
        private const val REQUEST = "⁣🖼R:"
        private const val FILE_PREFIX = "smpp_"
        private val HASH = Regex("[0-9a-f]{16}")
        private val main = Handler(Looper.getMainLooper())
        private val listeners = LinkedHashSet<() -> Unit>()

        fun listen(l: () -> Unit) = main.post { listeners += l }
        fun unlisten(l: () -> Unit) = main.post { listeners -= l }

        fun isPhotoFile(name: String) = name.startsWith(FILE_PREFIX)
        fun isPhotoMessage(text: String) = text.startsWith(LIST) || text.startsWith(REQUEST)

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("profile_photos", Context.MODE_PRIVATE)
        private fun myDir(ctx: Context) = File(ctx.filesDir, "profile_photos").apply { mkdirs() }
        private fun peerDir(ctx: Context, pk: String) = File(File(ctx.filesDir, "peer_photos"), pk).apply { mkdirs() }

        private fun hashOf(f: File) = f.name.removePrefix(FILE_PREFIX).substringBefore('.')

        /** Мои дополнительные фото (в порядке добавления). */
        fun mine(ctx: Context): List<File> {
            val order = prefs(ctx).getString("mine", "").orEmpty().split(',').filter { it.isNotBlank() }
            val dir = myDir(ctx)
            return order.map { File(dir, "$FILE_PREFIX$it.jpg") }.filter { it.exists() }
        }

        /** Фото контакта (кроме аватара), в его порядке. */
        fun peer(ctx: Context, pk: String): List<File> {
            val order = prefs(ctx).getString("order_$pk", "").orEmpty().split(',').filter { it.isNotBlank() }
            val dir = peerDir(ctx, pk)
            return order.map { File(dir, "$it.jpg") }.filter { it.exists() }
        }

        /** Добавить фото: уменьшаем до 1280 px (JPEG), имя - отпечаток содержимого. null - не вышло или уже [MAX]. */
        fun add(ctx: Context, uri: Uri): File? {
            if (mine(ctx).size >= MAX) return null
            return runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return null
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val tmp = File(myDir(ctx), "tmp.jpg")
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                val hash = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).take(8).joinToString("") { "%02x".format(it) }
                val target = File(myDir(ctx), "$FILE_PREFIX$hash.jpg")
                tmp.renameTo(target)
                val order = mine(ctx).map { hashOf(it) } + hash
                prefs(ctx).edit().putString("mine", order.distinct().joinToString(",")).apply()
                target
            }.getOrNull()
        }

        fun remove(ctx: Context, f: File) {
            val h = hashOf(f)
            val order = mine(ctx).map { hashOf(it) }.filter { it != h }
            prefs(ctx).edit().putString("mine", order.joinToString(",")).apply()
            f.delete()
        }
    }
}
