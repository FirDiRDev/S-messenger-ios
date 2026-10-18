// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.forEach as kForEach
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.FileTransferRepository
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.vo.FT_NOT_STARTED
import com.stoyko.smessenger.core.vo.FT_REJECTED
import com.stoyko.smessenger.core.vo.FT_STARTED
import com.stoyko.smessenger.core.vo.FileKind
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.core.vo.isComplete
import com.stoyko.smessenger.core.vo.isRejected
import com.stoyko.smessenger.core.vo.isStarted
import com.stoyko.smessenger.domain.tox.MAX_AVATAR_SIZE
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxFileControl

private const val TAG = "FileTransferManager"

// TODO(robinlinden): This will go away when PublicKey is used everywhere it should be.
private const val FINGERPRINT_LEN = 8
private fun String.fingerprint() = take(FINGERPRINT_LEN)

// Прогресс в базу пишем не чаще, чем раз в DB_PROGRESS_MS: раньше запись шла на КАЖДЫЙ кусок
// (~1,3 КБ), это тормозило передачу и перерисовывало весь чат сотни раз в секунду.
private const val DB_PROGRESS_MS = 700L

/** Передача без движения дольше этого - считается зависшей и перезапускается. */
private const val STALL_MS = 40_000L

/** Скрытое служебное сообщение «пришли файл снова» (имя и размер). */
const val RESEND_PREFIX = "⁣📎R:"

/** Повторно предложенный незаконченный файл (тот же собеседник, имя, размер) - в прежний пузырь, если ему не больше суток. */
private const val REOFFER_WINDOW_MS = 24 * 3600_000L

/**
 * Уже полностью принятый файл, который собеседник предложил снова после обрыва связи (он не успел
 * узнать, что файл дошёл), - не показываем второй раз, если с приёма прошло не больше этого.
 */
private const val DONE_REOFFER_MS = 30 * 60_000L

/** То же, если приём был до перезапуска приложения (точное время конца приёма неизвестно - берём время пузыря). */
private const val DONE_REOFFER_AFTER_RESTART_MS = 2 * 3600_000L

/** Повтор уже принятого файла приходит сразу после переподключения - позже это уже новая отправка. */
private const val REOFFER_AFTER_RECONNECT_MS = 15 * 60_000L

/** Повторная отправка того же адреса тому же собеседнику чаще этого - двойное нажатие, второй файл не создаём. */
private const val DOUBLE_SEND_MS = 4_000L

/** id «поглотителя»: повтор уже принятого файла дочитывается во временный файл и выбрасывается (в базе такой строки нет). */
private const val DISCARD_ID = Int.MIN_VALUE + 7

@Suppress("ArrayInDataClass")
private data class Chunk(val pos: Long, val data: ByteArray)

// Запись принятого файла (IncomingSink) и чтение отправляемого (PrefetchInputStream) - в TransferIo.kt:
// диск работает в фоне, цикл ядра Tox не ждёт флеш-память.

private data class OutgoingFile(val inputStream: InputStream, val unsentChunks: MutableList<Chunk>) {
    // Позиция, до которой поток уже прочитан (ядро просит куски по порядку).
    var readPos = 0L

    // Один буфер на файл: кусок (~1,3 КБ) читается в него и сразу уходит в ядро - без нового массива
    // на каждый кусок (сотни тысяч мелких объектов на большом файле нагружали сборщик мусора).
    var scratch = ByteArray(0)
}

@Singleton
class FileTransferManager @Inject constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val resolver: ContentResolver,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val tox: Tox,
) {
    // Колбэки ядра и действия пользователя приходят из разных потоков.
    private val fileTransfers: MutableList<FileTransfer> = CopyOnWriteArrayList()
    private val outgoingFiles = ConcurrentHashMap<Pair<String, Int>, OutgoingFile>()

    // Открытые файлы входящих передач (раньше файл открывался заново в режиме "rwd" на каждый кусок).
    private val incomingFiles = ConcurrentHashMap<Pair<String, Int>, IncomingSink>()
    private val lastDbWrite = ConcurrentHashMap<Int, Long>()

    /**
     * Файл, который не нужно показывать в чате (например, история). true - строку в чат не добавлять.
     * Задаётся приложением.
     */
    @Volatile
    var hiddenIncoming: ((publicKey: String, fileName: String) -> Boolean)? = null

    /** Входящий файл полностью получен (для историй и т. п.). */
    @Volatile
    var onIncomingComplete: ((FileTransfer) -> Unit)? = null

    /** Когда по передаче последний раз что-то пришло/ушло - для «сторожа» зависших передач. */
    private val lastActivity = ConcurrentHashMap<Int, Long>()
    private val resendAsked = ConcurrentHashMap<Int, Long>()

    /** Когда (по часам с загрузки) входящий файл «собеседник|размер|имя» был принят целиком. */
    private val recentDone = ConcurrentHashMap<String, Long>()

    /** Последний обрыв связи с собеседником (после него он может предложить уже принятый файл снова). */
    private val lastDisconnect = ConcurrentHashMap<String, Long>()

    /** Последние отправки «собеседник|адрес» -> (время, id) - защита от двойного нажатия «Отправить». */
    private val recentCreates = HashMap<String, Pair<Long, Int>>()

    /** Досылка своих файлов идёт строго по одной: раньше два одновременных вызова предлагали один файл дважды. */
    private val retryMutex = kotlinx.coroutines.sync.Mutex()

    /** Запуск приложения (по часам с загрузки): перезапуск - тоже обрыв связи для всех собеседников. */
    private val startedAt = SystemClock.elapsedRealtime()

    init {
        // Кнопка «Отменить» в уведомлении о передаче (шторка).
        TransferMonitor.cancelHandlers["ft:"] = { key -> key.removePrefix("ft:").toIntOrNull()?.let { reject(it) } }
        // Сторож: передача «застряла» (связь есть, а данные не идут) - перезапускаем сами,
        // с места обрыва. Раньше полузагруженный файл висел «уйдёт, когда собеседник будет
        // в сети» бесконечно, хотя собеседник в сети.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                runCatching { checkStalled() }.onFailure { Log.w(TAG, "watchdog: $it") }
            }
        }
        File(context.filesDir, "ft").mkdir()
        File(context.filesDir, "avatar").mkdir()
        resolver.persistedUriPermissions.kForEach {
            Log.w(TAG, "Clearing leftover permission for ${it.uri}")
            releaseFilePermission(it.uri)
        }
    }

    fun reset() {
        val now = SystemClock.elapsedRealtime()
        fileTransfers.map { it.publicKey }.distinct().kForEach { lastDisconnect[it] = now }
        fileTransfers.filter { it.id == DISCARD_ID }.kForEach { ft -> ft.destination.toUri().path?.let { File(it).delete() } }
        fileTransfers.filter { !it.outgoing && it.fileKind == FileKind.Data.ordinal && it.progress > 0 && !it.isComplete() && it.id != DISCARD_ID }
            .kForEach { ft -> ft.destination.toUri().path?.let { rememberPartial(ft, it) } }
        fileTransfers.clear()
        closeAllIncoming()
        scope.launch {
            fileTransferRepository.resetTransientData()
        }
    }

    private fun closeAllIncoming() {
        incomingFiles.values.kForEach { runCatching { it.close() } }
        incomingFiles.clear()
    }

    private fun closeIncoming(pk: String, fileNumber: Int) {
        incomingFiles.remove(Pair(pk, fileNumber))?.let { runCatching { it.close() } }
    }

    fun resetForContact(pk: String) {
        Log.i(TAG, "Clearing fts for contact ${pk.fingerprint()}")
        lastDisconnect[pk] = SystemClock.elapsedRealtime()
        fileTransfers.filter { it.publicKey == pk }.kForEach eachFt@{ ft ->
            fileTransfers.remove(ft)
            if (ft.id == DISCARD_ID) {
                closeIncoming(pk, ft.fileNumber)
                ft.destination.toUri().path?.let { File(it).delete() }
                return@eachFt
            }
            TransferMonitor.finish(TransferMonitor.key(ft.id))
            if (ft.fileKind == FileKind.Data.ordinal && ft.id !in cancelledByUser) {
                // Собеседник пропал из сети: передача не обрывается, а ставится на паузу.
                // Свой файл уйдёт заново при подключении, чужой - докачается с того же места.
                if (ft.outgoing) {
                    setProgress(ft, FT_NOT_STARTED, force = true)
                    outgoingFiles.remove(Pair(pk, ft.fileNumber))?.inputStream?.let { runCatching { it.close() } }
                } else {
                    closeIncoming(pk, ft.fileNumber)
                    val path = ft.destination.toUri().path
                    if (ft.isStarted() && path != null && File(path).exists()) {
                        setProgress(ft, ft.progress, force = true)
                        rememberPartial(ft, path)
                    } else {
                        setProgress(ft, FT_REJECTED, force = true)
                    }
                }
                return@eachFt
            }
            setProgress(ft, FT_REJECTED, force = true)
            if (ft.outgoing) {
                val uri = ft.destination.toUri()
                outgoingFiles.remove(Pair(pk, ft.fileNumber))?.inputStream?.let { runCatching { it.close() } }
                releaseFilePermission(uri)
            } else {
                closeIncoming(pk, ft.fileNumber)
                ft.destination.toUri().path?.let { File(it).delete() }
            }
        }
    }

    // ---------- Докачка входящих файлов после обрыва связи ----------

    private val partials by lazy { context.getSharedPreferences("ft_partial", Context.MODE_PRIVATE) }
    private val resumed = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    private fun partialKey(pk: String, name: String, size: Long) = "$pk|$size|$name".take(900)

    private fun rememberPartial(ft: FileTransfer, path: String) {
        if (ft.id == DISCARD_ID) return
        partials.edit().putString(partialKey(ft.publicKey, ft.fileName, ft.fileSize), "${ft.id}|${ft.progress}|$path").apply()
    }

    private fun forgetPartial(ft: FileTransfer) {
        if (ft.outgoing || ft.fileKind != FileKind.Data.ordinal) return
        partials.edit().remove(partialKey(ft.publicKey, ft.fileName, ft.fileSize)).apply()
    }

    /** Эта передача - продолжение оборванной (уже принята, в чате - прежний пузырь). */
    fun isResumed(id: Int) = id in resumed

    /** Собеседник снова прислал тот же файл - продолжаем с места обрыва. -1 - докачка невозможна. */
    private fun tryResume(ft: FileTransfer): Int {
        val key = partialKey(ft.publicKey, ft.fileName, ft.fileSize)
        val saved = partials.getString(key, null) ?: return -1
        partials.edit().remove(key).apply()
        val parts = saved.split('|', limit = 3)
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return -1
        val done = parts.getOrNull(1)?.toLongOrNull() ?: return -1
        val file = File(parts.getOrNull(2) ?: return -1)
        if (!file.exists() || done <= 0 || done >= ft.fileSize || id in cancelledByUser) return -1
        // Пузырь этого файла удалён из чата - докачивать «в никуда» не нужно.
        if (fileTransferRepository.getNow(id) == null) return -1
        val resumedFt = ft.copy().apply {
            this.id = id
            destination = Uri.fromFile(file).toString()
            progress = done
        }
        try {
            val raf = RandomAccessFile(file, "rw")
            incomingFiles.put(Pair(ft.publicKey, ft.fileNumber), IncomingSink(raf))?.let { runCatching { it.close() } }
        } catch (e: Exception) {
            Log.e(TAG, "resume open: $e")
            return -1
        }
        // Если ядро не поддержит перемотку, собеседник пришлёт файл с начала - запись идёт по позициям,
        // так что файл всё равно соберётся правильно.
        val seeked = tox.seekFile(PublicKey(ft.publicKey), ft.fileNumber, done)
        if (!seeked) resumedFt.progress = FT_STARTED
        // Старая «зависшая» копия этой же передачи (с прежним номером файла) больше не нужна.
        fileTransfers.filter { it.id == id && !it.outgoing && it.fileNumber != ft.fileNumber }.kForEach { old ->
            fileTransfers.remove(old)
            closeIncoming(old.publicKey, old.fileNumber)
        }
        fileTransfers.add(resumedFt)
        resumed.add(id)
        // Номер передачи у ядра новый - запоминаем его в той же строке (новой строки и пузыря не будет).
        fileTransferRepository.add(resumedFt.copy().apply { this.id = id })
        fileTransferRepository.forceProgress(id, resumedFt.progress)
        TransferMonitor.start(TransferMonitor.key(id), ft.fileSize, false, label = ft.fileName, peer = ft.publicKey)
        TransferMonitor.set(TransferMonitor.key(id), resumedFt.progress.coerceAtLeast(0))
        tox.startFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
        Log.i(TAG, "Resumed ft $id at $done (seek=$seeked)")
        return id
    }

    // ---------- Защита от дублей входящих файлов ----------

    /** Эта передача - повтор уже показанного файла: уведомлять и принимать заново не нужно. */
    private val reoffered = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /** Файл [id] предложен повторно и попал в прежний пузырь (уведомление не показываем). */
    fun isReoffer(id: Int) = id in reoffered

    /** Та же передача, что уже идёт под другим номером, - ставим старую на паузу (для докачки). */
    private fun parkStaleCopies(ft: FileTransfer) {
        fileTransfers.filter {
            !it.outgoing && it.fileKind == FileKind.Data.ordinal && it.id != DISCARD_ID && it.publicKey == ft.publicKey &&
                it.fileName == ft.fileName && it.fileSize == ft.fileSize && it.fileNumber != ft.fileNumber
        }.kForEach { old ->
            incomingFiles[Pair(old.publicKey, old.fileNumber)]?.let { runCatching { it.flush() } }
            val path = old.destination.toUri().path
            if (old.isStarted() && old.progress > 0 && path != null && File(path).exists()) {
                fileTransferRepository.updateProgress(old.id, old.progress)
                rememberPartial(old, path)
            }
            fileTransfers.remove(old)
            closeIncoming(old.publicKey, old.fileNumber)
            Log.i(TAG, "Parked stale copy of ft ${old.id} (${old.fileNumber} -> ${ft.fileNumber})")
        }
    }

    /**
     * Файл уже полностью принят, а собеседник предлагает его снова: он не успел узнать о конце
     * передачи до обрыва связи (или до перезапуска). Второй пузырь не нужен.
     */
    private fun isDoneReoffer(ft: FileTransfer): Boolean {
        val last = fileTransferRepository.lastIncoming(ft.publicKey, ft.fileName, ft.fileSize) ?: return false
        if (!last.isComplete() || ft.fileSize <= 0) return false
        val key = partialKey(ft.publicKey, ft.fileName, ft.fileSize)
        val now = SystemClock.elapsedRealtime()
        val doneAt = recentDone[key]
        return if (doneAt != null) {
            // Принят в этом запуске: повтор - только если с тех пор связь обрывалась и предложение пришло
            // вскоре после этого (иначе это новая, намеренная отправка того же файла).
            val cut = lastDisconnect[ft.publicKey] ?: 0L
            cut > doneAt && now - doneAt < DONE_REOFFER_MS && now - cut < REOFFER_AFTER_RECONNECT_MS
        } else {
            // Принят до перезапуска приложения - перезапуск и есть обрыв связи; повтор приходит вскоре после запуска.
            if (now - startedAt > REOFFER_AFTER_RECONNECT_MS) return false
            val shown = messageRepository.fileMessageTime(ft.publicKey, last.id) ?: return false
            System.currentTimeMillis() - shown in 0..DONE_REOFFER_AFTER_RESTART_MS
        }
    }

    /**
     * Повтор уже принятого файла: принимаем только последний байт во временный файл и выбрасываем -
     * отправитель увидит «доставлено», у получателя второй копии не будет.
     */
    private fun discardReoffer(ft: FileTransfer) {
        val tmp = File(context.cacheDir, "ft_dup_${System.nanoTime()}")
        try {
            incomingFiles.put(Pair(ft.publicKey, ft.fileNumber), IncomingSink(RandomAccessFile(tmp, "rw")))
                ?.let { runCatching { it.close() } }
        } catch (e: Exception) {
            Log.e(TAG, "discard open: $e")
            tox.stopFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
            return
        }
        val dup = ft.copy(progress = FT_STARTED, destination = Uri.fromFile(tmp).toString()).apply { id = DISCARD_ID }
        fileTransfers.add(dup)
        if (ft.fileSize > 1) tox.seekFile(PublicKey(ft.publicKey), ft.fileNumber, ft.fileSize - 1)
        tox.startFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
        Log.i(TAG, "Re-offer of already received ${ft.fileNumber} from ${ft.publicKey.fingerprint()} - discarding")
    }

    /**
     * Повторное предложение незаконченного файла (не успели принять, отклонён обрывом связи,
     * перезапуск приложения): используем прежнюю строку и прежний пузырь вместо нового.
     */
    private fun reuseStaleRow(ft: FileTransfer): Int? {
        val old = fileTransferRepository.lastIncoming(ft.publicKey, ft.fileName, ft.fileSize) ?: return null
        if (old.isComplete()) return null
        if (fileTransfers.any { it.id == old.id && it.fileNumber == ft.fileNumber }) return null
        val shown = messageRepository.fileMessageTime(ft.publicKey, old.id)
        val hidden = hiddenIncoming?.invoke(ft.publicKey, ft.fileName) == true
        if (shown == null && !hidden) return null // пузыря нет (удалён) - это новый файл
        if (shown != null && System.currentTimeMillis() - shown > REOFFER_WINDOW_MS) return null
        // Недокачанный кусок (если остался без записи для докачки) больше не нужен - файл пойдёт с начала.
        if (old.destination.startsWith("file://")) old.destination.toUri().path?.let { File(it).delete() }
        fileTransfers.filter { it.id == old.id && !it.outgoing }.kForEach { cur ->
            fileTransfers.remove(cur)
            closeIncoming(cur.publicKey, cur.fileNumber)
        }
        cancelledByUser.remove(old.id)
        resumed.remove(old.id)
        val fresh = ft.copy(progress = FT_NOT_STARTED, destination = "").apply { id = old.id }
        fileTransferRepository.add(fresh)
        fileTransferRepository.forceProgress(old.id, FT_NOT_STARTED)
        fileTransfers.add(fresh.copy().apply { id = old.id })
        reoffered.add(old.id)
        Log.i(TAG, "Re-offer of ft ${old.id} from ${ft.publicKey.fingerprint()} - same bubble")
        return old.id
    }

    fun add(ft: FileTransfer): Int {
        Log.i(TAG, "Add ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        return when (ft.fileKind) {
            FileKind.Data.ordinal -> {
                // Тот же файл ещё «висит» под прежним номером (собеседник перезапустил отправку) -
                // принятое сохраняем, и новая передача продолжит его, а не начнёт второй пузырь.
                parkStaleCopies(ft)
                val resumedId = tryResume(ft)
                if (resumedId >= 0) return resumedId
                if (isDoneReoffer(ft)) {
                    discardReoffer(ft)
                    return -1
                }
                reuseStaleRow(ft)?.let { return it }
                val id = fileTransferRepository.add(ft).toInt()
                val hidden = hiddenIncoming?.invoke(ft.publicKey, ft.fileName) == true
                if (!hidden) {
                    messageRepository.addFileMessageOnce(
                        Message(ft.publicKey, ft.fileName, Sender.Received, MessageType.FileTransfer, id, Date().time),
                    )
                }
                fileTransfers.add(ft.copy().apply { this.id = id })
                id
            }
            FileKind.Avatar.ordinal -> {
                if (ft.fileSize == 0L) {
                    contactRepository.setAvatarUri(ft.publicKey, "")
                    reject(ft)
                    return -1
                } else if (ft.fileSize > MAX_AVATAR_SIZE) {
                    Log.e(TAG, "Got trash avatar with size ${ft.fileSize} from ${ft.publicKey}")
                    contactRepository.setAvatarUri(ft.publicKey, "")
                    tox.stopFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
                    return -1
                }

                fileTransfers.add(ft)
                accept(ft)
                -1
            }
            else -> {
                Log.e(TAG, "Got unknown file kind ${ft.fileKind} in file transfer")
                -1
            }
        }
    }

    fun accept(id: Int) {
        fileTransfers.find { it.id == id }?.let {
            accept(it)
        } ?: Log.e(TAG, "Unable to find & accept ft $id")
    }

    fun accept(ft: FileTransfer) {
        if (ft.id in resumed && ft.isStarted()) return // уже докачивается
        Log.i(TAG, "Accept ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        val file = when (ft.fileKind) {
            FileKind.Data.ordinal -> {
                val dest = makeDestination(ft)
                val file = File(dest.path!!)
                file.parentFile!!.mkdirs()
                file
            }
            FileKind.Avatar.ordinal -> wipAvatar(ft.fileName)
            else -> {
                Log.e(TAG, "Got unknown file kind when accepting ft: $ft")
                return
            }
        }

        try {
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(ft.fileSize)
            incomingFiles.put(Pair(ft.publicKey, ft.fileNumber), IncomingSink(raf))?.let { runCatching { it.close() } }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create $file: $e")
            reject(ft)
            return
        }
        setDestination(ft, Uri.fromFile(file))
        setProgress(ft, FT_STARTED, force = true)
        if (ft.fileKind == FileKind.Data.ordinal) TransferMonitor.start(TransferMonitor.key(ft.id), ft.fileSize, false, label = ft.fileName, peer = ft.publicKey)
        tox.startFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
    }

    /** Отмена пользователем (кнопка «Отмена»/«Отклонить»). */
    fun reject(id: Int) {
        markCancelledByUser(id)
        TransferMonitor.finish(TransferMonitor.key(id))
        fileTransfers.find { it.id == id }?.let {
            reject(it)
        } ?: run {
            // Файл ещё ждал подключения собеседника - просто помечаем отменённым.
            fileTransferRepository.updateProgress(id, FT_REJECTED)
        }
    }

    fun reject(ft: FileTransfer) {
        Log.i(TAG, "Reject ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        fileTransfers.remove(ft)
        forgetPartial(ft)
        resumed.remove(ft.id)
        setProgress(ft, FT_REJECTED, force = true)
        TransferMonitor.finish(TransferMonitor.key(ft.id))
        tox.stopFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
        val uri = ft.destination.toUri()
        if (ft.outgoing) {
            outgoingFiles.remove(Pair(ft.publicKey, ft.fileNumber))?.inputStream?.let { runCatching { it.close() } }
            releaseFilePermission(uri)
        } else {
            closeIncoming(ft.publicKey, ft.fileNumber)
            uri.path?.let { File(it).delete() }
        }
    }

    private fun setDestination(ft: FileTransfer, destination: Uri) {
        fileTransfers.find { it === ft || (it.id == ft.id && it.publicKey == ft.publicKey && it.fileNumber == ft.fileNumber) }
            ?.destination = destination.toString()
        ft.destination = destination.toString()
        if (ft.fileKind == FileKind.Data.ordinal) {
            fileTransferRepository.setDestination(ft.id, destination.toString())
        }
    }

    /** force - записать в базу сразу (смена состояния, конец передачи). */
    private fun setProgress(ft: FileTransfer, progress: Long, force: Boolean = false) {
        ft.progress = progress
        if (ft.fileKind != FileKind.Data.ordinal || ft.id == DISCARD_ID) return
        val now = SystemClock.elapsedRealtime()
        lastActivity[ft.id] = now
        val last = lastDbWrite[ft.id] ?: 0L
        if (force || progress >= ft.fileSize || progress < 0 || now - last >= DB_PROGRESS_MS) {
            lastDbWrite[ft.id] = now
            fileTransferRepository.updateProgress(ft.id, progress)
            // Временный кэш докачки: что уже принято лежит в файле, а здесь - сколько. Если связь
            // оборвётся, телефон уснёт или приложение перезапустится, файл продолжит качаться
            // с этого места, а не с начала (как в больших мессенджерах).
            if (!ft.outgoing && progress > 0 && progress < ft.fileSize) {
                ft.destination.toUri().path?.let { rememberPartial(ft, it) }
            }
        }
        if (progress >= ft.fileSize || progress < 0) lastDbWrite.remove(ft.id)
    }

    fun addDataToTransfer(publicKey: String, fileNumber: Int, position: Long, data: ByteArray) {
        val ft = fileTransfers.find { it.publicKey == publicKey && it.fileNumber == fileNumber && !it.outgoing }
        if (ft == null) {
            if (data.isNotEmpty()) {
                Log.e(TAG, "Got data for ft $fileNumber for ${publicKey.fingerprint()} we don't know about")
            }
            return
        }

        if (ft.fileKind != FileKind.Data.ordinal && ft.fileKind != FileKind.Avatar.ordinal) {
            Log.e(TAG, "Got unknown file kind when adding data to ft: $ft")
            return
        }

        var durable = 0L
        if (data.isNotEmpty()) {
            try {
                val sink = incomingFiles.getOrPut(Pair(publicKey, fileNumber)) {
                    IncomingSink(RandomAccessFile(File(ft.destination.toUri().path!!), "rw"))
                }
                sink.write(position, data)
                // Последний кусок - сразу на диск.
                if (position + data.size >= ft.fileSize) sink.flush()
                durable = sink.durableEnd(position + data.size)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to write chunk of ${ft.fileNumber}: $e")
                reject(ft)
                return
            }
            // Прогресс - по позиции, а не суммой: при докачке и повторе куска не «перебегает» размер файла.
            // И только по записанному на диск (данные в буфере ещё не считаются).
            setProgress(ft, maxOf(ft.progress, durable))
            if (ft.fileKind == FileKind.Data.ordinal && ft.id != DISCARD_ID) TransferMonitor.set(TransferMonitor.key(ft.id), ft.progress)
        }

        if (ft.isComplete() || data.isEmpty()) {
            Log.i(TAG, "Finished ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
            closeIncoming(publicKey, fileNumber)
            if (ft.id == DISCARD_ID) {
                // Повтор уже принятого файла - выбрасываем.
                fileTransfers.remove(ft)
                ft.destination.toUri().path?.let { File(it).delete() }
                return
            }
            forgetPartial(ft)
            if (ft.fileKind == FileKind.Data.ordinal) {
                recentDone[partialKey(ft.publicKey, ft.fileName, ft.fileSize)] = SystemClock.elapsedRealtime()
                reoffered.remove(ft.id)
            }
            if (!ft.isComplete()) setProgress(ft, ft.fileSize, force = true)
            TransferMonitor.set(TransferMonitor.key(ft.id), ft.fileSize) // для итога «Загружено» в шторке
            TransferMonitor.finish(TransferMonitor.key(ft.id))
            if (ft.fileKind == FileKind.Avatar.ordinal) {
                // Новое имя при каждом обновлении - иначе экран показал бы старую картинку из кэша.
                val target = avatar("${ft.fileName}_${System.currentTimeMillis()}")
                wipAvatar(ft.fileName).copyTo(target, overwrite = true)
                wipAvatar(ft.fileName).delete()
                target.parentFile?.listFiles()?.kForEach { old ->
                    if (old != target && (old.name == ft.fileName || old.name.startsWith(ft.fileName + "_"))) old.delete()
                }
                contactRepository.setAvatarUri(ft.publicKey, Uri.fromFile(target).toString())
            } else {
                runCatching { onIncomingComplete?.invoke(ft) }
            }
            fileTransfers.remove(ft)
        }
    }

    fun transfersFor(publicKey: PublicKey) = fileTransferRepository.get(publicKey.string())

    /**
     * Имя и размер файла по адресу. Размер берётся и из самого файла, если источник его не сообщает
     * (раньше такой файл уходил с размером 0 - у собеседника появлялась «пустышка»). null - файла нет.
     */
    fun describe(file: Uri): Pair<String, Long>? {
        if (file.scheme == "file") {
            val f = File(file.path ?: return null)
            return if (f.isFile) Pair(f.name, f.length()) else null
        }
        val info = try {
            resolver.query(file, null, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                Pair(name, size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "query $file: $e")
            null
        }
        var size = info?.second ?: -1L
        if (size <= 0) {
            size = try {
                resolver.openAssetFileDescriptor(file, "r")?.use { it.length } ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
        if (info == null && size < 0) return null
        val name = info?.first?.takeIf { it.isNotBlank() } ?: file.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
        return Pair(name, size)
    }

    /**
     * [showInChat] = false - не добавлять строку в чат (например, для историй).
     * [displayName] - имя, под которым файл увидит собеседник (пересылка принятого файла: на диске
     * он лежит под служебным именем без расширения). Возвращает id или -1 (файла нет или он пустой).
     */
    fun create(pk: PublicKey, file: Uri, showInChat: Boolean = true, displayName: String? = null): Int {
        val (realName, size) = describe(file) ?: run {
            Log.e(TAG, "Unable to read $file")
            return -1
        }
        if (size <= 0) {
            // Пустой или исчезнувший файл не отправляем: у собеседника был бы белый файл без содержимого.
            Log.e(TAG, "Refusing to send empty file $file")
            return -1
        }
        val name = displayName?.trim()?.replace('/', '_')?.ifBlank { null } ?: realName

        // Двойное нажатие «Отправить», один и тот же файл дважды от выбора файлов - второй не создаём.
        val dupKey = "${pk.string()}|$file|$size|$showInChat"
        val now = SystemClock.elapsedRealtime()
        synchronized(recentCreates) {
            recentCreates.entries.removeAll { now - it.value.first > DOUBLE_SEND_MS }
            recentCreates[dupKey]?.let { (_, prevId) ->
                Log.w(TAG, "Ignoring duplicate send of $file to ${pk.fingerprint()}")
                return prevId
            }
            recentCreates[dupKey] = Pair(now, -1)
        }

        val fileNo = tox.sendFile(pk, FileKind.Data, size, name)
        val ft = FileTransfer(
            pk.string(),
            fileNo,
            FileKind.Data.ordinal,
            size,
            name,
            true,
            FT_NOT_STARTED,
            file.toString(),
        )
        val id = fileTransferRepository.add(ft).toInt()
        synchronized(recentCreates) {
            if (recentCreates[dupKey]?.first == now) recentCreates[dupKey] = Pair(now, id)
        }
        if (showInChat) {
            messageRepository.addFileMessageOnce(
                Message(ft.publicKey, ft.fileName, Sender.Sent, MessageType.FileTransfer, id, Date().time),
            )
        }
        if (fileNo < 0) {
            // Собеседник не в сети: файл (голосовое, фото) ждёт и уйдёт сам при подключении.
            Log.i(TAG, "Contact offline, queued ft $id for ${pk.fingerprint()}")
            return id
        }
        start(ft.copy().apply { this.id = id }, file)
        return id
    }

    private fun start(ft: FileTransfer, file: Uri) {
        fileTransfers.add(ft)
        val inputStream = try {
            resolver.openInputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open $file: $e")
            null
        }
        if (inputStream == null) {
            reject(ft)
            return
        }
        // Чтение с опережением в фоновом потоке (см. TransferIo.kt) - ядро не ждёт память телефона.
        outgoingFiles[Pair(ft.publicKey, ft.fileNumber)] = OutgoingFile(PrefetchInputStream(inputStream), mutableListOf())
        TransferMonitor.start(TransferMonitor.key(ft.id), ft.fileSize, true, label = ft.fileName, peer = ft.publicKey)
    }

    /**
     * Отправка своего аватара собеседнику (протокол аватаров ядра, сквозное шифрование).
     * file == null - аватар удалён: отправляем «пустой» аватар.
     */
    fun sendAvatar(pk: PublicKey, file: File?) {
        // Незаконченная прошлая отправка аватара этому собеседнику больше не нужна.
        fileTransfers.filter { it.publicKey == pk.string() && it.outgoing && it.fileKind == FileKind.Avatar.ordinal }
            .kForEach { reject(it) }
        if (file == null || !file.exists() || file.length() == 0L) {
            tox.sendFile(pk, FileKind.Avatar, 0, "")
            return
        }
        val size = file.length()
        if (size > MAX_AVATAR_SIZE) {
            Log.e(TAG, "Own avatar too big: $size")
            return
        }
        val fileNo = tox.sendFile(pk, FileKind.Avatar, size, file.name)
        if (fileNo < 0) return
        val ft = FileTransfer(
            pk.string(),
            fileNo,
            FileKind.Avatar.ordinal,
            size,
            file.name,
            true,
            FT_NOT_STARTED,
            Uri.fromFile(file).toString(),
        )
        fileTransfers.add(ft)
        outgoingFiles[Pair(ft.publicKey, fileNo)] = OutgoingFile(file.inputStream().buffered(1024 * 1024), mutableListOf())
    }

    /** Файл внутри приложения (голосовое, снимок, копия из галереи) - его можно отправить повторно в любой момент. */
    private fun isOwnFile(uri: Uri): Boolean =
        uri.scheme == "file" || (uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider")

    /**
     * Собеседник снова в сети: досылаем свои файлы, которые не успели уйти
     * (были в очереди или оборвались из-за потери связи).
     */
    suspend fun retryOutgoing(pk: PublicKey) = retryOutgoing(pk, 0)

    private suspend fun retryOutgoing(pk: PublicKey, attempt: Int) = retryMutex.withLock { retryOutgoingLocked(pk, attempt) }

    private suspend fun retryOutgoingLocked(pk: PublicKey, attempt: Int) {
        val pending = fileTransferRepository.get(pk.string()).first().filter { ft ->
            // Любой свой файл (из приложения или из галереи - пока есть доступ), кроме отменённых
            // и отклонённых: собеседник докачает его с места обрыва.
            ft.outgoing && ft.fileKind == FileKind.Data.ordinal && ft.id !in cancelledByUser &&
                !ft.isComplete() && !ft.isRejected() && fileTransfers.none { it.id == ft.id }
        }
        for (old in pending) {
            // Пока ждали, файл мог уже уйти (кнопка «Повторить», просьба получателя) - второй раз не предлагаем.
            if (fileTransfers.any { it.id == old.id && it.outgoing }) continue
            val uri = old.destination.toUri()
            val exists = try {
                resolver.openInputStream(uri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            if (!exists) continue
            val fileNo = tox.sendFile(pk, FileKind.Data, old.fileSize, old.fileName)
            if (fileNo < 0) {
                // Сразу после «в сети» ядро ещё может не принять файл - повторяем через 2, 5, 10, 20, 40 с
                // (раньше файл так и оставался «уйдёт, когда собеседник будет в сети»).
                val delays = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000)
                if (attempt < delays.size) {
                    scope.launch {
                        kotlinx.coroutines.delay(delays[attempt])
                        retryOutgoing(pk, attempt + 1)
                    }
                }
                return
            }
            Log.i(TAG, "Retrying ft ${old.id} for ${pk.fingerprint()} as $fileNo")
            val ft = old.copy(fileNumber = fileNo, progress = FT_NOT_STARTED).apply { id = old.id }
            fileTransferRepository.add(ft)
            start(ft, uri)
        }
    }

    /**
     * Отправить свой файл заново (кнопка «Повторить» или просьба получателя): текущая
     * попытка тихо бросается, получатель докачает с места обрыва.
     */
    fun restartOutgoing(id: Int, evenIfComplete: Boolean = false) {
        scope.launch {
            val ft = fileTransferRepository.get(id).first() ?: return@launch
            if (!ft.outgoing || ft.fileKind != FileKind.Data.ordinal) return@launch
            // Уже доставленный файл заново не предлагаем (иначе у получателя появлялась вторая копия),
            // кроме явной просьбы получателя - у него файл не докачался.
            if (ft.isComplete() && !evenIfComplete) return@launch
            cancelledByUser.remove(id)
            fileTransfers.filter { it.id == id && it.outgoing }.kForEach { cur ->
                fileTransfers.remove(cur)
                outgoingFiles.remove(Pair(cur.publicKey, cur.fileNumber))?.inputStream?.let { runCatching { it.close() } }
                TransferMonitor.finish(TransferMonitor.key(id))
            }
            fileTransferRepository.updateProgress(id, FT_NOT_STARTED)
            lastActivity[id] = SystemClock.elapsedRealtime()
            retryOutgoing(PublicKey(ft.publicKey))
        }
    }

    /**
     * Получатель: файл не докачался - просим отправителя прислать его снова (скрытым
     * служебным сообщением). Уже принятая часть сохраняется и докачивается с места обрыва.
     */
    fun requestResend(id: Int) {
        scope.launch {
            val ft = fileTransferRepository.get(id).first() ?: return@launch
            if (ft.outgoing || ft.fileKind != FileKind.Data.ordinal || ft.isComplete()) return@launch
            cancelledByUser.remove(id)
            fileTransfers.filter { it.id == id && !it.outgoing }.kForEach { cur ->
                val path = cur.destination.toUri().path
                if (cur.isStarted() && cur.progress > 0 && path != null && File(path).exists()) rememberPartial(cur, path)
                fileTransfers.remove(cur)
                closeIncoming(cur.publicKey, cur.fileNumber)
            }
            if (ft.isRejected()) fileTransferRepository.updateProgress(id, FT_NOT_STARTED)
            resendAsked[id] = SystemClock.elapsedRealtime()
            val body = org.json.JSONObject().put("n", ft.fileName).put("s", ft.fileSize).toString()
            runCatching { tox.sendMessage(PublicKey(ft.publicKey), RESEND_PREFIX + body, MessageType.Normal) }
        }
    }

    /** Пришла просьба получателя прислать файл снова. */
    fun onResendMessage(pk: String, text: String) {
        val o = runCatching { org.json.JSONObject(text.removePrefix(RESEND_PREFIX)) }.getOrNull() ?: return
        val name = o.optString("n")
        val size = o.optLong("s", -1)
        scope.launch {
            val same = fileTransferRepository.get(pk).first().filter {
                it.outgoing && it.fileKind == FileKind.Data.ordinal && it.fileName == name && it.fileSize == size
            }
            // Сначала - недошедшая копия: раньше брался последний файл с таким именем, и уже
            // доставленный уходил второй раз.
            val ft = same.lastOrNull { !it.isComplete() } ?: same.lastOrNull() ?: return@launch
            val readable = try {
                resolver.openInputStream(ft.destination.toUri())?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            if (readable) restartOutgoing(ft.id, evenIfComplete = true)
        }
    }

    /** Сторож зависших передач (раз в 15 с). */
    private fun checkStalled() {
        val now = SystemClock.elapsedRealtime()
        for (ft in fileTransfers.toList()) {
            if (ft.fileKind != FileKind.Data.ordinal || ft.isComplete() || ft.isRejected()) continue
            if (ft.id == DISCARD_ID) continue
            val last = lastActivity.getOrPut(ft.id) { now }
            if (now - last < STALL_MS) continue
            if (ft.outgoing) {
                // Ждём решения получателя (не начато) - это не зависание.
                if (ft.progress == FT_NOT_STARTED) continue
                Log.i(TAG, "Outgoing ft ${ft.id} stalled - restarting")
                lastActivity[ft.id] = now
                restartOutgoing(ft.id)
            } else if (ft.isStarted() && now - (resendAsked[ft.id] ?: 0L) > STALL_MS * 2) {
                Log.i(TAG, "Incoming ft ${ft.id} stalled - asking to resend")
                lastActivity[ft.id] = now
                requestResend(ft.id)
            }
        }
    }

    // Отменённые вручную - не досылать.
    private val cancelledByUser = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun markCancelledByUser(id: Int) {
        cancelledByUser.add(id)
    }

    /**
     * Читает ровно length байт в буфер файла и возвращает, сколько прочитано (-1 - не удалось догнать
     * позицию). InputStream.read может вернуть меньше - раньше из-за этого портились файлы.
     */
    private fun readChunk(file: OutgoingFile, pos: Long, length: Int): Int {
        if (pos > file.readPos) {
            // Ядро пропустило участок (бывает после паузы) - догоняем.
            var skip = pos - file.readPos
            while (skip > 0) {
                val s = file.inputStream.skip(skip)
                if (s <= 0) return -1
                skip -= s
                file.readPos += s
            }
        }
        if (file.scratch.size < length) file.scratch = ByteArray(length)
        val bytes = file.scratch
        var off = 0
        while (off < length) {
            val n = file.inputStream.read(bytes, off, length - off)
            if (n < 0) break
            off += n
        }
        file.readPos += off
        return off
    }

    // TODO(robinlinden): Handle seek-backs: https://github.com/TokTok/c-toxcore/blob/eeaa039222e7a123c2585c8486ee965017767209/toxcore/tox.h#L2405-L2406
    fun sendChunk(pk: String, fileNo: Int, pos: Long, length: Int) {
        val ft = fileTransfers.find { it.publicKey == pk && it.fileNumber == fileNo && it.outgoing }
        if (ft == null) {
            Log.e(TAG, "Received request for chunk of unknown ft ${pk.fingerprint()} $fileNo")
            tox.stopFileTransfer(PublicKey(pk), fileNo)
            return
        }

        if (length == 0) {
            Log.i(TAG, "Finished outgoing ft ${pk.fingerprint()} $fileNo ${ft.isComplete()}")
            fileTransfers.remove(ft)
            if (!ft.isComplete()) setProgress(ft, ft.fileSize, force = true)
            TransferMonitor.set(TransferMonitor.key(ft.id), ft.fileSize) // для итога «Отправлено» в шторке
            TransferMonitor.finish(TransferMonitor.key(ft.id))
            outgoingFiles.remove(Pair(pk, fileNo))?.inputStream?.let { runCatching { it.close() } }
            // Доступ к отправленному файлу из папки телефона НЕ отзываем: иначе по нажатию
            // на свой файл (например, APK) приложение падало с SecurityException.
            return
        }

        val file = outgoingFiles[Pair(pk, fileNo)] ?: return

        while (file.unsentChunks.isNotEmpty()) {
            val chunk = file.unsentChunks.first()
            Log.i(TAG, "Resending chunk @ ${chunk.pos} to ${pk.fingerprint()} ($fileNo)}")
            if (tox.sendFileChunk(PublicKey(pk), fileNo, chunk.pos, chunk.data).isFailure) {
                return
            }
            setProgress(ft, maxOf(ft.progress, chunk.pos + chunk.data.size))
            TransferMonitor.set(TransferMonitor.key(ft.id), ft.progress)
            file.unsentChunks.removeAt(0)
        }

        val n = try {
            readChunk(file, pos, length)
        } catch (e: Exception) {
            Log.e(TAG, "Read failed for ft ${ft.id}: $e")
            -1
        }
        if (n <= 0) {
            // Файл исчез или стал короче - прекращаем, иначе передача зависла бы навсегда.
            reject(ft)
            return
        }
        if (tox.sendFileChunk(PublicKey(pk), fileNo, pos, file.scratch, n).isFailure) {
            // Только неудачный кусок копируем - буфер файла сразу пойдёт под следующий.
            file.unsentChunks.add(Chunk(pos, file.scratch.copyOf(n)))
            return
        }

        // По позиции: при докачке собеседник просит продолжение с середины - прогресс сразу верный.
        setProgress(ft, maxOf(ft.progress, pos + n))
        if (ft.fileKind == FileKind.Data.ordinal) TransferMonitor.set(TransferMonitor.key(ft.id), ft.progress)
    }

    fun setStatus(pk: String, fileNo: Int, fileStatus: ToxFileControl) {
        Log.i(TAG, "Setting ${pk.fingerprint()} $fileNo to status $fileStatus")
        val ft = fileTransfers.find { it.publicKey == pk && it.fileNumber == fileNo }
        if (ft == null) {
            Log.e(TAG, "Attempted to set status for unknown ft ${pk.fingerprint()} $fileNo")
            return
        }

        if (fileStatus == ToxFileControl.RESUME && ft.progress == FT_NOT_STARTED) {
            setProgress(ft, FT_STARTED, force = true)
        } else if (fileStatus == ToxFileControl.CANCEL) {
            Log.i(TAG, "Friend canceled ft ${pk.fingerprint()} $fileNo")
            reject(ft)
        }
    }

    suspend fun deleteAll(publicKey: PublicKey) {
        fileTransferRepository.get(publicKey.string()).take(1).collect { fts ->
            fts.kForEach { delete(it.id) }
        }
    }

    suspend fun delete(id: Int) {
        fileTransfers.find { it.id == id }?.let {
            if (it.isStarted() && !it.isComplete()) {
                reject(it)
            }
            fileTransfers.remove(it)
        }
        TransferMonitor.finish(TransferMonitor.key(id))
        fileTransferRepository.get(id).take(1).collect {
            if (it != null) forgetPartial(it)
            if (it != null && !it.outgoing && it.destination.startsWith("file://")) {
                it.destination.toUri().path?.let { p -> File(p).delete() }
            }
            fileTransferRepository.delete(id)
        }
    }

    fun get(id: Int) = fileTransferRepository.get(id)

    private fun releaseFilePermission(uri: Uri) {
        if (fileTransfers.firstOrNull { it.destination == uri.toString() } != null) {
            return
        }

        Log.i(TAG, "Releasing read permission for $uri")
        // У своих файлов (голосовые) постоянного разрешения нет - это не ошибка.
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.i(TAG, "No persisted permission for $uri")
        }
    }

    private fun makeDestination(ft: FileTransfer) =
        Uri.fromFile(File(File(File(context.filesDir, "ft"), ft.publicKey.fingerprint()), Random.nextLong().toString()))

    private fun wipAvatar(name: String): File = File(File(context.filesDir, "avatar"), "$name.wip")
    private fun avatar(name: String): File = File(File(context.filesDir, "avatar"), name)
}
