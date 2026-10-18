// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.room.RoomDatabase
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Полный профиль в одном файле `.smsave` (ZIP-архив):
 *
 *  - `smsave.json` - заголовок: формат, версия приложения, примерный объём;
 *  - `tox.save` - ключи и контакты (то, что раньше и было всем файлом профиля);
 *  - `databases/` - снимки всех баз: переписка, файлы в чатах (записи), группы и каналы,
 *    заявки (`core_db`), чеки покупок монет (`coin_purchases.db`). Снимок делается под
 *    блокировкой записи, журнал WAL вливается в файл базы, снимок проверяется `quick_check`;
 *  - `shared_prefs/` - ВСЕ файлы настроек (перебор папки, а не список): оформление, сеть,
 *    узлы, прокси, VPN и свои серверы, монеты, подарки, достижения, скины, закреплённые,
 *    реакции, папки, скрытые чаты… Кроме настроек, привязанных к телефону ([DEVICE_PREFS]);
 *  - `files/` - аватарки, фото и видео профиля, сохранения игр, стикеры, обои, истории,
 *    переписка S-AI, свои узлы и др.; по желанию - и сами файлы из чатов ([MEDIA_DIRS]);
 *  - `manifest.json` (последним) - опись: размер, SHA-256 и время изменения каждого файла,
 *    пути к папкам приложения на старом телефоне.
 *
 * Импорт: архив читается потоком, каждый файл сверяется с описью и складывается во временную
 * папку; пути старого телефона в настройках и базе заменяются на пути этого. Повреждённый
 * или недописанный файл отклоняется - данные не меняются. Ключи загружаются сразу, а
 * подготовленное раскладывается при перезапуске (до того как откроются база и настройки):
 * заменяемые файлы сначала уходят в резервную папку, при сбое всё возвращается как было.
 * Старые файлы `.smsave` (только ключи, и полный профиль без описи) по-прежнему открываются.
 */
object FullBackup {
    private const val TAG = "FullBackup"

    /** Версия формата архива (3 - с описью и контрольными суммами). */
    const val FORMAT = 3

    /** Отложенный архив прежних версий приложения (импорт до обновления, перезапуск - после). */
    private const val PENDING = "pending_restore.smsave"
    private const val HEADER = "smsave.json"
    private const val MANIFEST = "manifest.json"
    private const val TOX = "tox.save"
    private const val CORE_DB = "core_db"

    /** Папки в dataDir (та же файловая система, что и у files/databases - перенос без копирования). */
    private const val STAGED = "smsave_staged"
    private const val BACKUP = "smsave_backup"
    private const val READY = ".ready"
    private const val ATTEMPTS = ".attempts"
    private const val JOURNAL = "journal.txt"
    private const val DONE = ".done"
    private const val RESULT = "smsave_result.txt"

    private const val BUF = 128 * 1024
    private const val MAX_SMALL = 64L * 1024 * 1024

    /** Папки в files/, которые не входят в профиль (кэш, временное, обновление, журналы ошибок). */
    private val SKIP_DIRS = setOf(
        "cache", "tmp", "pending", "webview", "app_webview", "outgoing_tmp", "updates", "update",
        // Журналы вылетов этого телефона; рабочая папка ядра (ключи - уже в tox.save).
        "diag", "toxcore",
    )

    /** Файлы в files/, привязанные к телефону: защита от отката версии, отложенный импорт. */
    private val SKIP_FILES = setOf(PENDING, ".vguard")

    /** Файлы из чатов (могут быть большими - по желанию): полученные и копии отправленных. */
    private val MEDIA_DIRS = setOf("ft", "received", "outgoing")

    /**
     * Настройки, которые не переносятся: состояние обновлений (запрет отката - иначе на телефоне
     * с версией постарше приложение заблокируется), журнал вылетов, проверка «слабый телефон»,
     * кэши DNS и здоровья узлов, недокачанные файлы, подсказки этого телефона, масштаб экрана,
     * значки на ярлыке, служебное состояние VPN.
     */
    private val DEVICE_PREFS = setOf(
        "updater", "diag_exits", "device_profile", "emoji_warmup", "node_dns", "ft_partial",
        "battery_tip", "screen_fit", "go_guard", "badge_counts", "node_health",
        // Синхронизация устройств: id ЭТОГО телефона и отметки (секрет профиля - в device_sync, он переезжает).
        DeviceSync.LOCAL_PREFS,
    )

    /** Настройки библиотек (WebView, сервисы Google, WorkManager) - тоже телефона. */
    private val DEVICE_PREF_PREFIXES = listOf("WebView", "org.chromium", "com.google.", "com.android.", "androidx.", "FirebaseHeartBeat")

    /** Уже сжатые форматы - пакуются быстро, без попыток сжать ещё. */
    private val PACKED = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "mp4", "mkv", "webm", "3gp", "mov",
        "m4a", "mp3", "ogg", "opus", "aac", "zip", "apk", "gz", "7z", "rar", "tgs", "lottie",
    )

    private const val BASE_PACKAGE = "com.stoyko.smessenger"

    /** Этап для окна прогресса. */
    enum class Stage { PREPARE, WRITE, VERIFY, READ }

    /** Прогресс: [done] из [total] байт ([total] <= 0 - объём неизвестен). Вызывается из фонового потока. */
    fun interface Progress {
        fun update(stage: Stage, done: Long, total: Long)
    }

    /** Итог экспорта: файлов в архиве, их объём, размер самого архива, пропущенные файлы. */
    class ExportResult(val files: Int, val bytes: Long, val archiveBytes: Long, val skipped: List<String>)

    /** Итог проверки архива: ключи профиля и ошибка (null - всё сошлось). */
    class Verified(val toxSave: ByteArray?, val error: String?, val files: Int)

    sealed class ImportResult {
        /** Старый файл - только ключи профиля. */
        class KeysOnly(val save: ByteArray) : ImportResult()

        /** Полный профиль проверен и подготовлен; разложится по местам при перезапуске. */
        class Full(val save: ByteArray, val files: Int) : ImportResult()

        /** Файл отклонён; [reason] - понятное объяснение для пользователя. */
        class Failed(val reason: String) : ImportResult()
    }

    /** Отказ с объяснением (прерывает чтение архива). */
    private class Refused(message: String) : IOException(message)

    fun isFull(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    // ---------------- пути ----------------

    private fun dataDir(ctx: Context): File = File(ctx.applicationInfo.dataDir ?: ctx.filesDir.parent ?: ctx.filesDir.path)
    private fun dbDir(ctx: Context): File = ctx.getDatabasePath(CORE_DB).parentFile ?: File(dataDir(ctx), "databases")
    private fun prefsDir(ctx: Context): File = File(dataDir(ctx), "shared_prefs")
    private fun stagedDir(ctx: Context): File = File(dataDir(ctx), STAGED)
    private fun backupDir(ctx: Context): File = File(dataDir(ctx), BACKUP)

    private fun rel(root: File, f: File) = f.relativeTo(root).path.replace('\\', '/')

    private fun isDbSidecar(name: String) = Regex("-(wal|shm|journal|mj[0-9A-Fa-f]+)$").containsMatchIn(name)

    /** Файл настроек пользователя (а не телефона и не временная копия `.bak`). */
    private fun isUserPrefs(file: String): Boolean {
        if (!file.endsWith(".xml")) return false
        val n = file.removeSuffix(".xml")
        return n !in DEVICE_PREFS && DEVICE_PREF_PREFIXES.none { n.startsWith(it) }
    }

    /** Ключи (`*.tox` и их временные копии) - уже в tox.save; временные и служебные файлы. */
    private fun isSkippedFile(rel: String): Boolean {
        val name = rel.substringAfterLast('/')
        return rel in SKIP_FILES || name.endsWith(".tox") || name.contains(".tox.") ||
            name.endsWith(".wip") || name.endsWith(".tmp") || name.endsWith(".pid")
    }

    private fun isMedia(rel: String) = rel.substringBefore('/') in MEDIA_DIRS && rel.contains('/')

    private fun isPacked(name: String) = name.substringAfterLast('.', "").lowercase() in PACKED

    // ---------------- экспорт ----------------

    private class Item(val name: String, val file: File)

    /**
     * Записать полный профиль в [out] (поток не читается в память целиком). [withMedia] - добавить
     * и сами файлы из чатов. [room] - открытая база приложения: её снимок делается под её же
     * блокировкой записи (переписка не «рвётся» посередине). Вызывать не в главном потоке.
     */
    fun export(
        ctx: Context,
        out: OutputStream,
        toxSave: ByteArray,
        withMedia: Boolean,
        room: RoomDatabase? = null,
        progress: Progress? = null,
    ): ExportResult {
        val snapDir = File(ctx.cacheDir, "smsave_export").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            progress?.update(Stage.PREPARE, 0, 0)
            val checks = JSONObject()
            val items = ArrayList<Item>()
            items += snapshotDatabases(ctx, room, snapDir, checks)
            // Секрет синхронизации: старый и новый телефон с этим профилем потом сами
            // обмениваются сообщениями и контактами (см. DeviceSync).
            runCatching { DeviceSync.ensureSecret(ctx) }
            // Все настройки (перебор папки - новые настройки попадут в профиль сами).
            prefsDir(ctx).listFiles()?.filter { it.isFile && isUserPrefs(it.name) }?.sortedBy { it.name }
                ?.forEach { items += Item("shared_prefs/" + it.name, it) }
            // Файлы приложения.
            val root = ctx.filesDir
            root.walkTopDown().onEnter { d ->
                val r = rel(root, d)
                val top = r.substringBefore('/')
                r.isEmpty() || (top !in SKIP_DIRS && (withMedia || top !in MEDIA_DIRS))
            }.forEach { f ->
                if (!f.isFile) return@forEach
                val r = rel(root, f)
                if (isSkippedFile(r) || (!withMedia && isMedia(r))) return@forEach
                items += Item("files/$r", f)
            }

            val total = toxSave.size + items.sumOf { it.file.length() }
            val counted = CountingOutputStream(out)
            val entries = JSONArray()
            val skipped = ArrayList<String>()
            val buf = ByteArray(BUF)
            var done = 0L
            var lastUi = 0L
            var bytes = 0L
            fun tick(n: Int) {
                done += n
                val now = SystemClock.uptimeMillis()
                if (progress != null && now - lastUi >= 100) {
                    lastUi = now
                    progress.update(Stage.WRITE, done, total)
                }
            }
            ZipOutputStream(counted.buffered(256 * 1024)).use { zip ->
                fun putSmall(name: String, data: ByteArray) {
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                // Заголовок - первым: по нему импорт поймёт, что опись в конце обязательна.
                putSmall(
                    HEADER,
                    JSONObject().put("format", "smsave/$FORMAT").put("version", FORMAT)
                        .put("app", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE)
                        .put("bytes", total).put("media", withMedia).toString().toByteArray(),
                )
                putSmall(TOX, toxSave)
                entries.put(entry(TOX, toxSave.size.toLong(), sha256(toxSave), System.currentTimeMillis()))
                bytes += toxSave.size
                tick(toxSave.size)
                for (item in items) {
                    val f = item.file
                    val mtime = f.lastModified()
                    val input = try {
                        f.inputStream()
                    } catch (e: IOException) {
                        null
                    }
                    if (input == null) {
                        skipped += item.name
                        continue
                    }
                    input.use { s ->
                        zip.setLevel(if (isPacked(f.name)) Deflater.BEST_SPEED else Deflater.DEFAULT_COMPRESSION)
                        zip.putNextEntry(ZipEntry(item.name).apply { if (mtime > 0) time = mtime })
                        try {
                            val (size, sha) = copyHashing(s, zip, buf, ::tick)
                            zip.closeEntry()
                            entries.put(entry(item.name, size, sha, mtime))
                            bytes += size
                        } catch (e: IOException) {
                            // Файл не дочитался (удалён или меняется прямо сейчас): запись без отметки
                            // в описи при импорте пропускается. Если сломана запись архива - closeEntry
                            // бросит снова, и экспорт честно завершится ошибкой.
                            Log.w(TAG, "skip ${item.name}: $e")
                            zip.closeEntry()
                            skipped += item.name
                        }
                    }
                }
                val manifest = JSONObject()
                    .put("format", "smsave/$FORMAT")
                    .put("version", FORMAT)
                    .put("app", BuildConfig.VERSION_NAME)
                    .put("code", BuildConfig.VERSION_CODE)
                    .put("created", System.currentTimeMillis())
                    .put("media", withMedia)
                    .put("package", ctx.packageName)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("dataDir", dataDir(ctx).path)
                    .put("filesDir", ctx.filesDir.absolutePath)
                    .put("dbDir", dbDir(ctx).absolutePath)
                    .put("dbCheck", checks)
                    .put("count", entries.length())
                    .put("bytes", bytes)
                    .put("entries", entries)
                    .put("skipped", JSONArray(skipped))
                putSmall(MANIFEST, manifest.toString(1).toByteArray())
            }
            progress?.update(Stage.WRITE, total, total)
            return ExportResult(entries.length(), bytes, counted.count, skipped)
        } finally {
            snapDir.deleteRecursively()
        }
    }

    private fun entry(name: String, size: Long, sha: String, mtime: Long) =
        JSONObject().put("name", name).put("size", size).put("sha256", sha).put("mtime", mtime)

    /**
     * Снимки всех баз из databases/ в [snapDir]. Пока копируется файл базы и её журнал, запись в
     * базу ждёт; затем журнал вливается в копию (один целый файл) и копия проверяется.
     */
    private fun snapshotDatabases(ctx: Context, room: RoomDatabase?, snapDir: File, checks: JSONObject): List<Item> {
        val dir = dbDir(ctx)
        val names = dir.listFiles()?.filter { it.isFile && !isDbSidecar(it.name) }?.map { it.name }?.sorted() ?: return emptyList()
        val out = ArrayList<Item>()
        for (name in names) {
            val src = File(dir, name)
            val dst = File(snapDir, name)
            val snap = runCatching {
                if (name == CORE_DB && room != null) snapshotRoom(room, src, dst) else snapshotPlain(src, dst)
                compact(dst, checks)
            }.onFailure { Log.w(TAG, "snapshot $name: $it") }.getOrNull()
            if (snap != null) {
                snap.forEach { out += Item("databases/" + it.name, it) }
            } else {
                // Не удалось сделать снимок - копируем как есть (как раньше), вместе с журналом.
                listOf("", "-wal", "-journal").map { File(src.path + it) }.filter { it.isFile && it.length() > 0 }
                    .forEach { out += Item("databases/" + it.name, it) }
            }
        }
        return out
    }

    private fun copyDbFiles(src: File, dst: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dst.path + it).delete() }
        src.copyTo(dst, overwrite = true, bufferSize = BUF)
        val wal = File(src.path + "-wal")
        if (wal.isFile && wal.length() > 0) wal.copyTo(File(dst.path + "-wal"), overwrite = true, bufferSize = BUF)
    }

    /** База Room: журнал - в файл базы, затем копия под блокировкой записи самой Room. */
    private fun snapshotRoom(room: RoomDatabase, src: File, dst: File) {
        val db = room.openHelper.writableDatabase
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        db.beginTransaction()
        try {
            copyDbFiles(src, dst)
        } finally {
            db.endTransaction()
        }
    }

    /** Остальные базы (чеки монет и др.): своё соединение и BEGIN IMMEDIATE на время копирования. */
    private fun snapshotPlain(src: File, dst: File) {
        val wal = File(src.path + "-wal").exists()
        val flags = SQLiteDatabase.OPEN_READWRITE or (if (wal) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
        SQLiteDatabase.openDatabase(src.path, null, flags).use { d ->
            if (wal) runCatching { d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            d.beginTransactionNonExclusive()
            try {
                copyDbFiles(src, dst)
            } finally {
                d.endTransaction()
            }
        }
    }

    /**
     * Влить журнал WAL в копию базы и проверить её (`quick_check`). Возвращает файлы снимка:
     * обычно один файл; журнал - только если влить не удалось.
     */
    private fun compact(dst: File, checks: JSONObject?): List<File> {
        val wal = File(dst.path + "-wal")
        val flags = SQLiteDatabase.OPEN_READWRITE or (if (wal.exists()) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
        SQLiteDatabase.openDatabase(dst.path, null, flags).use { d ->
            if (wal.exists()) runCatching { d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            runCatching { d.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } }
            val check = runCatching {
                d.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "?" }
            }.getOrDefault("?")
            if (check != "ok") Log.w(TAG, "quick_check ${dst.name}: $check")
            checks?.put(dst.name, JSONObject().put("check", check).put("version", d.version))
        }
        File(dst.path + "-shm").delete()
        File(dst.path + "-journal").delete()
        return listOf(dst) + listOf(wal).filter { it.isFile && it.length() > 0 }
    }

    // ---------------- чтение и проверка ----------------

    private class Scan(val header: JSONObject?, val manifest: JSONObject?, val tox: ByteArray?, val got: Map<String, Pair<Long, String>>)

    /**
     * Прочитать архив потоком: каждая запись хешируется (SHA-256) и, если [sink] вернул файл,
     * сохраняется туда. ZIP сам проверяет CRC каждой записи (ZipException при порче).
     */
    private fun scan(
        input: InputStream,
        total: Long,
        stage: Stage,
        progress: Progress?,
        onHeader: ((JSONObject) -> Unit)? = null,
        sink: (String) -> File?,
    ): Scan {
        val counting = CountingInputStream(input)
        val got = HashMap<String, Pair<Long, String>>()
        var header: JSONObject? = null
        var manifest: JSONObject? = null
        var tox: ByteArray? = null
        val buf = ByteArray(BUF)
        var lastUi = 0L
        val discard = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        fun tick(@Suppress("UNUSED_PARAMETER") n: Int) {
            val now = SystemClock.uptimeMillis()
            if (progress != null && now - lastUi >= 100) {
                lastUi = now
                progress.update(stage, counting.count, total)
            }
        }
        ZipInputStream(counting.buffered(BUF)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                when (name) {
                    HEADER -> {
                        val h = JSONObject(String(readLimited(z), Charsets.UTF_8))
                        header = h
                        onHeader?.invoke(h)
                    }
                    MANIFEST -> manifest = JSONObject(String(readLimited(z), Charsets.UTF_8))
                    TOX -> {
                        val bytes = readLimited(z)
                        tox = bytes
                        got[name] = bytes.size.toLong() to sha256(bytes)
                    }
                    else -> {
                        val target = sink(name)
                        if (target == null) {
                            got[name] = copyHashing(z, discard, buf, ::tick)
                        } else {
                            target.parentFile?.mkdirs()
                            got[name] = FileOutputStream(target).use { o -> copyHashing(z, o, buf, ::tick) }
                            if (e.time > 0) target.setLastModified(e.time)
                        }
                    }
                }
                tick(0)
            }
        }
        progress?.update(stage, total, total)
        return Scan(header, manifest, tox, got)
    }

    /** Сверить прочитанное с описью. null - всё совпало (или это старый файл без описи). */
    private fun checkScan(ctx: Context, s: Scan): String? {
        if (s.tox == null) return ctx.getString(R.string.smsave_err_no_keys)
        val entries = s.manifest?.optJSONArray("entries")
        if (entries == null) {
            // Заголовок нового формата есть, а описи нет - файл оборвался на середине.
            return if (s.header != null) ctx.getString(R.string.smsave_err_truncated) else null
        }
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val have = s.got[name] ?: return ctx.getString(R.string.smsave_err_missing, name)
            if (have.first != o.optLong("size", -1L) || !have.second.equals(o.optString("sha256"), ignoreCase = true)) {
                return ctx.getString(R.string.smsave_err_damaged, name)
            }
        }
        return null
    }

    private fun corrupt(ctx: Context, e: Throwable): String =
        if (e is Refused) e.message.orEmpty() else ctx.getString(R.string.smsave_err_corrupt, e.message ?: e.javaClass.simpleName)

    /** Перечитать записанный архив целиком и сверить каждый файл с описью (после экспорта). */
    fun verify(ctx: Context, input: InputStream, total: Long, progress: Progress? = null): Verified = try {
        val s = scan(input, total, Stage.VERIFY, progress) { null }
        Verified(s.tox, checkScan(ctx, s), s.got.size)
    } catch (e: Exception) {
        Verified(null, corrupt(ctx, e), 0)
    }

    /** Ключи профиля из полного или старого `.smsave`. */
    fun toxSave(bytes: ByteArray): ByteArray? {
        if (!isFull(bytes)) return bytes
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == TOX) return z.readBytes()
            }
        }
        return null
    }

    /** Ключи из потока (без чтения всего архива в память - он может быть большим). */
    fun toxSave(input: InputStream): ByteArray? {
        val b = input.buffered(64 * 1024)
        b.mark(4)
        val p = b.read()
        val k = b.read()
        b.reset()
        if (p != 'P'.code || k != 'K'.code) return b.readBytes()
        ZipInputStream(b).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == TOX) return z.readBytes()
            }
        }
        return null
    }

    // ---------------- импорт ----------------

    /**
     * Импорт из файла: возвращает ключи профиля. Полный профиль читается потоком, проверяется
     * по описи и готовится во временной папке - разложится при перезапуске. Если файл повреждён,
     * возвращается [ImportResult.Failed], а данные приложения не меняются. Не в главном потоке.
     */
    fun importFrom(ctx: Context, uri: android.net.Uri, progress: Progress? = null): ImportResult {
        val cr = ctx.contentResolver
        val fail = ImportResult.Failed(ctx.getString(R.string.smsave_err_read))
        val total = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        val head = cr.openInputStream(uri)?.use { s -> readHead(s) } ?: return fail
        discardPending(ctx)
        if (head.size < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            // Старый формат: весь файл - ключи профиля.
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return fail
            return ImportResult.KeysOnly(bytes)
        }
        return cr.openInputStream(uri)?.use { s -> stageArchive(ctx, s, total, progress) } ?: fail
    }

    private fun readHead(s: InputStream): ByteArray {
        val b = ByteArray(2)
        var n = 0
        while (n < 2) {
            val r = s.read(b, n, 2 - n)
            if (r < 0) break
            n += r
        }
        return b.copyOf(n)
    }

    /** Прочитать, проверить и подготовить архив во временной папке. При любой ошибке папка удаляется. */
    private fun stageArchive(ctx: Context, input: InputStream, total: Long, progress: Progress?): ImportResult {
        val staged = stagedDir(ctx)
        staged.deleteRecursively()
        staged.mkdirs()
        val r = try {
            stage(ctx, input, staged, total, progress)
        } catch (e: Exception) {
            Log.w(TAG, "stage: $e")
            ImportResult.Failed(corrupt(ctx, e))
        }
        if (r !is ImportResult.Full) staged.deleteRecursively()
        return r
    }

    private fun stage(ctx: Context, input: InputStream, staged: File, total: Long, progress: Progress?): ImportResult {
        val stagedCanon = staged.canonicalPath + File.separator
        val s = scan(
            input, total, Stage.READ, progress,
            onHeader = { h ->
                // Хватит ли места: файлы распаковываются рядом с текущими, плюс запас.
                val need = h.optLong("bytes", 0L) + 32L * 1024 * 1024
                val free = runCatching { dataDir(ctx).usableSpace }.getOrDefault(Long.MAX_VALUE)
                if (free in 1 until need) throw Refused(ctx.getString(R.string.smsave_err_space, Formatter.formatShortFileSize(ctx, need - free)))
            },
        ) { name ->
            val ok = when {
                name.startsWith("databases/") -> name.indexOf('/', 10) < 0
                name.startsWith("shared_prefs/") -> name.indexOf('/', 13) < 0 && isUserPrefs(name.removePrefix("shared_prefs/"))
                name.startsWith("files/") -> !isSkippedFile(name.removePrefix("files/")) && name.removePrefix("files/").substringBefore('/') !in SKIP_DIRS
                else -> false
            }
            // Защита от путей вида ../ в архиве.
            File(staged, name).takeIf { ok && it.canonicalPath.startsWith(stagedCanon) }
        }
        checkScan(ctx, s)?.let { return ImportResult.Failed(it) }
        val tox = s.tox ?: return ImportResult.Failed(ctx.getString(R.string.smsave_err_no_keys))
        val manifest = s.manifest

        // Записи, которых нет в описи (не дочитались при экспорте), не раскладываем.
        manifest?.optJSONArray("entries")?.let { arr ->
            val listed = HashSet<String>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("name")?.let { listed += it }
            s.got.keys.filter { it !in listed }.map { File(staged, it) }
                .filter { it.canonicalPath.startsWith(stagedCanon) }.forEach { it.delete() }
        }
        // Индекс журнала (-shm) чужого телефона не нужен - SQLite построит свой.
        File(staged, "databases").listFiles()?.filter { it.name.endsWith("-shm") }?.forEach { it.delete() }

        // Профиль из более новой версии с другой схемой базы здесь не откроется.
        val theirsDb = File(staged, "databases/$CORE_DB")
        if (theirsDb.isFile) {
            val theirs = runCatching { compact(theirsDb, null); dbVersion(theirsDb, readOnly = false) }.getOrDefault(0)
            val mine = runCatching {
                ctx.getDatabasePath(CORE_DB).takeIf { it.isFile }?.let { dbVersion(it, readOnly = true) }
            }.getOrNull()
            val newerApp = (manifest ?: s.header)?.optInt("code", 0) ?: 0
            val tooNew = if (mine != null && mine > 0) theirs > mine else newerApp > BuildConfig.VERSION_CODE
            if (tooNew) {
                val app = (manifest ?: s.header)?.optString("app").orEmpty().ifBlank { "?" }
                return ImportResult.Failed(ctx.getString(R.string.smsave_err_newer, app))
            }
        }

        // Пути к файлам старого телефона (в настройках, описях и базе) - на пути этого.
        val pairs = pathPairs(ctx, manifest)
        if (pairs.isNotEmpty()) rewritePaths(staged, pairs)
        File(staged, "databases").listFiles()?.filter { it.isFile && !isDbSidecar(it.name) }?.forEach { db ->
            runCatching { compact(db, null) }.onFailure { Log.w(TAG, "compact ${db.name}: $it") }
        }

        val count = staged.walkTopDown().count { it.isFile }
        File(staged, READY).writeText(
            JSONObject()
                .put("version", manifest?.optInt("version", 2) ?: 2)
                .put("app", (manifest ?: s.header)?.optString("app").orEmpty())
                .put("files", count)
                .toString(),
        )
        return ImportResult.Full(tox, count)
    }

    private fun dbVersion(f: File, readOnly: Boolean): Int {
        val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        return SQLiteDatabase.openDatabase(f.path, null, flags).use { it.version }
    }

    /**
     * Пары «старый путь → новый». Из описи (пути старого телефона), плюс обычные варианты
     * (/data/data/…, /data/user/N/…) для файлов без описи. Только если путь отличается.
     */
    private fun pathPairs(ctx: Context, manifest: JSONObject?): List<Pair<String, String>> {
        val newData = dataDir(ctx).path.trimEnd('/')
        val pairs = LinkedHashMap<String, String>()
        fun add(old: String?, new: String) {
            val o = old?.trimEnd('/')
            if (o.isNullOrEmpty() || o == new.trimEnd('/') || !o.startsWith("/")) return
            pairs[o] = new.trimEnd('/')
        }
        if (manifest != null) {
            add(manifest.optString("filesDir"), ctx.filesDir.absolutePath)
            add(manifest.optString("dbDir"), dbDir(ctx).absolutePath)
            add(manifest.optString("dataDir"), newData)
        }
        val pkgs = linkedSetOf(manifest?.optString("package").orEmpty(), ctx.packageName, BASE_PACKAGE, "$BASE_PACKAGE.debug").filter { it.isNotEmpty() }
        for (p in pkgs) {
            add("/data/data/$p", newData)
            for (u in listOf(0, 10, 11, 95, 150, 999)) add("/data/user/$u/$p", newData)
        }
        // Длинные (более точные) - первыми.
        return pairs.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    /** Заменить пути в настройках, текстовых описях (json/xml/txt) и текстовых полях баз. */
    private fun rewritePaths(staged: File, pairs: List<Pair<String, String>>) {
        // org.json пишет «/» как «\/» - заменяем оба вида; «/» в конце - чтобы не задеть похожие имена.
        val all = pairs.flatMap { (o, n) -> listOf("$o/" to "$n/", o.replace("/", "\\/") + "\\/" to n.replace("/", "\\/") + "\\/") }
        val text = staged.walkTopDown().filter { f ->
            f.isFile && f.length() < 16L * 1024 * 1024 &&
                f.extension.lowercase() in setOf("xml", "json", "txt") &&
                !f.path.contains("${File.separator}databases${File.separator}")
        }
        for (f in text) runCatching {
            val old = f.readText()
            var t = old
            for ((o, n) in all) if (t.contains(o)) t = t.replace(o, n)
            if (t != old) {
                val m = f.lastModified()
                f.writeText(t)
                if (m > 0) f.setLastModified(m)
            }
        }.onFailure { Log.w(TAG, "paths ${f.name}: $it") }
        File(staged, "databases").listFiles()?.filter { it.isFile && !isDbSidecar(it.name) }?.forEach { db ->
            runCatching { rewriteDb(db, all) }.onFailure { Log.w(TAG, "paths ${db.name}: $it") }
        }
    }

    private fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""

    private fun rewriteDb(file: File, pairs: List<Pair<String, String>>) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            val tables = d.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata','room_master_table') AND sql NOT LIKE 'CREATE VIRTUAL%'",
                null,
            ).use { c -> buildList<String> { while (c.moveToNext()) add(c.getString(0)) } }
            d.beginTransaction()
            try {
                for (t in tables) {
                    val cols = d.rawQuery("PRAGMA table_info(${q(t)})", null).use { c ->
                        val ni = c.getColumnIndex("name")
                        val ti = c.getColumnIndex("type")
                        buildList<String> {
                            while (c.moveToNext()) {
                                val type = (if (ti >= 0) c.getString(ti) else null).orEmpty().uppercase()
                                if (type.isEmpty() || "CHAR" in type || "TEXT" in type || "CLOB" in type) add(c.getString(ni))
                            }
                        }
                    }
                    for (col in cols) for ((o, n) in pairs) {
                        d.execSQL(
                            "UPDATE ${q(t)} SET ${q(col)} = REPLACE(${q(col)}, ?, ?) WHERE typeof(${q(col)}) = 'text' AND instr(${q(col)}, ?) > 0",
                            arrayOf<Any>(o, n, o),
                        )
                    }
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /** Есть подготовленный профиль, который разложится при перезапуске. */
    fun hasPending(ctx: Context) = File(stagedDir(ctx), READY).isFile || File(ctx.filesDir, PENDING).isFile

    /** Отменить подготовленный импорт (ключи не подошли, пароль не введён). */
    fun discardPending(ctx: Context) {
        stagedDir(ctx).deleteRecursively()
        File(ctx.filesDir, PENDING).delete()
    }

    // ---------------- раскладка при запуске ----------------

    /**
     * Разложить подготовленный профиль по местам. Вызывается первым делом при запуске приложения,
     * пока база и настройки ещё не открыты: здесь только переносы файлов (быстро), вся проверка -
     * при импорте. Текущие файлы уходят в резервную папку; при сбое всё возвращается обратно.
     */
    fun applyPending(ctx: Context) {
        val staged = stagedDir(ctx)
        // Прошлый запуск оборвался посреди раскладки - вернуть всё, как было до неё.
        runCatching { recoverInterrupted(ctx) }.onFailure { Log.e(TAG, "recover: $it") }
        // Архив, отложенный прежней версией приложения: проверить и подготовить сейчас.
        val legacy = File(ctx.filesDir, PENDING)
        if (legacy.isFile) {
            if (!File(staged, READY).isFile) {
                val r = runCatching { legacy.inputStream().use { stageArchive(ctx, it, legacy.length(), null) } }
                    .getOrElse { ImportResult.Failed(it.toString()) }
                if (r is ImportResult.Failed) writeResult(ctx, "fail\t" + r.reason)
            }
            legacy.delete()
        }
        if (!File(staged, READY).isFile) {
            if (staged.exists()) staged.deleteRecursively()
            return
        }
        // Если раскладка раз за разом роняет приложение - не зацикливаться.
        val attempts = File(staged, ATTEMPTS)
        val n = runCatching { attempts.readText().trim().toInt() }.getOrDefault(0)
        if (n >= 2) {
            staged.deleteRecursively()
            writeResult(ctx, "fail\t" + ctx.getString(R.string.smsave_err_interrupted))
            return
        }
        runCatching { attempts.writeText((n + 1).toString()) }
        val result = runCatching { swap(ctx, staged) }
        result.onFailure { Log.e(TAG, "restore: $it") }
        staged.deleteRecursively()
        writeResult(
            ctx,
            result.fold({ "ok\t$it" }, { "fail\t" + (it.message ?: it.javaClass.simpleName) }),
        )
    }

    /** Один перенос: [target] до раскладки лежал в [saved] (null - его не было), новый взят из [src]. */
    private class Move(val target: File, val saved: File?, val src: File?)

    private fun swap(ctx: Context, staged: File): Int {
        val info = runCatching { JSONObject(File(staged, READY).readText()) }.getOrDefault(JSONObject())
        val backup = backupDir(ctx).apply {
            deleteRecursively()
            mkdirs()
        }
        val moves = ArrayList<Move>()
        var placed = 0
        FileOutputStream(File(backup, JOURNAL)).bufferedWriter().use { journal ->
            fun place(src: File?, target: File) {
                val saved = if (target.exists()) File(backup, moves.size.toString()) else null
                // Сначала - запись в журнал: если процесс оборвётся, recoverInterrupted всё вернёт.
                journal.write(target.path + "\t" + (saved?.path ?: "-") + "\t" + (src?.path ?: "-") + "\n")
                journal.flush()
                moves += Move(target, saved, src)
                if (saved != null && !target.renameTo(saved)) throw IOException("backup ${target.name}")
                if (src != null) {
                    target.parentFile?.mkdirs()
                    if (!src.renameTo(target)) src.copyTo(target, overwrite = true, bufferSize = BUF)
                    placed++
                }
            }
            try {
                // 1. Базы: прежняя база этого телефона (с журналами) уступает место базе из профиля.
                val dbDir = dbDir(ctx).apply { mkdirs() }
                val dbs = File(staged, "databases").listFiles()?.filter { it.isFile && !it.name.endsWith("-shm") && !it.name.endsWith("-journal") }.orEmpty()
                for (base in dbs.map { it.name.removeSuffix("-wal") }.toSortedSet()) {
                    dbDir.listFiles()?.filter { it.name == base || it.name.startsWith("$base-") }?.forEach { place(null, it) }
                }
                dbs.forEach { place(it, File(dbDir, it.name)) }

                // 2. Настройки. Профиль формата 3 переносится точно: настройки пользователя, которых
                // нет в профиле, убираются (кроме настроек телефона). Копию .bak Android поднял бы
                // вместо восстановленного файла - её убираем всегда.
                val prefsDir = prefsDir(ctx).apply { mkdirs() }
                val prefs = File(staged, "shared_prefs").listFiles()?.filter { it.isFile }.orEmpty()
                val names = prefs.map { it.name }.toSet()
                val exact = info.optInt("version", 2) >= 3
                prefsDir.listFiles()?.forEach { f ->
                    val xml = f.name.removeSuffix(".bak")
                    val stale = f.name.endsWith(".bak") && (xml in names || (exact && isUserPrefs(xml)))
                    val extra = exact && isUserPrefs(f.name) && f.name !in names
                    if (stale || extra) place(null, f)
                }
                prefs.forEach { place(it, File(prefsDir, it.name)) }

                // 3. Файлы приложения (поверх имеющихся).
                val filesRoot = File(staged, "files")
                if (filesRoot.isDirectory) {
                    filesRoot.walkTopDown().filter { it.isFile }.toList().forEach { f ->
                        place(f, File(ctx.filesDir, rel(filesRoot, f)))
                    }
                }
            } catch (e: Throwable) {
                rollback(moves)
                backup.deleteRecursively()
                throw e
            }
        }
        // Готово: метка переезжает в резервную папку одним переносом - если процесс оборвётся
        // дальше, recoverInterrupted не станет откатывать удачную раскладку.
        if (!File(staged, READY).renameTo(File(backup, DONE))) File(backup, DONE).writeText("ok")
        File(staged, READY).delete()
        backup.deleteRecursively()
        return if (placed > 0) placed else info.optInt("files", 0)
    }

    /** Вернуть всё, как было до раскладки (в обратном порядке). */
    private fun rollback(moves: List<Move>) {
        for (m in moves.asReversed()) runCatching {
            if (m.target.exists()) {
                // Новый файл - обратно в подготовленную папку (повторная раскладка его найдёт).
                val back = m.src
                if (back != null) {
                    back.parentFile?.mkdirs()
                    // Подготовленный файл уцелел (перенос был копированием) - лишнюю копию просто убрать.
                    if (back.exists() || !m.target.renameTo(back)) m.target.deleteRecursively()
                } else if (m.saved != null && m.saved.exists()) {
                    m.target.deleteRecursively()
                }
            }
            if (m.saved != null && m.saved.exists()) {
                m.target.parentFile?.mkdirs()
                if (!m.saved.renameTo(m.target)) m.saved.copyRecursively(m.target, overwrite = true)
            }
        }.onFailure { Log.e(TAG, "rollback ${m.target}: $it") }
    }

    private fun recoverInterrupted(ctx: Context) {
        val backup = backupDir(ctx)
        val journal = File(backup, JOURNAL)
        if (journal.isFile && !File(backup, DONE).exists()) {
            val moves = journal.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) return@mapNotNull null
                Move(File(p[0]), p[1].takeIf { it != "-" }?.let(::File), p[2].takeIf { it != "-" }?.let(::File))
            }
            rollback(moves)
            Log.w(TAG, "recovered ${moves.size} moves after interrupted restore")
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    private fun writeResult(ctx: Context, text: String) {
        runCatching { File(dataDir(ctx), RESULT).writeText(text) }
    }

    /**
     * Итог раскладки при этом запуске (для сообщения): текст или null, если импорта не было.
     * Читается один раз.
     */
    fun takeRestoreResult(ctx: Context): String? {
        val f = File(dataDir(ctx), RESULT)
        if (!f.isFile) return null
        val t = runCatching { f.readText() }.getOrNull()
        f.delete()
        if (t == null) return null
        val kind = t.substringBefore('\t')
        val arg = t.substringAfter('\t', "")
        return if (kind == "ok") {
            ctx.getString(R.string.smsave_restore_done, arg.toIntOrNull() ?: 0)
        } else {
            ctx.getString(R.string.smsave_restore_failed, arg)
        }
    }

    /** Сколько займут файлы из чатов (для вопроса «включить их?»). */
    fun mediaSize(ctx: Context): Long = MEDIA_DIRS.sumOf { d ->
        File(ctx.filesDir, d).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---------------- потоки ----------------

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            size += n
            if (size > MAX_SMALL) throw IOException("entry too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Копировать поток, считая размер и SHA-256 на лету (без чтения файла в память). */
    private fun copyHashing(input: InputStream, output: OutputStream, buf: ByteArray, onBytes: (Int) -> Unit): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
            size += n
            onBytes(n)
        }
        return size to hex(md.digest())
    }

    private fun sha256(data: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
