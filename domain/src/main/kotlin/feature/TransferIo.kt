// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Диск - отдельно от потока ядра Tox.
 *
 * Куски файла принимаются и отдаются внутри цикла ядра (tox_iterate). Раньше запись 512 КБ на
 * флеш-память (и чтение 1 МБ из галереи) шла прямо в этом цикле: на медленной памяти это 10-50 мс,
 * за которые ядро не отвечало сети - падала скорость, а во время звонка звук шёл рывками.
 * Теперь запись и чтение идут в фоне, ядро только копирует байты в память.
 * Шифрование не меняется: данные по сети по-прежнему идут только внутри сквозного шифрования Tox.
 */
/**
 * Запись - несколькими потоками (2-4 по числу ядер): несколько файлов принимаются одновременно
 * и не ждут друг друга. Порядок внутри одного файла сохраняется - у каждого файла в пути
 * не больше одного блока (см. [IncomingSink.submit]).
 */
private val writer: ExecutorService = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(2, 4) },
) { r -> Thread(r, "ft-writer").apply { isDaemon = true } }
private val readers: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "ft-prefetch").apply { isDaemon = true } }

/**
 * Запись принятого файла крупными блоками с двойным буфером: пока один блок пишется на диск
 * в фоне, следующие куски сети копятся во втором. Прогресс (и докачка) считаются только по уже
 * записанному ([durableEnd]) - после внезапного закрытия файл докачается с верного места.
 */
internal class IncomingSink(private val raf: RandomAccessFile, size: Int = 512 * 1024) {
    private var buf = ByteArray(size)
    private var spare = ByteArray(size)
    private var start = 0L
    private var len = 0
    private var pending: Future<*>? = null

    /** Начало блока, который сейчас пишется в фоне (-1 - ничего не пишется). */
    @Volatile
    private var inflightStart = -1L

    @Volatile
    private var error: Throwable? = null

    @Synchronized
    fun write(pos: Long, data: ByteArray) {
        error?.let { throw IOException("write failed", it) }
        if (len > 0 && pos == start + len && len + data.size <= buf.size) {
            System.arraycopy(data, 0, buf, len, data.size)
            len += data.size
            return
        }
        submit()
        if (data.size >= buf.size) {
            await()
            raf.seek(pos)
            raf.write(data)
            return
        }
        start = pos
        System.arraycopy(data, 0, buf, 0, data.size)
        len = data.size
    }

    /** Отдать накопленный блок фоновой записи (не больше одного блока в пути - порядок сохраняется). */
    private fun submit() {
        if (len == 0) return
        await()
        val b = buf
        val s = start
        val l = len
        buf = spare
        spare = b
        len = 0
        inflightStart = s
        pending = writer.submit {
            try {
                raf.seek(s)
                raf.write(b, 0, l)
            } catch (t: Throwable) {
                error = t
            } finally {
                inflightStart = -1L
            }
        }
    }

    private fun await() {
        pending?.get()
        pending = null
        error?.let { throw IOException("write failed", it) }
    }

    /** Докуда файл точно записан на диск (с учётом последнего куска [end]). */
    @Synchronized
    fun durableEnd(end: Long): Long {
        val f = inflightStart
        return when {
            f >= 0 -> f
            len > 0 -> start
            else -> end
        }
    }

    /** Записать всё накопленное и дождаться записи. */
    @Synchronized
    fun flush() {
        submit()
        await()
    }

    @Synchronized
    fun close() {
        try {
            flush()
        } finally {
            raf.close()
        }
    }
}

/**
 * Чтение отправляемого файла с опережением: фоновый поток читает блоки по 256 КБ (до 1 МБ
 * вперёд), ядро забирает уже готовые байты. Блоки переиспользуются - без мусора для сборщика.
 */
internal class PrefetchInputStream(
    private val src: InputStream,
    private val blockSize: Int = 256 * 1024,
    // Быстрые телефоны (6+ ядер) читают вперёд до 2 МБ - отправка не ждёт медленную галерею.
    depth: Int = if (Runtime.getRuntime().availableProcessors() >= 6) 8 else 4,
) : InputStream() {
    private class Block(val data: ByteArray, var len: Int)

    private val ready = ArrayBlockingQueue<Any>(depth + 1)
    private val free = ArrayBlockingQueue<Block>(depth + 2)
    private val eof = Any()

    @Volatile
    private var closed = false
    private var cur: Block? = null
    private var curPos = 0
    private var done = false
    private val task: Future<*>

    init {
        task = readers.submit { fill() }
    }

    private fun fill() {
        try {
            while (!closed) {
                val b = free.poll() ?: Block(ByteArray(blockSize), 0)
                var n = 0
                while (n < blockSize) {
                    val r = src.read(b.data, n, blockSize - n)
                    if (r < 0) break
                    n += r
                }
                b.len = n
                if (n > 0) put(b)
                if (n < blockSize) {
                    put(eof)
                    return
                }
            }
        } catch (t: Throwable) {
            if (!closed) put(t)
        }
    }

    private fun put(o: Any) {
        while (!closed) {
            if (ready.offer(o, 200, TimeUnit.MILLISECONDS)) return
        }
    }

    /** Текущий блок с непрочитанными байтами или null (конец файла). */
    private fun block(): Block? {
        cur?.let { if (curPos < it.len) return it }
        cur?.let { free.offer(it) }
        cur = null
        if (done) return null
        // Источник завис (облачный файл, отключённая карта памяти) - не держим ядро вечно.
        val o = ready.poll(20, TimeUnit.SECONDS) ?: throw IOException("read timeout")
        if (o === eof) {
            done = true
            return null
        }
        if (o is Throwable) {
            done = true
            throw IOException("read failed", o)
        }
        val b = o as Block
        cur = b
        curPos = 0
        return b
    }

    override fun read(): Int {
        val b = block() ?: return -1
        return b.data[curPos++].toInt() and 0xFF
    }

    override fun read(out: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            // Первый блок ждём; дальше берём только то, что уже прочитано (не ждём ради «добора»).
            val c = cur
            val b = when {
                n == 0 -> block()
                c != null && curPos < c.len -> c
                ready.isEmpty() -> null
                else -> block()
            } ?: break
            val k = minOf(len - n, b.len - curPos)
            System.arraycopy(b.data, curPos, out, off + n, k)
            curPos += k
            n += k
        }
        return if (n == 0) -1 else n
    }

    override fun skip(n: Long): Long {
        var left = n
        while (left > 0) {
            val b = block() ?: break
            val k = minOf(left, (b.len - curPos).toLong()).toInt()
            curPos += k
            left -= k
        }
        return n - left
    }

    override fun close() {
        closed = true
        task.cancel(true)
        ready.clear()
        runCatching { src.close() }
    }
}
