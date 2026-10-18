// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.files

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/*
 * Архиватор файлового менеджера (без сторонних библиотек, только java.util.zip):
 *  - ZIP: чтение центрального каталога (в т.ч. ZIP64, архивы с «приклеенным» началом - SFX),
 *    имена UTF-8 / CP866 (архивы из Windows), методы «без сжатия» и Deflate;
 *  - шифрование ZipCrypto (классический пароль PKWARE) - и чтение, и запись;
 *  - создание, добавление файлов, удаление и переименование внутри архива
 *    (архив переписывается во временный файл - сжатые данные копируются как есть, без пересжатия);
 *  - проверка целостности (CRC32), защита от «zip-slip» (пути вида ../) и «zip-бомб»;
 *  - только чтение: TAR, TAR.GZ / TGZ, одиночный GZ. RAR и 7z - понятная ошибка.
 * Здесь нет ничего от Android - логику можно проверять обычными тестами на JVM.
 */

/** Формат открытого архива. */
enum class ArcFormat { ZIP, TAR, TAR_GZ, GZ }

/** Уровень сжатия ZIP (как в WinRAR: без сжатия / быстрое / обычное / максимальное). */
enum class ArcLevel(val deflate: Int) { STORE(0), FAST(1), NORMAL(6), MAX(9) }

/** Что делать, если файл уже есть в папке назначения. */
enum class Overwrite { REPLACE, SKIP, REPLACE_ALL, SKIP_ALL, CANCEL }

/** Прогресс долгой операции; вызывается из фонового потока. */
interface ArcProgress {
    /** Обработано [done] байт из [total], сейчас - [name]. */
    fun update(done: Long, total: Long, name: String)

    /** Пользователь нажал «Отмена». */
    val cancelled: Boolean
}

/** Ошибки архиватора (у каждой - своё понятное сообщение в интерфейсе). */
open class ArcException(message: String) : IOException(message)

/** Неверный пароль (или пароль не указан для зашифрованного файла). */
class WrongPasswordException : ArcException("wrong password")

/** Контрольная сумма не сошлась - файл в архиве повреждён (или пароль неверный). */
class CrcException(val name: String) : ArcException("CRC error: $name")

/** Формат или метод сжатия не поддерживается: "RAR", "7z", "AES", "method 12" и т.п. */
class UnsupportedArcException(val kind: String) : ArcException("unsupported: $kind")

/** Подозрение на «zip-бомбу»: распакованные данные больше заявленного или слишком сильное сжатие. */
class ZipBombException(message: String) : ArcException(message)

/** Не хватает места в папке назначения. */
class NoSpaceException(val need: Long, val free: Long) : ArcException("no space")

/** Операция отменена пользователем. */
class ArcCancelledException : ArcException("cancelled")

/** Файл не похож на архив или повреждён. */
class BadArchiveException(message: String) : ArcException(message)

/**
 * Запись в архиве. [path] - нормализованный путь без ведущего и завершающего «/».
 * [crc] = -1, если неизвестна (TAR), [size] = -1, если неизвестен (GZ).
 */
class ArcEntry internal constructor(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val csize: Long,
    val time: Long,
    val crc: Long,
    val encrypted: Boolean,
    val method: Int,
    internal val flags: Int,
    internal val dosTime: Long,
    internal val offset: Long,
    internal val rawName: ByteArray,
) {
    /** Имя без папок. */
    val name: String get() = path.substringAfterLast('/')

    /** Путь содержит «..» - такую запись нельзя распаковывать (zip-slip). */
    val unsafe: Boolean get() = path.split('/').any { it == ".." }

    /** Название метода сжатия для «Свойств». */
    val methodName: String
        get() = when (method) {
            0 -> "Store"
            8 -> "Deflate"
            9 -> "Deflate64"
            12 -> "BZip2"
            14 -> "LZMA"
            93 -> "Zstandard"
            95 -> "XZ"
            99 -> "AES"
            -1 -> "-"
            else -> "#$method"
        }
}

/** Элемент списка при просмотре архива «как папки»: файл или (возможно, неявная) папка. */
class ArcNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val entry: ArcEntry?,
    val size: Long,
    val csize: Long,
    val time: Long,
    val encrypted: Boolean,
    val files: Int,
)

/** Итог распаковки. */
class ExtractResult(val extracted: Int, val skipped: Int, val blocked: List<String>)

/** Итог проверки архива: сколько файлов проверено и ошибки (путь → причина). */
class TestReport(val tested: Int, val errors: List<Pair<String, Throwable>>)

/** Открытый (прочитанный) архив: список записей и чтение данных. */
class Archive private constructor(
    val file: File,
    val format: ArcFormat,
    val entries: List<ArcEntry>,
    internal val commentBytes: ByteArray,
) {
    /** Комментарий архива (ZIP). */
    val comment: String get() = decodeName(commentBytes, false)

    /** Можно ли менять архив (добавлять, удалять, переименовывать) - только ZIP. */
    val writable: Boolean get() = format == ArcFormat.ZIP

    val encrypted: Boolean get() = entries.any { it.encrypted }
    val fileCount: Int get() = entries.count { !it.isDir }
    val totalSize: Long get() = entries.filter { !it.isDir }.sumOf { it.size.coerceAtLeast(0) }
    val packedSize: Long get() = entries.filter { !it.isDir }.sumOf { it.csize.coerceAtLeast(0) }

    /** Содержимое «папки» [prefix] внутри архива ("" - корень, иначе "a/b"): папки сверху. */
    fun children(prefix: String): List<ArcNode> {
        val pre = if (prefix.isEmpty()) "" else "$prefix/"
        val dirs = LinkedHashMap<String, LongArray>() // size, csize, time, files, encrypted
        val explicitDirs = HashMap<String, ArcEntry>()
        val files = ArrayList<ArcNode>()
        for (e in entries) {
            if (!e.path.startsWith(pre) || e.path.length == pre.length) continue
            val rest = e.path.substring(pre.length)
            val slash = rest.indexOf('/')
            if (slash < 0 && !e.isDir) {
                files += ArcNode(rest, e.path, false, e, e.size, e.csize, e.time, e.encrypted, 1)
                continue
            }
            val dn = if (slash < 0) rest else rest.substring(0, slash)
            val acc = dirs.getOrPut(dn) { LongArray(5) }
            if (slash < 0) {
                explicitDirs[dn] = e
                acc[2] = maxOf(acc[2], e.time)
            } else if (!e.isDir) {
                acc[0] += e.size.coerceAtLeast(0)
                acc[1] += e.csize.coerceAtLeast(0)
                acc[2] = maxOf(acc[2], e.time)
                acc[3]++
                if (e.encrypted) acc[4] = 1
            }
        }
        val dirNodes = dirs.map { (n, a) ->
            ArcNode(n, pre + n, true, explicitDirs[n], a[0], a[1], a[2], a[4] == 1L, a[3].toInt())
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return dirNodes + files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Все записи с путём [path] или внутри папки [path]. */
    fun under(path: String): List<ArcEntry> =
        entries.filter { it.path == path || it.path.startsWith("$path/") }

    /** Есть ли запись (или неявная папка) с таким путём. */
    fun exists(path: String): Boolean = entries.any { it.path == path || it.path.startsWith("$path/") }

    /**
     * Поток с распакованными данными записи (CRC и размер проверяются при чтении до конца).
     * Для зашифрованных - [password]; неверный - [WrongPasswordException].
     */
    fun open(e: ArcEntry, password: String?): InputStream {
        if (e.isDir) return java.io.ByteArrayInputStream(ByteArray(0))
        return when (format) {
            ArcFormat.ZIP -> openZip(e, password)
            ArcFormat.GZ -> CheckIn(GZIPInputStream(FileInputStream(file), BUF), e.path, -1, -1, e.csize)
            ArcFormat.TAR, ArcFormat.TAR_GZ -> {
                val raw = CountIn(rawTar())
                try {
                    skipFully(raw, e.offset)
                } catch (t: Throwable) {
                    raw.close()
                    throw t
                }
                CheckIn(Slice(raw, e.size, closeSource = true), e.path, -1, e.size, e.csize)
            }
        }
    }

    /** Подходит ли пароль: пробуем открыть первый зашифрованный файл из [list]. */
    fun checkPassword(list: List<ArcEntry>, password: String?) {
        val e = list.firstOrNull { it.encrypted && !it.isDir } ?: return
        open(e, password).close()
    }

    private fun rawTar(): InputStream {
        val fin = FileInputStream(file)
        return if (format == ArcFormat.TAR_GZ) GZIPInputStream(fin, BUF) else fin.buffered(BUF)
    }

    private fun openZip(e: ArcEntry, password: String?): InputStream {
        if (e.method == 99) throw UnsupportedArcException("AES")
        if (e.method != 0 && e.method != 8) throw UnsupportedArcException(e.methodName)
        if (e.flags and 0x40 != 0) throw UnsupportedArcException("Strong encryption")
        val raf = RandomAccessFile(file, "r")
        try {
            val start = localDataStart(raf, e)
            var src: InputStream = RafSlice(raf, start, e.csize)
            var csize = e.csize
            if (e.encrypted) {
                if (password == null) throw WrongPasswordException()
                val head = ByteArray(12)
                readFully(src, head)
                val check = if (e.flags and 8 != 0) ((e.dosTime ushr 8) and 0xFF).toInt() else ((e.crc ushr 24) and 0xFF).toInt()
                var crypto: ZipCrypto? = null
                for (pw in passwordVariants(password)) {
                    val c = ZipCrypto(pw)
                    val h = head.copyOf()
                    c.decrypt(h, 0, h.size)
                    if ((h[11].toInt() and 0xFF) == check) {
                        crypto = c
                        break
                    }
                }
                if (crypto == null) throw WrongPasswordException()
                src = DecryptIn(src, crypto)
                csize -= 12
            }
            val data = if (e.method == 8) InflateIn(src.buffered(BUF)) else src.buffered(BUF)
            return CheckIn(data, e.path, e.crc, e.size, csize)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    /**
     * Перебрать данные файлов [list] по порядку (TAR - за один проход по потоку).
     * [action] может прочитать поток не до конца.
     */
    fun forEachData(list: List<ArcEntry>, password: String?, p: ArcProgress?, action: (ArcEntry, InputStream) -> Unit) {
        val files = list.filter { !it.isDir }
        if (format == ArcFormat.TAR || format == ArcFormat.TAR_GZ) {
            CountIn(rawTar()).use { raw ->
                for (e in files.sortedBy { it.offset }) {
                    if (p?.cancelled == true) throw ArcCancelledException()
                    if (raw.count > e.offset) throw BadArchiveException("tar order")
                    skipFully(raw, e.offset - raw.count)
                    val s = Slice(raw, e.size, closeSource = false)
                    action(e, CheckIn(s, e.path, -1, e.size, e.csize))
                    s.drain()
                }
            }
            return
        }
        for (e in files) {
            if (p?.cancelled == true) throw ArcCancelledException()
            open(e, password).use { action(e, it) }
        }
    }

    companion object {
        internal const val BUF = 64 * 1024

        /** Не больше стольких записей (защита от архивов-ловушек и нехватки памяти). */
        const val MAX_ENTRIES = 300_000

        /** Сжатие сильнее этого (и файл больше [BOMB_MIN_SIZE]) - похоже на «zip-бомбу». */
        const val BOMB_RATIO = 1000L
        const val BOMB_MIN_SIZE = 256L * 1024 * 1024

        private val ZIP_EXT = setOf("zip", "jar", "apk", "apks", "xapk", "aab", "aar", "cbz", "epub", "docx", "xlsx", "pptx", "odt", "ods", "odp", "war", "ipa", "zipx")
        private val OTHER_EXT = setOf("tar", "tgz", "gz", "rar", "7z", "cbr", "cb7", "bz2", "tbz2", "xz", "txz", "lzma", "zst", "cab", "arj", "lzh")

        /** Похоже ли имя файла на архив (для меню и иконок). */
        fun isArchiveName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in ZIP_EXT || ext in OTHER_EXT
        }

        /** Архивы, которые лучше сначала предложить открыть обычным приложением (установка, книга, документ). */
        fun isContainerName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("apk", "apks", "xapk", "jar", "aar", "aab", "epub", "docx", "xlsx", "pptx", "odt", "ods", "odp", "cbz", "ipa")

        /** Прочитать архив (долго для больших TAR.GZ - вызывать в фоне). */
        fun open(file: File, p: ArcProgress? = null): Archive {
            val name = file.name.lowercase()
            val magic = ByteArray(8)
            val n = FileInputStream(file).use { readUpTo(it, magic) }
            fun m(vararg b: Int) = n >= b.size && b.indices.all { (magic[it].toInt() and 0xFF) == b[it] }
            when {
                m(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> throw UnsupportedArcException("RAR")
                m(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> throw UnsupportedArcException("7z")
                m(0x50, 0x4B, 0x03, 0x04) || m(0x50, 0x4B, 0x05, 0x06) || m(0x50, 0x4B, 0x07, 0x08) -> return readZip(file)
                m(0x1F, 0x8B) -> {
                    val isTar = name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar.gzip") ||
                        runCatching {
                            GZIPInputStream(FileInputStream(file)).use { g ->
                                val h = ByteArray(512)
                                readUpTo(g, h) == 512 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar"
                            }
                        }.getOrDefault(false)
                    return if (isTar) readTar(file, ArcFormat.TAR_GZ, p) else readGz(file)
                }
                m(0x42, 0x5A, 0x68) -> throw UnsupportedArcException("BZip2")
                m(0xFD, 0x37, 0x7A, 0x58, 0x5A) -> throw UnsupportedArcException("XZ")
                m(0x28, 0xB5, 0x2F, 0xFD) -> throw UnsupportedArcException("Zstandard")
                m(0x4D, 0x53, 0x43, 0x46) -> throw UnsupportedArcException("CAB")
            }
            val ustar = runCatching {
                RandomAccessFile(file, "r").use { r ->
                    val h = ByteArray(5)
                    r.length() >= 512 && run { r.seek(257); r.readFully(h); String(h, Charsets.ISO_8859_1) == "ustar" }
                }
            }.getOrDefault(false)
            if (ustar || name.endsWith(".tar")) return readTar(file, ArcFormat.TAR, p)
            if (name.endsWith(".rar") || name.endsWith(".cbr")) throw UnsupportedArcException("RAR")
            if (name.endsWith(".7z") || name.endsWith(".cb7")) throw UnsupportedArcException("7z")
            // Может быть SFX (exe с архивом в конце) или ZIP с мусором в начале - ищем каталог с конца.
            return readZip(file)
        }

        // ---------------- ZIP: чтение ----------------

        private fun readZip(file: File): Archive = RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 22) throw BadArchiveException("too small")
            val tailLen = minOf(len, 65_557L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail.u32(i) == 0x06054b50L) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw BadArchiveException("no central directory")
            val eocdPos = len - tailLen + eocd
            var count = tail.u16(eocd + 10).toLong()
            var cdSize = tail.u32(eocd + 12)
            var cdOffset = tail.u32(eocd + 16)
            val commentLen = tail.u16(eocd + 20)
            val comment = tail.copyOfRange(eocd + 22, minOf(tailLen, eocd + 22 + commentLen))
            var zip64 = false
            if (eocdPos >= 20) {
                val loc = ByteArray(20)
                raf.seek(eocdPos - 20)
                raf.readFully(loc)
                if (loc.u32(0) == 0x07064b50L) {
                    val z64pos = loc.u64(8)
                    if (z64pos in 0..(eocdPos - 56)) {
                        val z = ByteArray(56)
                        raf.seek(z64pos)
                        raf.readFully(z)
                        if (z.u32(0) == 0x06064b50L) {
                            zip64 = true
                            count = z.u64(32)
                            cdSize = z.u64(40)
                            cdOffset = z.u64(48)
                        }
                    }
                }
            }
            // Данные перед архивом (SFX): каталог стоит не там, где записано, - сдвигаем смещения.
            val shift = if (!zip64) (eocdPos - (cdOffset + cdSize)).coerceAtLeast(0) else 0L
            if (count > MAX_ENTRIES) throw ZipBombException("too many entries: $count")
            if (cdSize > 256L * 1024 * 1024 || cdOffset + shift + cdSize > len) throw BadArchiveException("bad central directory")
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset + shift)
            raf.readFully(cd)
            val out = ArrayList<ArcEntry>(count.toInt())
            var i = 0
            while (i + 46 <= cd.size && cd.u32(i) == 0x02014b50L) {
                val flags = cd.u16(i + 8)
                val method = cd.u16(i + 10)
                val dos = cd.u32(i + 12)
                val crc = cd.u32(i + 16)
                var csize = cd.u32(i + 20)
                var size = cd.u32(i + 24)
                val nLen = cd.u16(i + 28)
                val xLen = cd.u16(i + 30)
                val cLen = cd.u16(i + 32)
                var offset = cd.u32(i + 42)
                if (i + 46 + nLen + xLen > cd.size) throw BadArchiveException("truncated central directory")
                val raw = cd.copyOfRange(i + 46, i + 46 + nLen)
                var nameStr = decodeName(raw, flags and 0x800 != 0)
                var time = fromDos(dos)
                // Дополнительные поля: ZIP64-размеры, точное время, Unicode-имя (Info-ZIP).
                var x = i + 46 + nLen
                val xEnd = x + xLen
                while (x + 4 <= xEnd) {
                    val id = cd.u16(x)
                    val sz = cd.u16(x + 2)
                    val d = x + 4
                    if (d + sz > xEnd) break
                    when (id) {
                        0x0001 -> {
                            var p = d
                            if (size == 0xFFFFFFFFL && p + 8 <= d + sz) { size = cd.u64(p); p += 8 }
                            if (csize == 0xFFFFFFFFL && p + 8 <= d + sz) { csize = cd.u64(p); p += 8 }
                            if (offset == 0xFFFFFFFFL && p + 8 <= d + sz) { offset = cd.u64(p) }
                        }
                        0x5455 -> if (sz >= 5 && cd[d].toInt() and 1 != 0) time = cd.u32(d + 1) * 1000L
                        0x7075 -> if (sz > 5 && cd[d].toInt() == 1) {
                            nameStr = String(cd, d + 5, sz - 5, Charsets.UTF_8)
                        }
                    }
                    x = d + sz
                }
                val norm = normalize(nameStr)
                val isDir = nameStr.endsWith("/") || nameStr.endsWith("\\")
                if (norm.isNotEmpty()) {
                    out += ArcEntry(
                        norm, isDir, if (isDir) 0 else size, if (isDir) 0 else csize, time, crc,
                        flags and 1 != 0, method, flags, dos, offset + shift, raw,
                    )
                }
                if (out.size > MAX_ENTRIES) throw ZipBombException("too many entries")
                i += 46 + nLen + xLen + cLen
            }
            if (out.isEmpty() && count > 0) throw BadArchiveException("empty central directory")
            Archive(file, ArcFormat.ZIP, out, comment)
        }

        /** Где начинаются данные записи: после локального заголовка (его длина может отличаться от центрального). */
        internal fun localDataStart(raf: RandomAccessFile, e: ArcEntry): Long {
            val h = ByteArray(30)
            raf.seek(e.offset)
            raf.readFully(h)
            if (h.u32(0) != 0x04034b50L) throw BadArchiveException("bad local header: ${e.path}")
            return e.offset + 30 + h.u16(26) + h.u16(28)
        }

        // ---------------- TAR / GZ: чтение ----------------

        private fun readTar(file: File, format: ArcFormat, p: ArcProgress?): Archive {
            val out = ArrayList<ArcEntry>()
            val fin = CountIn(FileInputStream(file))
            val total = file.length()
            val src = CountIn(if (format == ArcFormat.TAR_GZ) GZIPInputStream(fin, BUF) else fin.buffered(BUF))
            src.use {
                val h = ByteArray(512)
                var longName: String? = null
                var paxPath: String? = null
                var paxSize: Long? = null
                var zeros = 0
                while (true) {
                    if (p?.cancelled == true) throw ArcCancelledException()
                    p?.update(fin.count, total, file.name)
                    val r = readUpTo(src, h)
                    if (r == 0) break
                    if (r < 512) throw BadArchiveException("truncated tar")
                    if (h.all { it.toInt() == 0 }) {
                        if (++zeros >= 2) break
                        continue
                    }
                    zeros = 0
                    if (!tarChecksumOk(h)) throw BadArchiveException("tar checksum")
                    val type = h[156].toInt().toChar()
                    val size = paxSize ?: tarNumber(h, 124, 12)
                    if (size < 0) throw BadArchiveException("tar size")
                    val padded = (size + 511) / 512 * 512
                    val dataPos = src.count
                    when (type) {
                        'L' -> {
                            longName = String(readBytes(src, size), Charsets.UTF_8).trimEnd('\u0000')
                            skipFully(src, padded - size)
                            continue
                        }
                        'x' -> {
                            val pax = String(readBytes(src, size), Charsets.UTF_8)
                            skipFully(src, padded - size)
                            for (line in pax.split('\n')) {
                                val kv = line.substringAfter(' ', "")
                                when {
                                    kv.startsWith("path=") -> paxPath = kv.removePrefix("path=")
                                    kv.startsWith("size=") -> paxSize = kv.removePrefix("size=").toLongOrNull()
                                }
                            }
                            continue
                        }
                        'g', 'K' -> {
                            skipFully(src, padded)
                            continue
                        }
                    }
                    var name = paxPath ?: longName ?: run {
                        val base = cString(h, 0, 100)
                        val magic = String(h, 257, 5, Charsets.ISO_8859_1)
                        val prefix = if (magic == "ustar") cString(h, 345, 155) else ""
                        if (prefix.isNotEmpty()) "$prefix/$base" else base
                    }
                    longName = null
                    paxPath = null
                    paxSize = null
                    val isDir = type == '5' || name.endsWith("/")
                    val isFile = type == '0' || type == '\u0000' || type == '7'
                    if (name.startsWith("./")) name = name.substring(2)
                    val norm = normalize(name)
                    if (norm.isNotEmpty() && (isDir || isFile)) {
                        out += ArcEntry(
                            norm, isDir, if (isDir) 0 else size, if (isDir) 0 else size,
                            tarNumber(h, 136, 12) * 1000L, -1, false, -1, 0, 0, dataPos, ByteArray(0),
                        )
                        if (out.size > MAX_ENTRIES) throw ZipBombException("too many entries")
                    }
                    skipFully(src, padded)
                }
            }
            return Archive(file, format, out, ByteArray(0))
        }

        private fun readGz(file: File): Archive {
            val len = file.length()
            // Размер распакованного - в последних 4 байтах (по модулю 4 ГБ, поэтому только для показа).
            val isize = RandomAccessFile(file, "r").use { r ->
                if (len < 18) -1L else {
                    val b = ByteArray(4)
                    r.seek(len - 4)
                    r.readFully(b)
                    b.u32(0)
                }
            }
            val inner = file.name.let { n ->
                when {
                    n.endsWith(".gz", true) -> n.dropLast(3)
                    n.endsWith(".gzip", true) -> n.dropLast(5)
                    else -> "$n.out"
                }
            }.ifBlank { "data" }
            val e = ArcEntry(normalize(inner), false, isize, len, file.lastModified(), -1, false, 8, 0, 0, 0, ByteArray(0))
            return Archive(file, ArcFormat.GZ, listOf(e), ByteArray(0))
        }

        private fun tarChecksumOk(h: ByteArray): Boolean {
            val stored = tarNumber(h, 148, 8)
            var sum = 0L
            for (i in 0 until 512) sum += if (i in 148..155) 32 else (h[i].toInt() and 0xFF)
            return stored == sum || stored == -1L
        }

        /** Число в заголовке TAR: восьмеричное или двоичное (base-256) для больших файлов. */
        private fun tarNumber(h: ByteArray, off: Int, len: Int): Long {
            if (h[off].toInt() and 0x80 != 0) {
                var v = 0L
                for (i in 1 until len) v = (v shl 8) or (h[off + i].toLong() and 0xFF)
                return v
            }
            val s = String(h, off, len, Charsets.ISO_8859_1).trim { it == ' ' || it == '\u0000' }
            if (s.isEmpty()) return 0
            return s.toLongOrNull(8) ?: -1
        }

        private fun cString(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end].toInt() != 0) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        // ---------------- распаковка и проверка ----------------

        /**
         * Распаковать [list] (записи, в т.ч. папки) в [dest]. У путей отрезается [stripPrefix]
         * ("a/b/" - чтобы выбранное внутри подпапки легло прямо в [dest]).
         * Пути, выходящие за [dest] (../, абсолютные), не распаковываются - попадают в blocked.
         * [ask] спрашивает про существующий файл (вызывается из фонового потока).
         */
        fun extract(
            arc: Archive,
            list: List<ArcEntry>,
            dest: File,
            stripPrefix: String,
            password: String?,
            ask: (File) -> Overwrite,
            p: ArcProgress,
        ): ExtractResult {
            if (!dest.exists() && !dest.mkdirs()) throw IOException("mkdirs: $dest")
            val root = dest.canonicalFile
            val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
            val blocked = ArrayList<String>()
            fun target(e: ArcEntry): File? {
                val rel = e.path.removePrefix(stripPrefix)
                if (rel.isEmpty()) return null
                if (e.unsafe || rel.startsWith("/") || rel.contains('\u0000') || rel.contains(":")) {
                    blocked += e.path
                    return null
                }
                val t = File(root, rel)
                val canon = runCatching { t.canonicalPath }.getOrNull()
                if (canon == null || !canon.startsWith(rootPath)) {
                    blocked += e.path
                    return null
                }
                return t
            }
            val files = list.filter { !it.isDir }
            // Защита от «бомб»: заявленные размеры проверяются при чтении (больше - ошибка),
            // слишком сильное сжатие больших файлов - отказ, всё должно влезть на диск.
            for (e in files) {
                if (e.size > BOMB_MIN_SIZE && e.csize > 0 && e.size / e.csize > BOMB_RATIO) {
                    throw ZipBombException("ratio: ${e.path}")
                }
            }
            val need = files.sumOf { it.size.coerceAtLeast(0) }
            val free = root.usableSpace
            if (free in 1 until need) throw NoSpaceException(need, free)
            for (e in list) if (e.isDir) target(e)?.mkdirs()
            var done = 0L
            var extracted = 0
            var skipped = 0
            var mode: Overwrite? = null
            val buf = ByteArray(BUF)
            arc.forEachData(files, password, p) { e, input ->
                val t = target(e) ?: return@forEachData
                p.update(done, need, e.path)
                if (t.isDirectory) {
                    skipped++
                    done += e.size.coerceAtLeast(0)
                    return@forEachData
                }
                if (t.exists()) {
                    val a = mode ?: ask(t)
                    when (a) {
                        Overwrite.CANCEL -> throw ArcCancelledException()
                        Overwrite.REPLACE_ALL -> mode = Overwrite.REPLACE
                        Overwrite.SKIP_ALL -> mode = Overwrite.SKIP
                        else -> Unit
                    }
                    if (a == Overwrite.SKIP || a == Overwrite.SKIP_ALL) {
                        skipped++
                        done += e.size.coerceAtLeast(0)
                        return@forEachData
                    }
                }
                t.parentFile?.mkdirs()
                val tmp = File(t.parentFile, ".${t.name}.arc-part")
                try {
                    tmp.outputStream().use { out ->
                        while (true) {
                            if (p.cancelled) throw ArcCancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            p.update(done, need, e.path)
                        }
                    }
                    if (t.exists() && !t.delete()) throw IOException("delete: $t")
                    if (!tmp.renameTo(t)) {
                        tmp.copyTo(t, overwrite = true)
                        tmp.delete()
                    }
                    if (e.time > 0) t.setLastModified(e.time)
                    extracted++
                } catch (x: Throwable) {
                    tmp.delete()
                    throw x
                }
            }
            return ExtractResult(extracted, skipped, blocked)
        }

        /** Проверить архив: распаковать всё «в никуда» и сверить CRC/размеры. */
        fun test(arc: Archive, password: String?, p: ArcProgress): TestReport {
            val files = arc.entries.filter { !it.isDir }
            val total = files.sumOf { it.size.coerceAtLeast(0) }
            val errors = ArrayList<Pair<String, Throwable>>()
            arc.entries.filter { it.unsafe }.forEach { errors += it.path to BadArchiveException("unsafe path") }
            var done = 0L
            var tested = 0
            val buf = ByteArray(BUF)
            fun drain(e: ArcEntry, input: InputStream) {
                while (true) {
                    if (p.cancelled) throw ArcCancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    done += n
                    p.update(done, total, e.path)
                }
                tested++
            }
            if (arc.format == ArcFormat.ZIP) {
                for (e in files) {
                    if (p.cancelled) throw ArcCancelledException()
                    try {
                        arc.open(e, password).use { drain(e, it) }
                    } catch (c: ArcCancelledException) {
                        throw c
                    } catch (t: Throwable) {
                        errors += e.path to t
                    }
                }
            } else {
                // Поток TAR.GZ после ошибки дальше не читается - останавливаемся на первой.
                try {
                    arc.forEachData(files, password, p) { e, input -> drain(e, input) }
                } catch (c: ArcCancelledException) {
                    throw c
                } catch (t: Throwable) {
                    errors += (files.getOrNull(tested)?.path ?: arc.file.name) to t
                }
            }
            return TestReport(tested, errors)
        }

        // ---------------- ZIP: создание и изменение ----------------

        /** Развернуть файлы и папки в список (файл → путь в архиве; у папок путь заканчивается «/»). */
        fun expand(sources: List<File>, prefix: String, skip: File?): List<Pair<File, String>> {
            val out = ArrayList<Pair<File, String>>()
            val skipPath = skip?.let { runCatching { it.canonicalPath }.getOrNull() }
            val pre = if (prefix.isEmpty()) "" else prefix.trimEnd('/') + "/"
            fun walk(f: File, path: String, depth: Int) {
                if (out.size > MAX_ENTRIES || depth > 64) return
                if (skipPath != null && runCatching { f.canonicalPath }.getOrNull() == skipPath) return
                if (f.isDirectory) {
                    out += f to "$path/"
                    f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, path + "/" + it.name, depth + 1) }
                } else if (f.isFile) {
                    out += f to path
                }
            }
            sources.forEach { walk(it, pre + it.name, 0) }
            return out
        }

        /**
         * Создать ZIP [target] из [sources] (папки - со всей структурой).
         * Пишем во временный файл рядом - при ошибке или отмене недоделанный архив не остаётся.
         */
        fun create(target: File, sources: List<File>, level: ArcLevel, password: String?, p: ArcProgress) {
            val items = expand(sources, "", target)
            val tmp = File(target.parentFile, ".${target.name}.arc-tmp")
            try {
                ZipWriter(tmp).use { w ->
                    val total = items.sumOf { (f, path) -> if (path.endsWith("/")) 0L else f.length() * (if (password != null) 2 else 1) }
                    val counter = Counter(p, total)
                    for ((f, path) in items) addItem(w, f, path, level, password, counter)
                    w.finish(ByteArray(0))
                }
                replaceWith(tmp, target)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

        /**
         * Переписать ZIP [arc]: убрать записи [drop], переименовать по [rename] (null - как было),
         * дописать [add] (файл → путь в архиве; записи с такими же путями заменяются).
         * Сжатые данные старых записей копируются байт в байт (пароль не нужен).
         */
        fun rewrite(
            arc: Archive,
            drop: (ArcEntry) -> Boolean,
            rename: (ArcEntry) -> String?,
            add: List<Pair<File, String>>,
            level: ArcLevel,
            password: String?,
            p: ArcProgress,
        ) {
            if (!arc.writable) throw UnsupportedArcException(arc.format.name)
            val target = arc.file
            val tmp = File(target.parentFile, ".${target.name}.arc-tmp")
            val addPaths = add.map { it.second.trimEnd('/') }.toHashSet()
            val keep = arc.entries.filter { !drop(it) && it.path !in addPaths }
            val addFiltered = add.filter { (f, _) -> runCatching { f.canonicalPath != target.canonicalPath }.getOrDefault(true) }
            try {
                RandomAccessFile(target, "r").use { src ->
                    ZipWriter(tmp).use { w ->
                        val total = keep.sumOf { it.csize.coerceAtLeast(0) } +
                            addFiltered.sumOf { (f, path) -> if (path.endsWith("/")) 0L else f.length() * (if (password != null) 2 else 1) }
                        val counter = Counter(p, total)
                        for (e in keep) {
                            val newName = rename(e)
                            w.copyRaw(src, e, newName, counter)
                        }
                        for ((f, path) in addFiltered) addItem(w, f, path, level, password, counter)
                        w.finish(arc.commentBytes)
                    }
                }
                replaceWith(tmp, target)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

        private fun replaceWith(tmp: File, target: File) {
            if (target.exists() && !target.delete()) throw IOException("delete: $target")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }

        private fun addItem(w: ZipWriter, f: File, path: String, level: ArcLevel, password: String?, c: Counter) {
            if (c.p.cancelled) throw ArcCancelledException()
            if (path.endsWith("/")) w.addDir(path, f.lastModified()) else w.addFile(f, path, level, password, c)
        }

        // ---------------- служебное ----------------

        /** Путь в архиве: «/» вместо «\», без «./», пустых частей, ведущего «/» и буквы диска. */
        internal fun normalize(name: String): String {
            var s = name.replace('\\', '/')
            if (s.length >= 2 && s[1] == ':' && s[0].isLetter()) s = s.substring(2)
            return s.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        }

        private val CP866: Charset = runCatching { Charset.forName("IBM866") }.getOrDefault(Charsets.ISO_8859_1)
        private val CP1251: Charset = runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.ISO_8859_1)

        /** Имя из ZIP: UTF-8 (флаг 11 или если байты - корректный UTF-8), иначе CP866 (архивы Windows). */
        internal fun decodeName(raw: ByteArray, utf8: Boolean): String {
            if (utf8 || raw.all { it >= 0 }) return String(raw, Charsets.UTF_8)
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return runCatching { dec.decode(ByteBuffer.wrap(raw)).toString() }.getOrElse { String(raw, CP866) }
        }

        /** Пароль в байтах: UTF-8, а для кириллицы ещё CP866 и CP1251 (так шифруют старые архиваторы). */
        private fun passwordVariants(pw: String): List<ByteArray> {
            val v = arrayListOf(pw.toByteArray(Charsets.UTF_8))
            if (pw.any { it.code > 127 }) {
                v += pw.toByteArray(CP866)
                v += pw.toByteArray(CP1251)
            }
            return v
        }

        internal fun toDos(t: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val y = c.get(Calendar.YEAR)
            if (y < 1980) return 0x00210000L // 01.01.1980
            return ((minOf(y, 2107) - 1980).toLong() shl 25) or
                ((c.get(Calendar.MONTH) + 1).toLong() shl 21) or
                (c.get(Calendar.DAY_OF_MONTH).toLong() shl 16) or
                (c.get(Calendar.HOUR_OF_DAY).toLong() shl 11) or
                (c.get(Calendar.MINUTE).toLong() shl 5) or
                (c.get(Calendar.SECOND).toLong() shr 1)
        }

        internal fun fromDos(d: Long): Long {
            val c = Calendar.getInstance()
            c.clear()
            c.set(
                (((d shr 25) and 0x7F) + 1980).toInt(),
                (((d shr 21) and 0x0F) - 1).toInt().coerceIn(0, 11),
                ((d shr 16) and 0x1F).toInt().coerceAtLeast(1),
                ((d shr 11) and 0x1F).toInt(),
                ((d shr 5) and 0x3F).toInt(),
                ((d and 0x1F) * 2).toInt(),
            )
            return c.timeInMillis
        }

        internal fun readUpTo(i: InputStream, b: ByteArray): Int {
            var n = 0
            while (n < b.size) {
                val r = i.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        internal fun readFully(i: InputStream, b: ByteArray) {
            if (readUpTo(i, b) < b.size) throw EOFException("unexpected end of data")
        }

        private fun readBytes(i: InputStream, n: Long): ByteArray {
            if (n < 0 || n > 16L * 1024 * 1024) throw BadArchiveException("bad header size")
            val b = ByteArray(n.toInt())
            readFully(i, b)
            return b
        }

        internal fun skipFully(i: InputStream, n: Long) {
            var left = n
            val tmp = ByteArray(8192)
            while (left > 0) {
                val s = i.skip(left)
                if (s > 0) {
                    left -= s
                    continue
                }
                val r = i.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
                if (r < 0) throw EOFException("unexpected end of data")
                left -= r
            }
        }
    }
}

// ---------------- запись ZIP ----------------

/** Счётчик прогресса для записи. */
private class Counter(val p: ArcProgress, val total: Long) {
    var done = 0L
    fun add(n: Long, name: String) {
        done += n
        p.update(done, total, name)
    }
}

/** Запись в файл с буфером и возможностью «вернуться и дописать» заголовок. */
private class SeekOut(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
    private val buf = ByteArray(256 * 1024)
    private var n = 0
    private var base = 0L
    val position: Long get() = base + n

    fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
        if (len > buf.size - n) {
            flush()
            if (len > buf.size) {
                raf.write(b, off, len)
                base += len
                return
            }
        }
        System.arraycopy(b, off, buf, n, len)
        n += len
    }

    fun flush() {
        if (n > 0) {
            raf.write(buf, 0, n)
            base += n
            n = 0
        }
    }

    fun patch(pos: Long, b: ByteArray) {
        flush()
        raf.seek(pos)
        raf.write(b)
        raf.seek(base)
    }

    override fun close() {
        flush()
        raf.close()
    }
}

/** Little-endian сборщик заголовков. */
private class Le {
    private val o = ByteArrayOutputStream()
    fun u16(v: Int) = apply { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
    fun u32(v: Long) = apply { for (i in 0 until 4) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun bytes(b: ByteArray) = apply { o.write(b, 0, b.size) }
    fun size() = o.size()
    fun get(): ByteArray = o.toByteArray()
}

/** Запись центрального каталога. */
private class CdRec(
    val name: ByteArray,
    val flags: Int,
    val method: Int,
    val dos: Long,
    val crc: Long,
    val csize: Long,
    val size: Long,
    val offset: Long,
    val mtime: Long,
    val isDir: Boolean,
)

/**
 * Писатель ZIP. Локальный заголовок пишется сразу, CRC и размеры - дописываются после данных
 * (без «data descriptor», поэтому архив читается любыми программами, в т.ч. потоковыми).
 * Для пароля CRC считается заранее (нужна для проверочного байта заголовка шифрования).
 */
private class ZipWriter(file: File) : Closeable {
    private val out = SeekOut(file)
    private val cd = ArrayList<CdRec>()
    private val random = SecureRandom()

    private fun timeExtra(mtime: Long): ByteArray =
        Le().u16(0x5455).u16(5).apply { bytes(byteArrayOf(1)) }.u32((mtime / 1000).coerceIn(0, 0xFFFFFFFFL)).get()

    /** Локальный заголовок; возвращает смещение zip64-поля (или -1). */
    private fun local(name: ByteArray, flags: Int, method: Int, dos: Long, crc: Long, csize: Long, size: Long, mtime: Long, zip64: Boolean): Long {
        val start = out.position
        val extra = Le()
        if (zip64) extra.u16(0x0001).u16(16).u64(size).u64(csize)
        extra.bytes(timeExtra(mtime))
        val ex = extra.get()
        val h = Le().u32(0x04034b50L).u16(if (zip64) 45 else 20).u16(flags).u16(method).u32(dos)
            .u32(crc)
            .u32(if (zip64) 0xFFFFFFFFL else csize)
            .u32(if (zip64) 0xFFFFFFFFL else size)
            .u16(name.size).u16(ex.size).bytes(name).bytes(ex).get()
        out.write(h)
        return if (zip64) start + 30 + name.size + 4 else -1
    }

    fun addDir(path: String, mtime: Long) {
        val name = path.toByteArray(Charsets.UTF_8)
        val dos = Archive.toDos(mtime)
        val off = out.position
        local(name, 0x800, 0, dos, 0, 0, 0, mtime, false)
        cd += CdRec(name, 0x800, 0, dos, 0, 0, 0, off, mtime, true)
    }

    fun addFile(f: File, path: String, level: ArcLevel, password: String?, c: Counter) {
        val name = path.toByteArray(Charsets.UTF_8)
        val mtime = f.lastModified()
        val dos = Archive.toDos(mtime)
        val size0 = f.length()
        // Уже сжатое (фото, видео, архивы) повторно не жмём - только тратит время и место.
        val packed = f.extension.lowercase() in PACKED_EXT
        val method = if (level == ArcLevel.STORE || packed || size0 == 0L) 0 else 8
        var flags = 0x800
        if (method == 8) flags = flags or when (level) {
            ArcLevel.MAX -> 2
            ArcLevel.FAST -> 4
            else -> 0
        }
        val buf = ByteArray(Archive.BUF)
        // Пароль: сначала CRC (проверочный байт заголовка шифрования = старший байт CRC).
        var preCrc = 0L
        if (password != null) {
            flags = flags or 1
            val crc = CRC32()
            f.inputStream().use { i ->
                while (true) {
                    if (c.p.cancelled) throw ArcCancelledException()
                    val n = i.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    c.add(n.toLong(), path)
                }
            }
            preCrc = crc.value
        }
        val zip64 = size0 >= 0xF0000000L
        val headerPos = out.position
        val z64pos = local(name, flags, method, dos, preCrc, 0, 0, mtime, zip64)
        val dataStart = out.position
        val crypto = password?.let { ZipCrypto(it.toByteArray(Charsets.UTF_8)) }
        if (crypto != null) {
            val head = ByteArray(12)
            random.nextBytes(head)
            head[11] = ((preCrc ushr 24) and 0xFF).toByte()
            crypto.encrypt(head, 0, 12)
            out.write(head)
        }
        val crc = CRC32()
        var size = 0L
        val deflater = if (method == 8) Deflater(level.deflate, true) else null
        val obuf = ByteArray(Archive.BUF)
        fun emit(b: ByteArray, len: Int) {
            if (len <= 0) return
            crypto?.encrypt(b, 0, len)
            out.write(b, 0, len)
        }
        try {
            f.inputStream().use { i ->
                while (true) {
                    if (c.p.cancelled) throw ArcCancelledException()
                    val n = i.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    size += n
                    if (deflater != null) {
                        deflater.setInput(buf, 0, n)
                        while (!deflater.needsInput()) emit(obuf, deflater.deflate(obuf))
                    } else {
                        // Буфер шифруется на месте - а он ещё нужен только для CRC, которую уже посчитали.
                        emit(buf, n)
                    }
                    c.add(n.toLong(), path)
                }
            }
            if (deflater != null) {
                deflater.finish()
                while (!deflater.finished()) emit(obuf, deflater.deflate(obuf))
            }
        } finally {
            deflater?.end()
        }
        val csize = out.position - dataStart
        if (password != null && crc.value != preCrc) throw IOException("file changed while archiving: ${f.name}")
        if (!zip64 && (csize >= 0xFFFFFFFFL || size >= 0xFFFFFFFFL)) throw IOException("file too large: ${f.name}")
        out.patch(headerPos + 14, Le().u32(crc.value).u32(if (zip64) 0xFFFFFFFFL else csize).u32(if (zip64) 0xFFFFFFFFL else size).get())
        if (zip64) out.patch(z64pos, Le().u64(size).u64(csize).get())
        cd += CdRec(name, flags, method, dos, crc.value, csize, size, headerPos, mtime, false)
    }

    /** Скопировать запись из другого ZIP без распаковки (можно с новым именем). */
    fun copyRaw(src: RandomAccessFile, e: ArcEntry, newPath: String?, c: Counter) {
        val start = Archive.localDataStart(src, e)
        val name: ByteArray
        var flags = e.flags
        if (newPath != null) {
            name = (if (e.isDir) "$newPath/" else newPath).toByteArray(Charsets.UTF_8)
            flags = flags or 0x800
        } else {
            name = e.rawName
        }
        val zip64 = e.csize >= 0xFFFFFFFFL || e.size >= 0xFFFFFFFFL
        val off = out.position
        // Флаг «data descriptor» (бит 3) оставляем - от него зависит проверка пароля; размеры и CRC
        // всё равно известны и пишутся сразу, а после данных добавляется сам descriptor.
        local(name, flags, e.method, e.dosTime, e.crc, e.csize, e.size, e.time, zip64)
        val buf = ByteArray(Archive.BUF)
        var left = e.csize
        var pos = start
        while (left > 0) {
            if (c.p.cancelled) throw ArcCancelledException()
            val n = minOf(left, buf.size.toLong()).toInt()
            src.seek(pos)
            src.readFully(buf, 0, n)
            out.write(buf, 0, n)
            pos += n
            left -= n
            c.add(n.toLong(), e.path)
        }
        if (flags and 8 != 0) {
            val dd = Le().u32(0x08074b50L).u32(e.crc)
            if (zip64) dd.u64(e.csize).u64(e.size) else dd.u32(e.csize).u32(e.size)
            out.write(dd.get())
        }
        cd += CdRec(name, flags, e.method, e.dosTime, e.crc, e.csize, e.size, off, e.time, e.isDir)
    }

    /** Центральный каталог и конец архива (ZIP64 - если нужно). */
    fun finish(comment: ByteArray) {
        val cdStart = out.position
        for (r in cd) {
            val needSize = r.size >= 0xFFFFFFFFL
            val needCsize = r.csize >= 0xFFFFFFFFL
            val needOff = r.offset >= 0xFFFFFFFFL
            val extra = Le()
            if (needSize || needCsize || needOff) {
                val z = Le()
                if (needSize) z.u64(r.size)
                if (needCsize) z.u64(r.csize)
                if (needOff) z.u64(r.offset)
                val zb = z.get()
                extra.u16(0x0001).u16(zb.size).bytes(zb)
            }
            extra.bytes(timeExtra(r.mtime))
            val ex = extra.get()
            val zip64 = needSize || needCsize || needOff
            val mode = if (r.isDir) 0x41EDL else 0x81A4L // drwxr-xr-x / -rw-r--r--
            val h = Le().u32(0x02014b50L).u16(0x0300 or 45).u16(if (zip64) 45 else 20).u16(r.flags).u16(r.method)
                .u32(r.dos).u32(r.crc)
                .u32(if (needCsize) 0xFFFFFFFFL else r.csize)
                .u32(if (needSize) 0xFFFFFFFFL else r.size)
                .u16(r.name.size).u16(ex.size).u16(0).u16(0).u16(0)
                .u32((mode shl 16) or (if (r.isDir) 0x10L else 0L))
                .u32(if (needOff) 0xFFFFFFFFL else r.offset)
                .bytes(r.name).bytes(ex).get()
            out.write(h)
        }
        val cdEnd = out.position
        val cdSize = cdEnd - cdStart
        val count = cd.size.toLong()
        val zip64 = count >= 0xFFFF || cdSize >= 0xFFFFFFFFL || cdStart >= 0xFFFFFFFFL
        if (zip64) {
            out.write(
                Le().u32(0x06064b50L).u64(44).u16(0x0300 or 45).u16(45).u32(0).u32(0)
                    .u64(count).u64(count).u64(cdSize).u64(cdStart).get(),
            )
            out.write(Le().u32(0x07064b50L).u32(0).u64(cdEnd).u32(1).get())
        }
        val cm = if (comment.size > 0xFFFF) comment.copyOf(0xFFFF) else comment
        out.write(
            Le().u32(0x06054b50L).u16(0).u16(0)
                .u16(if (zip64) 0xFFFF else count.toInt()).u16(if (zip64) 0xFFFF else count.toInt())
                .u32(if (zip64) 0xFFFFFFFFL else cdSize).u32(if (zip64) 0xFFFFFFFFL else cdStart)
                .u16(cm.size).bytes(cm).get(),
        )
    }

    override fun close() = out.close()
}

/** Форматы, которые уже сжаты: кладём в архив «без сжатия». */
private val PACKED_EXT = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif",
    "mp4", "mkv", "webm", "3gp", "mov", "avi", "mp3", "m4a", "aac", "ogg", "opus", "flac",
    "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "zst", "apk", "jar", "docx", "xlsx", "pptx", "epub",
)

// ---------------- ZipCrypto (традиционное шифрование PKWARE) ----------------

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

private class ZipCrypto(password: ByteArray) {
    private var k0 = 0x12345678
    private var k1 = 0x23456789
    private var k2 = 0x34567890

    init {
        for (b in password) update(b.toInt() and 0xFF)
    }

    private fun crc(c: Int, b: Int) = (c ushr 8) xor CRC_TABLE[(c xor b) and 0xFF]

    private fun update(b: Int) {
        k0 = crc(k0, b)
        k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
        k2 = crc(k2, k1 ushr 24)
    }

    private fun key(): Int {
        val t = (k2 or 2) and 0xFFFF
        return ((t * (t xor 1)) ushr 8) and 0xFF
    }

    fun decrypt(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val p = (b[i].toInt() xor key()) and 0xFF
            update(p)
            b[i] = p.toByte()
        }
    }

    fun encrypt(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val p = b[i].toInt() and 0xFF
            val k = key()
            update(p)
            b[i] = (p xor k).toByte()
        }
    }
}

// ---------------- потоки ----------------

/** Кусок файла [start, start+len) - данные одной записи ZIP. */
private class RafSlice(private val raf: RandomAccessFile, start: Long, private var left: Long) : InputStream() {
    private var pos = start
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        if (len == 0) return 0
        raf.seek(pos)
        val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
        if (n <= 0) throw EOFException("unexpected end of archive")
        pos += n
        left -= n
        return n
    }

    override fun close() = raf.close()
}

/** Расшифровка ZipCrypto на лету. */
private class DecryptIn(src: InputStream, private val c: ZipCrypto) : FilterInputStream(src) {
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        if (n > 0) c.decrypt(b, off, n)
        return n
    }

    override fun skip(n: Long): Long = 0
    override fun markSupported() = false
}

/** «Сырой» Deflate (без заголовка zlib), как в ZIP. */
private class InflateIn(private val src: InputStream) : InputStream() {
    private val inf = Inflater(true)
    private val buf = ByteArray(Archive.BUF)
    private var dummy = false
    private var eof = false

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        if (len == 0) return 0
        while (true) {
            val n = try {
                inf.inflate(b, off, len)
            } catch (e: DataFormatException) {
                throw ArcException("corrupt data: ${e.message}")
            }
            if (n > 0) return n
            if (inf.finished()) {
                eof = true
                return -1
            }
            if (inf.needsDictionary()) throw ArcException("corrupt data")
            if (inf.needsInput()) {
                val r = src.read(buf)
                if (r > 0) {
                    inf.setInput(buf, 0, r)
                } else if (!dummy) {
                    // Inflater без заголовка иногда просит лишний байт в самом конце.
                    dummy = true
                    buf[0] = 0
                    inf.setInput(buf, 0, 1)
                } else {
                    throw EOFException("unexpected end of compressed data")
                }
            }
        }
    }

    override fun close() {
        inf.end()
        src.close()
    }
}

/**
 * Проверка при чтении: распаковано не больше заявленного (иначе - «бомба»), в конце
 * совпадают размер и CRC. Если размер неизвестен - ограничение по степени сжатия.
 */
private class CheckIn(
    src: InputStream,
    private val name: String,
    private val crc: Long,
    private val size: Long,
    private val csize: Long,
) : FilterInputStream(src) {
    private val c = CRC32()
    private var n = 0L
    private var ended = false

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = `in`.read(b, off, len)
        if (r > 0) {
            if (crc >= 0) c.update(b, off, r)
            n += r
            if (size >= 0 && n > size) throw ZipBombException("more data than declared: $name")
            if (size < 0 && csize > 0 && n > Archive.BOMB_MIN_SIZE && n / csize > Archive.BOMB_RATIO) {
                throw ZipBombException("ratio: $name")
            }
        } else if (r < 0 && !ended) {
            ended = true
            if (size >= 0 && n != size) throw CrcException(name)
            if (crc >= 0 && c.value != crc) throw CrcException(name)
        }
        return r
    }

    override fun skip(n: Long): Long {
        val tmp = ByteArray(8192)
        var left = n
        while (left > 0) {
            val r = read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported() = false
}

/** Ограничение потока [len] байтами (запись TAR). */
private class Slice(src: InputStream, private var left: Long, private val closeSource: Boolean) : FilterInputStream(src) {
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        if (len == 0) return 0
        val r = `in`.read(b, off, minOf(len.toLong(), left).toInt())
        if (r < 0) throw EOFException("unexpected end of archive")
        left -= r
        return r
    }

    /** Дочитать остаток (чтобы поток TAR встал на следующий заголовок). */
    fun drain() {
        if (left > 0) Archive.skipFully(`in`, left)
        left = 0
    }

    override fun skip(n: Long): Long = 0
    override fun markSupported() = false
    override fun close() {
        if (closeSource) `in`.close()
    }
}

/** Считает прочитанные байты (позиция в потоке TAR). */
private class CountIn(src: InputStream) : FilterInputStream(src) {
    var count = 0L
        private set

    override fun read(): Int {
        val r = `in`.read()
        if (r >= 0) count++
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = `in`.read(b, off, len)
        if (r > 0) count += r
        return r
    }

    override fun skip(n: Long): Long {
        val s = `in`.skip(n)
        if (s > 0) count += s
        return s
    }

    override fun markSupported() = false
}

// ---------------- little-endian чтение ----------------

private fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
private fun ByteArray.u64(i: Int): Long = u32(i) or (u32(i + 4) shl 32)
