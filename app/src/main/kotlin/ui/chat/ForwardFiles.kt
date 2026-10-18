// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.isComplete

/**
 * Откуда брать файл для пересылки и «Поделиться».
 *
 * Раньше пересылался адрес файла как есть, и у получателя появлялся «белый файл без ничего»:
 * - принятый файл лежит в памяти приложения под служебным именем без расширения (ft/<ключ>/<число>) -
 *   уходило это имя, и файл не открывался ни как фото, ни как архив или APK;
 * - копия отправленного могла быть уже удалена очисткой памяти - уходил файл размером 0;
 * - к оригиналу из галереи/проводника доступ временный - после перезапуска файл не читался;
 * - недокачанный входящий файл (заранее заполненный нулями до полного размера) уходил как есть.
 *
 * Теперь файл проверяется (существует, не пустой, докачан), уходит под своим настоящим именем
 * (с расширением - по нему получатель определяет тип), а чужие адреса копируются к себе.
 */
object ForwardFiles {
    private const val TAG = "ForwardFiles"

    /** Что пересылать. */
    sealed class Source {
        /** Готовый файл: [uri] читается, [name] - настоящее имя с расширением, [size] > 0. */
        data class Ready(val uri: Uri, val name: String, val size: Long) : Source()

        /** Файл переслать нельзя: [reason] - строка с «%1$s» (имя файла). */
        data class Fail(val name: String, val reason: Int) : Source()
    }

    /** Имя, под которым файл увидит получатель. */
    fun cleanName(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim().ifBlank { "file_${System.currentTimeMillis()}" }

    /**
     * Свой файл по адресу FileProvider приложения - настоящий путь в памяти приложения
     * (по корням из res/xml/file_paths.xml). null - адрес чужой или корень неизвестен.
     */
    fun ownFile(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        if (uri.scheme != "content" || uri.authority != "${BuildConfig.APPLICATION_ID}.fileprovider") return null
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val base = when (segs[0]) {
            "file_transfers" -> File(ctx.filesDir, "ft")
            "outgoing" -> File(ctx.filesDir, "outgoing")
            "stories" -> File(ctx.filesDir, "stories")
            "saved" -> File(ctx.filesDir, "saved")
            "profile_photos" -> File(ctx.filesDir, "profile_photos")
            "app_root" -> ctx.filesDir
            "shared_images" -> File(ctx.cacheDir, "shared_images")
            "voice" -> File(ctx.cacheDir, "voice")
            else -> return null
        }
        val f = File(base, segs.drop(1).joinToString("/"))
        // Защита от «../» в адресе.
        return f.takeIf { runCatching { it.canonicalPath.startsWith(base.canonicalPath) }.getOrDefault(false) }
    }

    /**
     * Проверить и подготовить файл [ft] к пересылке. Вызывать НЕ в главном потоке:
     * чужой файл (галерея, проводник) копируется к себе, пока к нему есть доступ.
     */
    fun resolve(ctx: Context, ft: FileTransfer): Source {
        val name = cleanName(ft.fileName)
        if (!ft.outgoing && !ft.isComplete()) return Source.Fail(name, R.string.forward_file_not_ready)
        if (ft.destination.isBlank()) return Source.Fail(name, R.string.forward_file_missing)
        val uri = runCatching { Uri.parse(ft.destination) }.getOrNull() ?: return Source.Fail(name, R.string.forward_file_missing)

        // Файл в памяти приложения (принятый, копия отправленного, голосовое, снимок).
        ownFile(ctx, uri)?.let { f ->
            if (!f.isFile) return Source.Fail(name, R.string.forward_file_missing)
            val len = f.length()
            if (len <= 0L) return Source.Fail(name, R.string.forward_file_empty)
            // Принятый файл короче, чем должен быть, - он повреждён, такой не пересылаем.
            if (!ft.outgoing && ft.fileSize > 0 && len < ft.fileSize) return Source.Fail(name, R.string.forward_file_missing)
            val stable = stableLink(ctx, f, name) ?: f
            return Source.Ready(Uri.fromFile(stable), name, stable.length())
        }

        // Чужой адрес (галерея, проводник): доступ к нему может пропасть - копируем к себе.
        return try {
            val dir = File(ctx.filesDir, "outgoing/fwd/${System.nanoTime()}").apply { mkdirs() }
            val out = File(dir, name)
            val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it, 256 * 1024) }
            }
            if (copied == null || !out.isFile) {
                dir.deleteRecursively()
                Source.Fail(name, R.string.forward_file_missing)
            } else if (out.length() <= 0L) {
                dir.deleteRecursively()
                Source.Fail(name, R.string.forward_file_empty)
            } else {
                Source.Ready(Uri.fromFile(out), name, out.length())
            }
        } catch (e: Exception) {
            Log.w(TAG, "copy $uri: $e")
            Source.Fail(name, R.string.forward_file_missing)
        }
    }

    /**
     * Принятый файл для пересылки - вторая ссылка на него (жёсткая ссылка, без лишнего места;
     * не вышло - копия) в папке отправляемого под настоящим именем. Так пересылка не сломается,
     * если исходное сообщение удалят, пока получатель не в сети. null - оставить файл как есть.
     */
    private fun stableLink(ctx: Context, f: File, name: String): File? {
        val outgoing = File(ctx.filesDir, "outgoing")
        val inOutgoing = runCatching { f.canonicalPath.startsWith(outgoing.canonicalPath + File.separator) }.getOrDefault(false)
        if (inOutgoing) return null
        val dir = File(outgoing, "fwd/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, name)
        val linked = try {
            android.system.Os.link(f.absolutePath, out.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
        val ok = linked || runCatching { f.copyTo(out, overwrite = true) }.isSuccess
        if (!ok || out.length() != f.length()) {
            dir.deleteRecursively()
            return null
        }
        return out
    }

    /** Имя файла по адресу (как его покажет другое приложение), null - неизвестно. */
    fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
