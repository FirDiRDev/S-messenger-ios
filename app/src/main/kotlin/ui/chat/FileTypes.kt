// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/** Типы файлов в чате: значок, цвет, MIME, чем открыть. */
object FileTypes {
    private const val TAG = "FileTypes"
    const val APK_MIME = "application/vnd.android.package-archive"

    // Все распространённые контейнеры и «сырые» потоки кодеков - открываются встроенным плеером
    // (если кодек телефон не поддерживает - плеер предложит другое приложение).
    private val VIDEO = setOf(
        "mp4", "m4v", "mpeg4", "mkv", "webm", "3gp", "3g2", "mov", "qt", "avi", "divx", "xvid",
        "flv", "f4v", "wmv", "wmv3", "asf", "ogv", "ogm", "ts", "mts", "m2ts", "m2t", "tp", "trp", "vob", "iso",
        "rm", "rmvb", "mpeg", "mpg", "mpe", "m1v", "m2v", "mp2v", "dat", "h263", "263", "h264", "264", "avc",
        "h265", "265", "hevc", "av1", "obu", "ivf", "vp8", "vp9", "mjpeg", "mjpg", "dv", "dif", "drc", "cpk",
    )
    private val AUDIO = setOf(
        "mp3", "mp2", "m4a", "m4b", "m4p", "aac", "adts", "ogg", "oga", "opus", "weba", "wav", "wave", "flac",
        "alac", "wma", "ac3", "eac3", "dts", "amr", "awb", "3ga", "aiff", "aif", "aifc", "mid", "midi", "kar",
        "mka", "mod", "xm", "s3m", "it", "mpc", "ra", "ram", "spx", "speex", "tta", "voc", "wv", "wavpack", "caf", "ape",
    )
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
    private val DOC = setOf("doc", "docx", "odt", "rtf")
    private val SHEET = setOf("xls", "xlsx", "ods", "csv")
    private val SLIDES = setOf("ppt", "pptx", "odp")
    private val TEXT = setOf("txt", "md", "log", "json", "xml", "html", "htm", "kt", "java", "py", "js")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isVideo(name: String) = ext(name) in VIDEO

    /** «Отправили фото / видео / архив «x» / файл «x»» - для уведомлений и списка чатов. */
    fun sentText(ctx: android.content.Context, name: String): String {
        val e = ext(name)
        val r = ctx.resources
        return when {
            e == "gif" || com.stoyko.smessenger.chat.KbGif.isKbGif(name) -> "🎞 GIF"
            e in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp") -> "📷 " + r.getString(com.stoyko.smessenger.R.string.sent_photo)
            e in VIDEO -> "🎬 " + r.getString(com.stoyko.smessenger.R.string.sent_video)
            e in AUDIO -> "🎵 " + r.getString(com.stoyko.smessenger.R.string.sent_audio, name)
            e in ARCHIVE -> "🗜 " + r.getString(com.stoyko.smessenger.R.string.sent_archive, name)
            isApk(name) -> "🤖 " + r.getString(com.stoyko.smessenger.R.string.sent_apk, name)
            e in DOC || e in SHEET || e in SLIDES || e == "pdf" || e in TEXT -> "📄 " + r.getString(com.stoyko.smessenger.R.string.sent_document, name)
            else -> "📎 " + r.getString(com.stoyko.smessenger.R.string.sent_file, name)
        }
    }
    fun isApk(name: String) = ext(name) == "apk" || ext(name) == "apks" || ext(name) == "xapk"

    fun mime(name: String): String {
        val e = ext(name)
        if (e == "apk") return APK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: when (e) {
            in VIDEO -> "video/*"
            in AUDIO -> "audio/*"
            else -> "*/*"
        }
    }

    /** Значок файла: короткая подпись + цвет круга. */
    fun badge(name: String): Pair<String, Int> {
        val e = ext(name)
        return when {
            isApk(name) -> "APK" to 0xFF3DDC84.toInt()
            e in ARCHIVE -> e.uppercase(Locale.ROOT).take(3) to 0xFFF59E0B.toInt()
            e == "pdf" -> "PDF" to 0xFFE5484D.toInt()
            e in DOC -> "DOC" to 0xFF2B7CD3.toInt()
            e in SHEET -> "XLS" to 0xFF21A366.toInt()
            e in SLIDES -> "PPT" to 0xFFD24726.toInt()
            e in TEXT -> "TXT" to 0xFF64748B.toInt()
            e in AUDIO -> "♪" to 0xFF8B5CF6.toInt()
            e in VIDEO -> "▶" to 0xFF0EA5E9.toInt()
            e.isNotEmpty() && e.length <= 4 -> e.uppercase(Locale.ROOT) to 0xFF5B4BDB.toInt()
            else -> "📄" to 0xFF5B4BDB.toInt()
        }
    }

    fun applyBadge(view: TextView, name: String) {
        val (label, color) = badge(name)
        view.text = label
        view.setTextColor(Color.WHITE)
        view.textSize = if (label.length >= 3) 11f else 16f
        view.gravity = Gravity.CENTER
        view.paint.isFakeBoldText = true
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** Адрес для других приложений: file:// превращаем в content:// через FileProvider. */
    fun shareUri(context: Context, destination: String): Uri {
        val u = Uri.parse(destination)
        return if (u.scheme == "file") {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", File(u.path!!))
        } else {
            u
        }
    }

    /**
     * Открыть файл: видео - во встроенном плеере, APK - установка, остальное - «Открыть с помощью».
     */
    fun open(context: Context, destination: String, name: String) {
        val uri = try {
            shareUri(context, destination)
        } catch (e: Exception) {
            Log.e(TAG, "uri: $e")
            Toast.makeText(context, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        if (!readable(context, uri)) {
            Toast.makeText(context, R.string.file_no_access, Toast.LENGTH_LONG).show()
            return
        }
        try {
            when {
                isVideo(name) -> VideoPlayerDialog(context, uri, name).show()
                ext(name) in AUDIO -> AudioPlayerDialog(context, uri, name).show()
                // TXT/JSON с серверами VPN - сначала предложить встроенный VPN.
                com.stoyko.smessenger.vpn.VpnShare.isConfigName(name) && !isApk(name) ->
                    com.stoyko.smessenger.vpn.VpnShare.checkFile(context, uri, name) { fileActions(context, uri, name) }
                else -> fileActions(context, uri, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "open: $e")
            Toast.makeText(context, R.string.file_no_access, Toast.LENGTH_LONG).show()
        }
    }

    /** Открыть / установить или сохранить в «Загрузки». */
    private fun fileActions(context: Context, uri: Uri, name: String) {
        // Чат с запретом пересылки и сохранения: документ нельзя отдать другому приложению.
        if (com.stoyko.smessenger.chat.ScreenGuard.blocked && !isApk(name)) {
            Toast.makeText(context, R.string.chatfx_guard_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val actions = ArrayList<Pair<String, () -> Unit>>()
            if (isApk(name)) actions += context.getString(R.string.file_install) to { install(context, uri, name) }
            actions += context.getString(R.string.open_with) to { openWith(context, uri, name) }
            actions += context.getString(saveTargetLabel(name)) to { saveToPublic(context, uri, name) }
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(name)
                .setItems(actions.map { it.first }.toTypedArray<CharSequence>()) { _, i -> actions[i].second() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "open: $e")
            Toast.makeText(context, R.string.file_no_access, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * «Поделиться» файлом с другим приложением. Раньше уходил адрес как есть: принятый файл лежит
     * под служебным именем без расширения, и другое приложение получало «белый файл без ничего».
     * Теперь - копия под настоящим именем [name] и с верным типом; пустой или исчезнувший файл
     * не отправляется (показывается ошибка).
     */
    fun shareFile(context: Context, uri: Uri, name: String) {
        val app = context.applicationContext
        thumbPool.execute {
            val target = try {
                namedForShare(app, uri, name)
            } catch (e: Exception) {
                Log.w(TAG, "share copy: $e")
                null
            }
            main.post {
                if (target == null) {
                    Toast.makeText(app, R.string.share_file_failed, Toast.LENGTH_LONG).show()
                    return@post
                }
                val type = mime(name).takeIf { it != "*/*" }
                    ?: runCatching { app.contentResolver.getType(target) }.getOrNull() ?: "*/*"
                val send = Intent(Intent.ACTION_SEND).apply {
                    this.type = type
                    putExtra(Intent.EXTRA_STREAM, target)
                    putExtra(Intent.EXTRA_TITLE, name)
                    clipData = android.content.ClipData.newRawUri(name, target)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, context.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!safeStart(context, chooser)) Toast.makeText(app, R.string.share_file_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Адрес для «Поделиться» под именем [name]: при необходимости - копия во временной папке. null - файла нет или он пустой. */
    private fun namedForShare(ctx: Context, uri: Uri, name0: String): Uri? {
        val name = ForwardFiles.cleanName(name0)
        val own = ForwardFiles.ownFile(ctx, uri)
        if (own == null) {
            // Чужой адрес с тем же именем - можно отдать как есть, если он читается и не пустой.
            if (ForwardFiles.displayName(ctx, uri) == name && nonEmpty(ctx, uri)) return uri
        } else {
            if (!own.isFile || own.length() <= 0L) return null
            if (own.name == name) return FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", own)
        }
        // Копия под настоящим именем; старые копии (старше суток) убираем.
        val root = File(ctx.cacheDir, "shared_images/share").apply { mkdirs() }
        val old = System.currentTimeMillis() - 24 * 3600_000L
        root.listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
        val dir = File(root, System.nanoTime().toString()).apply { mkdirs() }
        val out = File(dir, name)
        val ok = if (own != null) {
            own.copyTo(out, overwrite = true)
            true
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } } != null
        }
        if (!ok || out.length() <= 0L) {
            dir.deleteRecursively()
            return null
        }
        return FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", out)
    }

    private fun nonEmpty(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
    } catch (e: Exception) {
        false
    }

    /** Можно ли прочитать файл (доступ к файлу из папки телефона мог быть отозван). */
    fun readable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Запуск чужого экрана без вылета приложения (SecurityException, нет приложения и т. п.). */
    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "start: $e")
        false
    }

    fun openWith(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime(name))
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!safeStart(context, Intent.createChooser(intent, context.getString(R.string.open_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Toast.makeText(context, context.getString(R.string.mimetype_handler_not_found, mime(name)), Toast.LENGTH_LONG).show()
        }
    }

    /** Установка APK. На Android 8+ сначала нужно разрешить установку из этого приложения. */
    fun install(context: Context, uri: Uri, name: String) {
        if (ext(name) == "apks" || ext(name) == "xapk") {
            // .apks/.xapk (base.apk + части): распаковываем и ставим одной сессией сами.
            com.stoyko.smessenger.update.SessionInstaller.installSplit(context, uri, name)
            return
        }
        if (ext(name) != "apk") {
            openWith(context, uri, name)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.apk_allow_install, Toast.LENGTH_LONG).show()
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                openWith(context, uri, name)
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!safeStart(context, intent)) openWith(context, uri, name)
    }

    // ---------- Сохранение в Галерею / Загрузки ----------

    private fun isImage(name: String) = ext(name) in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")

    private fun folderLabel(name: String) = when {
        isImage(name) || isVideo(name) -> R.string.folder_gallery
        ext(name) in AUDIO -> R.string.folder_music
        else -> R.string.folder_downloads
    }

    fun saveTargetLabel(name: String) = when {
        isImage(name) || isVideo(name) -> R.string.save_to_gallery
        ext(name) in AUDIO -> R.string.save_to_music
        else -> R.string.save_to_downloads
    }

    /**
     * Сохранить копию файла: фото и видео - в Галерею (Pictures/Movies/StoykoMessenger),
     * музыку - в Music, остальное - в «Загрузки/StoykoMessenger». Без лишних разрешений (Android 10+).
     */
    fun saveToPublic(context: Context, uri: Uri, name: String) {
        val app = context.applicationContext
        Toast.makeText(context, R.string.saving, Toast.LENGTH_SHORT).show()
        thumbPool.execute {
            val ok = try {
                save(app, uri, name)
            } catch (e: Exception) {
                Log.e(TAG, "save: $e")
                false
            }
            main.post {
                Toast.makeText(
                    app,
                    if (ok) app.getString(R.string.saved_to, app.getString(folderLabel(name))) else app.getString(R.string.save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun save(context: Context, uri: Uri, name: String): Boolean {
        val mime = mime(name)
        val (collection, dir) = when {
            isImage(name) -> (if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Images.Media.getContentUri("external_primary") else null) to android.os.Environment.DIRECTORY_PICTURES
            isVideo(name) -> (if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Video.Media.getContentUri("external_primary") else null) to android.os.Environment.DIRECTORY_MOVIES
            ext(name) in AUDIO -> (if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Audio.Media.getContentUri("external_primary") else null) to android.os.Environment.DIRECTORY_MUSIC
            else -> (if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.getContentUri("external_primary") else null) to android.os.Environment.DIRECTORY_DOWNLOADS
        }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29 && collection != null) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$dir/StoykoMessenger")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: return false
            try {
                resolver.openInputStream(uri)?.use { input ->
                    resolver.openOutputStream(target)?.use { out -> input.copyTo(out, 256 * 1024) } ?: return false
                } ?: return false
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                return true
            } catch (e: Exception) {
                runCatching { resolver.delete(target, null, null) }
                throw e
            }
        }
        // Android 9 и старше: папка на общей памяти (нужно разрешение на запись).
        val folder = File(android.os.Environment.getExternalStoragePublicDirectory(dir), "StoykoMessenger").apply { mkdirs() }
        var out = File(folder, name)
        var n = 1
        while (out.exists()) out = File(folder, name.substringBeforeLast('.') + " ($n)." + ext(name)).also { n++ }
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } } ?: return false
        android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mime), null)
        return true
    }

    // ---------- Превью видео ----------

    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbPool = Executors.newFixedThreadPool(3) { Thread(it, "video-thumb").apply { isDaemon = true } }

    /** Кадры видео хранятся и на диске - при повторном открытии чата показываются сразу. */
    private fun diskThumb(ctx: Context, destination: String, w: Int): File =
        File(File(ctx.cacheDir, "vthumbs").apply { mkdirs() }, Integer.toHexString((destination + ":" + w).hashCode()) + ".jpg")
    private val main = Handler(Looper.getMainLooper())

    /** Кадр из видео в [target] (асинхронно, с кэшем). [key] защищает от переиспользованных строк списка. */
    fun loadThumb(context: Context, destination: String, target: ImageView, maxWidth: Int) {
        target.tag = destination
        thumbs.get(destination)?.let {
            target.setImageBitmap(it)
            return
        }
        target.setImageDrawable(null)
        target.minimumWidth = maxWidth
        target.minimumHeight = maxWidth * 9 / 16
        target.setBackgroundColor(0xFF111827.toInt())
        val app = context.applicationContext
        thumbPool.execute {
            val disk = diskThumb(app, destination, maxWidth)
            val bmp = runCatching { if (disk.exists()) android.graphics.BitmapFactory.decodeFile(disk.path) else null }.getOrNull() ?: try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(app, shareUri(app, destination))
                    // Android 8.1+: кадр сразу нужного размера (без декодирования в полном
                    // разрешении - в разы быстрее для 1080p/4K).
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (vw > 0 && vh > 0) {
                            val sw = minOf(maxWidth, vw)
                            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sw, (sw.toLong() * vh / vw).toInt().coerceAtLeast(1))
                        } else {
                            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } else {
                        r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    frame?.let { scale(it, maxWidth) }
                } finally {
                    runCatching { r.release() }
                }
            } catch (e: Exception) {
                null
            }?.also { b -> runCatching { disk.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } } }
            if (bmp != null) thumbs.put(destination, bmp)
            main.post {
                if (target.tag == destination && bmp != null) {
                    target.setBackgroundColor(Color.TRANSPARENT)
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Длительность видео «1:23» (или пусто). */
    fun durationText(context: Context, destination: String): String = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, shareUri(context, destination))
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms <= 0) "" else "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        } finally {
            runCatching { r.release() }
        }
    } catch (e: Exception) {
        ""
    }

    private fun scale(b: Bitmap, maxWidth: Int): Bitmap {
        if (b.width <= maxWidth) return b
        val h = (b.height.toFloat() * maxWidth / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, maxWidth, h, true)
    }
}

/**
 * Встроенный видеоплеер. Теперь это отдельное окно (VideoPlayerActivity): при повороте экрана
 * видео не прерывается и не выкидывает обратно в чат. Класс оставлен для совместимости вызовов.
 */
class VideoPlayerDialog(private val context: Context, private val uri: Uri, private val name: String) {
    fun show() {
        try {
            com.stoyko.smessenger.ui.player.VideoPlayerActivity.start(context, uri, name)
        } catch (e: Exception) {
            Log.w("VideoPlayer", "player: $e")
            FileTypes.openWith(context, uri, name)
        }
    }
}
