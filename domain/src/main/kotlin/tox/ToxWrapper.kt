// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2026 StoykoMessenger
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.util.AtomicFile
import android.util.Log
import com.stoyko.smessenger.domain.Diagnostics
import com.zoffcc.applications.trifa.MainActivity
import com.zoffcc.applications.trifa.TrifaCallbacks
import com.zoffcc.applications.trifa.TrifaToxService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random
import com.stoyko.smessenger.core.vo.FileKind
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.UserStatus

private const val TAG = "ToxWrapper"

// Opus 32 кбит/с: полноценный широкополосный голос, а пакеты меньше - устойчивее на LTE и
// через релеи (дальше ядро само подстраивает битрейт под связь).
private const val AUDIO_BIT_RATE = 32L

/** Wi-Fi - звук звонка качественнее (48 кбит/с), мобильная - 32 (дальше подстраивается по сети). */
private fun audioBitRate() = if (com.stoyko.smessenger.domain.feature.NetHint.wifi) 48L else AUDIO_BIT_RATE

// Видео, кбит/с: баланс качества и мобильной сети.
/** Битрейт видео в звонках, кбит/с (меняется настройкой «Качество видео»). */
object VideoQuality {
    @Volatile
    var bitRate = 400L
}
private const val VIDEO_IN_MAX_W = 1920
private const val VIDEO_IN_MAX_H = 1088
private const val VIDEO_IN_BYTES = 4096 * 1088 * 2
const val VIDEO_OUT_MAX_W = 1280
const val VIDEO_OUT_MAX_H = 720

// Сколько миллисекунд звука забирать из нативного буфера за раз.
private const val AUDIO_PULL_MS = 20
private const val AUDIO_MAX_PULLS_PER_ITERATION = 6

// 120 мс стерео 48 кГц int16 = 23 040 байт; берём с запасом.
private const val AUDIO_BUFFER_BYTES = 64 * 1024
private const val FILE_ID_LENGTH = 32
private const val SAVE_FILE_NAME = "savedata.tox"

private const val FRIEND_NOT_FOUND = -1L

const val GROUP_CHAT_ID_SIZE = 32
const val GROUP_MAX_NAME_BYTES = 48
const val MAX_NAME_BYTES = 128
const val GROUP_PRIVACY_PUBLIC = 0
const val GROUP_ROLE_FOUNDER = 0
const val GROUP_ROLE_MODERATOR = 1
const val GROUP_ROLE_USER = 2
const val GROUP_ROLE_OBSERVER = 3
const val GROUP_AUDIO_SAMPLE_RATE = 48_000
const val GROUP_AUDIO_FRAME_SAMPLES = 1920

/** Первый байт служебного пакета со звуком группового звонка. */
const val GROUP_PKT_AUDIO: Byte = 32

enum class CustomPacketError {
    Success,
    Empty,
    FriendNotConnected,
    FriendNotFound,
    Invalid,
    Null,
    Sendq,
    TooLong,
}

/**
 * Ядро Tox поверх готовой нативной библиотеки TRIfA (libjni-c-toxcore.so).
 *
 * Важно: библиотека держит ОДИН глобальный экземпляр Tox на процесс, поэтому
 * одновременно может существовать только один ToxWrapper. Все обращения к ядру
 * идут под [lock]: toxcore не потокобезопасен, а aTox вызывает его из разных потоков.
 *
 * Профиль хранится нативной библиотекой в файле [dataDir]/savedata.tox и шифруется
 * паролем (tox_pass_encrypt), если он задан.
 */
class ToxWrapper(
    private val eventListener: ToxEventListener,
    private val avEventListener: ToxAvEventListener,
    private val groupListener: GroupEventListener,
    options: SaveOptions,
    private val dataDir: File,
    password: String?,
) {
    private companion object {
        val lock = Any()

        @Volatile
        var alive: ToxWrapper? = null

        /** Нативное ядро запущено (init вызван, tox_kill ещё нет). tox_kill дважды вызывать нельзя. */
        @Volatile
        var nativeRunning = false

        fun killNative() {
            if (!nativeRunning) return
            nativeRunning = false
            try {
                MainActivity.tox_kill()
            } catch (t: Throwable) {
                Log.e(TAG, "tox_kill failed", t)
            }
        }
    }

    /** Пароль профиля (пустая строка - без шифрования). */
    @Volatile
    var passphrase: String = password.orEmpty()

    private val saveFile = File(dataDir, SAVE_FILE_NAME)
    private var status = UserStatus.None

    // События звонков приходят из нативных AV-потоков; обрабатываем их в своём потоке,
    // чтобы не держать нативные мьютексы, пока работает код приложения.
    private val avExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "tox-av-events") }

    @Volatile
    private var callFriend: Long = FRIEND_NOT_FOUND

    private val audioOut: ByteBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES).order(ByteOrder.nativeOrder())
    private val audioIn: ByteBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES).order(ByteOrder.nativeOrder())
    private var inChannels = 1
    private var inSamplingRate = 48_000

    private class AudioFrame(val friend: Long, val pcm: ShortArray, val channels: Int, val samplingRate: Int)

    private var pendingAudio: AudioFrame? = null

    // Видео: приёмный буфер с запасом под stride (до 1920x1088), отправка - до 1280x720.
    private val videoIn: ByteBuffer = ByteBuffer.allocateDirect(VIDEO_IN_BYTES)
    private val videoOut: ByteBuffer = ByteBuffer.allocateDirect(VIDEO_OUT_MAX_W * VIDEO_OUT_MAX_H * 3 / 2)

    private val fileIdBuffer: ByteBuffer = ByteBuffer.allocateDirect(FILE_ID_LENGTH)
    private var chunkBuffer: ByteBuffer = ByteBuffer.allocateDirect(2048)

    // Поля групп объявлены до init: колбэки ядра могут прийти сразу после запуска.
    private val chatIdBuffer: ByteBuffer = ByteBuffer.allocateDirect(GROUP_CHAT_ID_SIZE)

    // Номер группы постоянен, пока работает ядро; кэшируем, чтобы не дёргать JNI на каждый пакет звука.
    private val chatIdCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val peerKeyCache = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, String>>()
    private val peerNameCache = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, String>>()

    private val opusLock = Any()

    /**
     * Последние статусы подключения, сообщённые ядром (номер друга -> TOX_CONNECTION).
     * Нужны для сверки в [syncConnectionStatus]: досылаются только отличия.
     */
    private val friendConnection = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    @Volatile
    private var selfConnection = 0

    private val callbacks = object : TrifaCallbacks() {
        override fun selfConnectionStatus(connection: Int) {
            selfConnection = connection
            eventListener.selfConnectionStatus(connection)
        }
        override fun friendName(friend: Long, name: String?) = eventListener.friendName(friend, name.orEmpty())
        override fun friendStatusMessage(friend: Long, message: String?) =
            eventListener.friendStatusMessage(friend, message.orEmpty())

        override fun friendLosslessPacket(friend: Long, data: ByteArray?) {
            if (data != null) eventListener.friendLosslessPacket(friend, data)
        }

        override fun friendStatus(friend: Long, status: Int) = eventListener.friendStatus(friend, status)
        override fun friendConnectionStatus(friend: Long, connection: Int) {
            friendConnection[friend] = connection
            eventListener.friendConnectionStatus(friend, connection)
        }

        override fun friendTyping(friend: Long, typing: Boolean) = eventListener.friendTyping(friend, typing)
        override fun friendReadReceipt(friend: Long, messageId: Long) =
            eventListener.friendReadReceipt(friend, messageId)

        override fun friendRequest(publicKeyHex: String?, message: String?) {
            if (publicKeyHex != null) eventListener.friendRequest(publicKeyHex, message.orEmpty())
        }

        override fun friendMessage(friend: Long, type: Int, message: String?) =
            eventListener.friendMessage(friend, type, message.orEmpty())

        override fun fileRecvControl(friend: Long, file: Long, control: Int) =
            eventListener.fileRecvControl(friend, file, control)

        override fun fileChunkRequest(friend: Long, file: Long, position: Long, length: Long) =
            eventListener.fileChunkRequest(friend, file, position, length)

        override fun fileRecv(friend: Long, file: Long, kind: Int, size: Long, name: String?) =
            eventListener.fileRecv(friend, file, kind, size, name.orEmpty())

        override fun fileRecvChunk(friend: Long, file: Long, position: Long, data: ByteArray?) =
            eventListener.fileRecvChunk(friend, file, position, data ?: ByteArray(0))

        override fun toxLog(level: Int, file: String?, line: Long, function: String?, message: String?) {
            // TOX_LOG_LEVEL: 0 TRACE, 1 DEBUG, 2 INFO, 3 WARNING, 4 ERROR
            if (level >= 3) Log.w("toxcore", "$file:$line $function: $message")
        }

        override fun avCall(friend: Long, audio: Boolean, video: Boolean) {
            avExecutor.execute { avEventListener.call(friend, audio, video) }
        }

        override fun avCallState(friend: Long, state: Int) {
            val finished = state and (ToxavFriendCallState.ERROR.bit or ToxavFriendCallState.FINISHED.bit) != 0
            if (finished && callFriend == friend) {
                callFriend = FRIEND_NOT_FOUND
                MainActivity.set_av_call_status(0)
            }
            avExecutor.execute { avEventListener.callState(friend, state) }
        }

        override fun avBitRate(friend: Long, audioBitRate: Long, videoBitRate: Long) {
            avExecutor.execute { avEventListener.bitRate(friend, audioBitRate, videoBitRate) }
        }

        // ---- группы и каналы ----
        override fun groupMessage(group: Long, peer: Long, type: Int, message: String?) {
            val chatId = groupChatId(group) ?: return
            groupListener.onMessage(chatId, peer, peerKey(group, peer), peerName(group, peer), message.orEmpty())
        }

        override fun groupInvite(friend: Long, inviteData: ByteArray?, groupName: String?) {
            if (inviteData != null) groupListener.onInvite(friend, inviteData, groupName.orEmpty())
        }

        override fun groupPeerJoin(group: Long, peer: Long) {
            groupChatId(group)?.let { groupListener.onPeerJoin(it, peer) }
        }

        override fun groupPeerName(group: Long, peer: Long) {
            peerNameCache[group]?.remove(peer)
        }

        override fun groupPeerExit(group: Long, peer: Long) {
            peerKeyCache[group]?.remove(peer) // номер участника может достаться другому
            peerNameCache[group]?.remove(peer)
            groupChatId(group)?.let { groupListener.onPeerExit(it, peer) }
        }

        override fun groupSelfJoin(group: Long) {
            groupChatId(group)?.let { groupListener.onSelfJoin(it) }
        }

        override fun groupCustomPacket(group: Long, peer: Long, data: ByteArray?) {
            if (data == null || data.isEmpty()) return
            val chatId = groupChatId(group) ?: return
            groupListener.onCustomPacket(chatId, peer, peerKey(group, peer), peerName(group, peer), data)
        }

        // Кадр видео: вызывается из видеопотока ядра, буфер валиден только во время вызова.
        override fun avVideoFrame(friend: Long, width: Long, height: Long, yStride: Long, uStride: Long, vStride: Long) {
            if (callFriend != friend) return
            val w = width.toInt()
            val h = height.toInt()
            val frame = copyIncomingFrame(w, h, yStride.toInt(), uStride.toInt(), vStride.toInt()) ?: return
            avEventListener.videoFrame(friend, w, h, frame)
        }

        // Вызывается синхронно изнутри jni_iterate_videocall_audio() под нативным мьютексом:
        // только копируем данные, обработка - после возврата из нативного вызова.
        override fun avAudioFrame(friend: Long, sampleCount: Long, channels: Int, samplingRate: Long) {
            if (sampleCount <= 0 || channels <= 0 || samplingRate <= 0) return
            val count = (sampleCount * channels).toInt().coerceAtMost(audioIn.capacity() / 2)
            val pcm = ShortArray(count)
            audioIn.asShortBuffer().get(pcm, 0, count)
            inChannels = channels
            inSamplingRate = samplingRate.toInt()
            pendingAudio = AudioFrame(friend, pcm, channels, samplingRate.toInt())
        }
    }

    init {
        try {
            Diagnostics.track("загрузка нативной библиотеки") { MainActivity.loadLibrary() }
        } catch (e: UnsatisfiedLinkError) {
            throw ToxInitException(ToxSaveStatus.Null, "Native toxcore library missing: $e")
        }

        val save = options.saveData
        if (save != null && !save.isPlainToxSave() && !save.isEncryptedToxSave()) {
            throw ToxInitException(ToxSaveStatus.BadFormat, "Not a Tox profile")
        }
        // Нативная библиотека шифрует профиль ВСЕГДА, в том числе пустым паролем.
        // Поэтому зашифрованный профиль без пароля не отвергаем сразу: пробуем открыть
        // его пустым паролем, а неверный пароль ловим ниже по логу расшифровки.
        if (options.proxyType == ProxyType.HTTP) {
            throw ToxInitException(ToxSaveStatus.BadProxyType, "Only SOCKS5 proxies are supported")
        }
        val useProxy = options.proxyType == ProxyType.SOCKS5
        if (useProxy && options.proxyAddress.isBlank()) {
            throw ToxInitException(ToxSaveStatus.BadProxyHost, "Empty proxy host")
        }
        if (useProxy && options.proxyPort !in 1..65535) {
            throw ToxInitException(ToxSaveStatus.BadProxyPort, "Bad proxy port")
        }

        synchronized(lock) {
            alive?.let {
                Log.w(TAG, "Previous Tox instance still alive, stopping it")
                it.stop()
            }

            dataDir.mkdirs()
            if (save == null) {
                saveFile.delete()
            } else {
                AtomicFile(saveFile).run {
                    val out = startWrite()
                    try {
                        out.write(save)
                        finishWrite(out)
                    } catch (e: Exception) {
                        failWrite(out)
                        throw e
                    }
                }
            }

            // Прошлый неудачный запуск мог оставить ядро работать - сначала останавливаем его.
            killNative()
            try {
                MainActivity.callbacks = callbacks
                TrifaToxService.decryptFailed = false
                Log.i(TAG, "Starting toxcore ${MainActivity.jnictoxcore_version()} udp=${options.udpEnabled} proxy=$useProxy")
                nativeRunning = true
                Diagnostics.track("нативный init (создание ядра, ключей и профиля)") {
                MainActivity().init(
                    dataDir.absolutePath,
                    if (options.udpEnabled) 1 else 0,
                    1, // поиск собеседников в локальной сети
                    if (useProxy) 1 else 0,
                    options.proxyAddress,
                    options.proxyPort.toLong(),
                    passphrase,
                    1, // IPv6
                    0, // force UDP only
                    128, // ngc video bitrate (групповые звонки не используются)
                    40, // max quantizer
                    16_000, // битрейт группового звука, бит/с
                    48_000,
                    1,
                )
                }

                val id = Diagnostics.track("чтение ID профиля") { MainActivity.get_my_toxid() }
                if (id == null || id.length < 76) {
                    MainActivity.callbacks = null
                    throw ToxInitException(ToxSaveStatus.Null, "tox_new failed")
                }

                if (save != null && save.isEncryptedToxSave() && TrifaToxService.decryptFailed) {
                    // Неверный пароль: библиотека создала бы НОВЫЙ профиль - убиваем его и
                    // возвращаем исходный файл.
                    MainActivity.callbacks = null
                    killNative()
                    saveFile.writeBytes(save)
                    throw ToxInitException(ToxSaveStatus.Encrypted, "Wrong password")
                }

                Diagnostics.track("подключение обработчиков событий ядра") {
                    MainActivity.init_tox_callbacks()
                    MainActivity.set_JNI_audio_buffer(audioOut)
                    MainActivity.set_JNI_audio_buffer2(audioIn)
                    MainActivity.set_JNI_video_buffer(videoIn, VIDEO_IN_MAX_W, VIDEO_IN_MAX_H)
                    MainActivity.set_JNI_video_buffer2(videoOut, VIDEO_OUT_MAX_W, VIDEO_OUT_MAX_H)
                }
            } catch (t: Throwable) {
                // Любая ошибка после запуска ядра: полностью его останавливаем, иначе повторная
                // попытка запустила бы второе ядро поверх первого - и приложение упало бы.
                MainActivity.callbacks = null
                killNative()
                if (t is ToxInitException) throw t
                throw ToxInitException(ToxSaveStatus.Null, "Ошибка запуска ядра: $t")
            }
            alive = this
        }

        updateContactMapping()
    }

    private inline fun <T> core(block: () -> T): T = synchronized(lock) { block() }

    private fun updateContactMapping() {
        val contacts = getContacts()
        eventListener.contactMapping = contacts
        avEventListener.contactMapping = contacts
    }

    fun bootstrap(address: String, port: Int, publicKey: ByteArray): Boolean =
        core { MainActivity.bootstrap_single(address, publicKey.bytesToHex(), port.toLong()) == 0 }

    fun addTcpRelay(address: String, port: Int, publicKey: ByteArray): Boolean =
        core { MainActivity.add_tcp_relay_single(address, publicKey.bytesToHex(), port.toLong()) == 0 }

    fun stop() = core {
        if (alive !== this) return@core
        callFriend = FRIEND_NOT_FOUND
        MainActivity.set_av_call_status(0)
        MainActivity.callbacks = null
        killNative()
        alive = null
        forgetGroupCaches()
        avExecutor.shutdown()
        Log.i(TAG, "Killed Tox")
    }

    fun iterate() = core { MainActivity.tox_iterate() }

    /**
     * Сверка статусов «в сети» с ядром. Событие о смене подключения приходит только один раз
     * и может потеряться (гонка при запуске, сон телефона, обрыв во время перезапуска) - тогда
     * контакт навсегда оставался «в сети» или «не в сети». Спрашиваем ядро напрямую
     * (tox_self_get_connection_status / tox_friend_get_connection_status) и отличия отдаём
     * тем же путём, что и обычные события ядра. Вызывать из потока цикла ядра.
     */
    fun syncConnectionStatus() = core {
        if (alive !== this || !nativeRunning) return@core
        val self = MainActivity.tox_self_get_connection_status().takeIf { it in 0..2 } ?: 0
        if (self != selfConnection) {
            selfConnection = self
            eventListener.selfConnectionStatus(self)
        }
        val friends = MainActivity.tox_self_get_friend_list() ?: LongArray(0)
        for (friend in friends) {
            val now = MainActivity.tox_friend_get_connection_status(friend).takeIf { it in 0..2 } ?: 0
            if (now != (friendConnection[friend] ?: 0)) {
                friendConnection[friend] = now
                eventListener.friendConnectionStatus(friend, now)
            }
        }
    }
    fun iterationInterval(): Long = core { MainActivity.tox_iteration_interval() }.coerceIn(5, 50)

    /** Забирает принятый звук звонка из нативного буфера и отдаёт его в [ToxAvEventListener]. */
    fun iterateAv() {
        if (callFriend == FRIEND_NOT_FOUND) return
        repeat(AUDIO_MAX_PULLS_PER_ITERATION) {
            pendingAudio = null
            val res = MainActivity.jni_iterate_videocall_audio(0, AUDIO_PULL_MS, inChannels, inSamplingRate, 0)
            val frame = pendingAudio ?: return
            pendingAudio = null
            avEventListener.audioReceiveFrame(frame.friend, frame.pcm, frame.channels, frame.samplingRate)
            if (res < 0) return
        }
    }

    fun iterationIntervalAv(): Long = if (callFriend == FRIEND_NOT_FOUND) 100 else 10

    fun getName(): String = core { MainActivity.tox_self_get_name() }.orEmpty()
    fun setName(name: String) {
        core { MainActivity.tox_self_set_name(name.clean()) }
    }

    fun getStatusMessage(): String = core { MainActivity.tox_self_get_status_message() }.orEmpty()
    fun setStatusMessage(statusMessage: String) {
        core { MainActivity.tox_self_set_status_message(statusMessage.clean()) }
    }

    fun getToxId() = ToxID(core { MainActivity.get_my_toxid() }.uppercase())
    fun getPublicKey() = getToxId().toPublicKey()
    fun getNospam(): Int = core { MainActivity.tox_self_get_nospam() }.toInt()
    fun setNospam(value: Int) = core { MainActivity.tox_self_set_nospam(value.toLong() and 0xFFFFFFFFL) }

    /** Сохраняет профиль через нативную библиотеку и возвращает байты файла (зашифрованные, если есть пароль). */
    fun getSaveData(): ByteArray = core {
        Diagnostics.light("сохранение профиля (шифрование)") { MainActivity.update_savedata_file(passphrase) }
        if (!saveFile.exists() || saveFile.length() == 0L) {
            throw IllegalStateException("Нативная библиотека не записала профиль в ${saveFile.absolutePath}")
        }
        saveFile.readBytes()
    }

    fun addContact(toxId: ToxID, message: String) {
        val res = core { MainActivity.tox_friend_add(toxId.string(), message.clean()) }
        if (res < 0) Log.e(TAG, "tox_friend_add failed: $res")
        updateContactMapping()
    }

    fun deleteContact(pk: PublicKey) {
        Log.i(TAG, "Deleting ${pk.fingerprint()}")
        val friend = contactByKey(pk)
        if (friend == FRIEND_NOT_FOUND) {
            Log.e(TAG, "Tried to delete nonexistent contact, the database may be out of sync with the Tox save")
        } else {
            core { MainActivity.tox_friend_delete(friend) }
            // Номер освободился и может достаться новому другу - старый статус ему не нужен.
            friendConnection.remove(friend)
        }
        updateContactMapping()
    }

    /** Ключ друга по его номеру в ядре (null - такого нет). */
    fun friendPublicKey(number: Long): String? =
        core { MainActivity.tox_friend_get_public_key(number) }?.takeIf { it.length == 64 }?.uppercase()

    /** Номер друга в ядре по ключу (отрицательный - не найден). */
    fun friendNumber(pk: PublicKey): Long = contactByKey(pk)

    fun getContacts(): List<Pair<PublicKey, Int>> = core {
        val friends = MainActivity.tox_self_get_friend_list() ?: LongArray(0)
        Log.i(TAG, "Loading ${friends.size} friends")
        friends.toList().mapNotNull { number ->
            val pk = MainActivity.tox_friend_get_public_key(number)
            if (pk == null || pk.length != 64) null else Pair(PublicKey(pk.uppercase()), number.toInt())
        }
    }

    fun sendMessage(publicKey: PublicKey, message: String, type: MessageType): Int {
        val friend = contactByKey(publicKey)
        val res = core { MainActivity.tox_friend_send_message(friend, type.toToxType().ordinal, message.clean()) }
        if (res < 0) Log.e(TAG, "tox_friend_send_message failed: $res")
        return res.toInt()
    }

    fun acceptFriendRequest(pk: PublicKey) {
        val res = core { MainActivity.tox_friend_add_norequest(pk.string()) }
        if (res < 0) Log.e(TAG, "Error accepting friend request ${pk.fingerprint()}: $res")
        updateContactMapping()
    }

    fun startFileTransfer(pk: PublicKey, fileNumber: Int) = fileControl(pk, fileNumber, ToxFileControl.RESUME)

    /** Подстройка качества звонка на лету: -1 - не менять. */
    fun setCallBitRate(pk: PublicKey, audioKbps: Long, videoKbps: Long): Boolean = try {
        core { MainActivity.toxav_bit_rate_set(contactByKey(pk), audioKbps, videoKbps) } != 0
    } catch (e: Throwable) {
        false
    }

    /** Докачка входящего файла с позиции [position]. false - не вышло (файл пойдёт с начала). */
    fun seekFile(pk: PublicKey, fileNumber: Int, position: Long): Boolean = try {
        core { MainActivity.tox_file_seek(contactByKey(pk), fileNumber.toLong() and 0xFFFFFFFFL, position) }
        true
    } catch (e: Throwable) {
        Log.w(TAG, "tox_file_seek: $e")
        false
    }
    fun stopFileTransfer(pk: PublicKey, fileNumber: Int) = fileControl(pk, fileNumber, ToxFileControl.CANCEL)

    private fun fileControl(pk: PublicKey, fileNumber: Int, control: ToxFileControl) {
        val res = core {
            MainActivity.tox_file_control(contactByKey(pk), fileNumber.toLong() and 0xFFFFFFFFL, control.ordinal)
        }
        if (res != 0) Log.e(TAG, "tox_file_control($control) failed ${pk.fingerprint()} $fileNumber")
    }

    /** Возвращает номер передачи или -1 при ошибке. */
    fun sendFile(pk: PublicKey, fileKind: FileKind, fileSize: Long, fileName: String): Int {
        val name = fileName.clean()
        val res = core {
            fileIdBuffer.clear()
            fileIdBuffer.put(Random.nextBytes(FILE_ID_LENGTH))
            fileIdBuffer.rewind()
            MainActivity.tox_file_send(
                contactByKey(pk),
                fileKind.toToxtype().toLong(),
                fileSize,
                fileIdBuffer,
                name,
                name.toByteArray().size.toLong(),
            )
        }
        if (res < 0) {
            Log.e(TAG, "Error sending ft $fileName ${pk.fingerprint()}: $res")
            return -1
        }
        return res.toInt()
    }

    fun sendFileChunk(pk: PublicKey, fileNo: Int, pos: Long, data: ByteArray, len: Int = data.size): Result<Unit> = core {
        if (chunkBuffer.capacity() < len) chunkBuffer = ByteBuffer.allocateDirect(len)
        chunkBuffer.clear()
        chunkBuffer.put(data, 0, len)
        chunkBuffer.rewind()
        val res = MainActivity.tox_file_send_chunk(
            contactByKey(pk),
            fileNo.toLong() and 0xFFFFFFFFL,
            pos,
            chunkBuffer,
            len.toLong(),
        )
        if (res == 0) {
            Result.success(Unit)
        } else {
            Log.e(TAG, "Error sending chunk $pos:$len to ${pk.fingerprint()} $fileNo: $res")
            Result.failure(IllegalStateException("tox_file_send_chunk: $res"))
        }
    }

    fun setTyping(publicKey: PublicKey, typing: Boolean) {
        core { MainActivity.tox_self_set_typing(contactByKey(publicKey), if (typing) 1 else 0) }
    }

    fun getStatus() = status
    fun setStatus(status: UserStatus) {
        this.status = status
        core { MainActivity.tox_self_set_status(status.toToxType().ordinal) }
    }

    fun sendLosslessPacket(pk: PublicKey, packet: ByteArray): CustomPacketError {
        if (packet.isEmpty()) return CustomPacketError.Empty
        val friend = contactByKey(pk)
        if (friend == FRIEND_NOT_FOUND) return CustomPacketError.FriendNotFound
        val res = core { MainActivity.tox_friend_send_lossless_packet(friend, packet, packet.size) }
        return if (res > 0) CustomPacketError.Success else CustomPacketError.FriendNotConnected
    }

    private fun contactByKey(pk: PublicKey): Long = core { MainActivity.tox_friend_by_public_key(pk.string()) }

    // ---------------- ToxAV ----------------

    /** false - звонок не начался (например, собеседник не в сети). */
    fun startCall(pk: PublicKey, video: Boolean = false): Boolean {
        val friend = contactByKey(pk)
        if (friend == FRIEND_NOT_FOUND) return false
        val ok = core { MainActivity.toxav_call(friend, audioBitRate(), if (video) VideoQuality.bitRate else 0) } != 0
        if (!ok) {
            Log.e(TAG, "toxav_call failed for ${pk.fingerprint()}")
            return false
        }
        beginCall(friend)
        return true
    }

    fun answerCall(pk: PublicKey, video: Boolean = false) {
        val friend = contactByKey(pk)
        if (core { MainActivity.toxav_answer(friend, audioBitRate(), if (video) VideoQuality.bitRate else 0) } == 0) {
            throw ToxavAnswerException("toxav_answer failed for ${pk.fingerprint()}")
        }
        beginCall(friend)
    }

    private fun beginCall(friend: Long) {
        inChannels = 1
        inSamplingRate = 48_000
        callFriend = friend
        MainActivity.set_av_call_status(1)
    }

    fun endCall(pk: PublicKey) {
        val friend = contactByKey(pk)
        core { MainActivity.toxav_call_control(friend, ToxavCallControl.CANCEL.ordinal) }
        if (callFriend == friend) {
            callFriend = FRIEND_NOT_FOUND
            MainActivity.set_av_call_status(0)
        }
    }

    // ---------------- Видео (кодирование и сквозное шифрование делает ядро) ----------------

    /** Включить/выключить отправку видео в идущем звонке. */
    fun setVideoSending(pk: PublicKey, on: Boolean): Boolean {
        val friend = contactByKey(pk)
        if (friend == FRIEND_NOT_FOUND) return false
        // -1 для звука = «не менять».
        return core { MainActivity.toxav_bit_rate_set(friend, -1, if (on) VideoQuality.bitRate else 0) } != 0
    }

    /** Отправляет кадр I420 (Y, затем U, затем V, без отступов). Возвращает код ядра (0 - успех). */
    fun sendVideo(pk: PublicKey, width: Int, height: Int, i420: ByteArray): Int {
        val size = width * height * 3 / 2
        if (i420.size < size || size > videoOut.capacity()) return -1
        val friend = contactByKey(pk)
        if (friend == FRIEND_NOT_FOUND) return -1
        return core {
            videoOut.clear()
            videoOut.put(i420, 0, size)
            MainActivity.toxav_video_send_frame(friend, width, height)
        }
    }

    /** Копирует кадр собеседника из нативного буфера в плотный I420 (вызывается из видеопотока ядра). */
    private fun copyIncomingFrame(w: Int, h: Int, ys: Int, us: Int, vs: Int): ByteArray? {
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        val yStride = maxOf(w, kotlin.math.abs(ys))
        val uStride = maxOf(w / 2, kotlin.math.abs(us))
        val vStride = maxOf(w / 2, kotlin.math.abs(vs))
        val ySize = yStride * h
        val uSize = uStride * (h / 2)
        val vSize = vStride * (h / 2)
        if (ySize + uSize + vSize > videoIn.capacity()) return null
        val cw = w / 2
        val ch = h / 2
        val out = ByteArray(w * h + 2 * cw * ch)
        val src = videoIn.duplicate()
        for (row in 0 until h) {
            src.position(row * yStride)
            src.get(out, row * w, w)
        }
        var o = w * h
        for (row in 0 until ch) {
            src.position(ySize + row * uStride)
            src.get(out, o, cw)
            o += cw
        }
        for (row in 0 until ch) {
            src.position(ySize + uSize + row * vStride)
            src.get(out, o, cw)
            o += cw
        }
        return out
    }

    fun sendAudio(pk: PublicKey, pcm: ShortArray, channels: Int, samplingRate: Int) {
        if (pcm.isEmpty() || channels <= 0) return
        val count = pcm.size.coerceAtMost(audioOut.capacity() / 2)
        val friend = contactByKey(pk)
        core {
            audioOut.asShortBuffer().put(pcm, 0, count)
            MainActivity.toxav_audio_send_frame(friend, (count / channels).toLong(), channels, samplingRate.toLong())
        }
    }
    // ---------------- Группы и каналы (NGC, сквозное шифрование делает ядро) ----------------

    private fun forgetGroupCaches() {
        chatIdCache.clear()
        peerKeyCache.clear()
        peerNameCache.clear()
    }

    fun groupChatId(group: Long): String? {
        chatIdCache[group]?.let { return it }
        val id = core {
            chatIdBuffer.clear()
            if (MainActivity.tox_group_get_chat_id(group, chatIdBuffer) != 0) return@core null
            val bytes = ByteArray(GROUP_CHAT_ID_SIZE)
            chatIdBuffer.rewind()
            chatIdBuffer.get(bytes)
            bytes.bytesToHex()
        }
        if (id != null) chatIdCache[group] = id
        return id
    }

    fun groupNumber(chatId: String): Long {
        chatIdCache.entries.firstOrNull { it.value == chatId }?.let { return it.key }
        if (chatId.length != GROUP_CHAT_ID_SIZE * 2) return -1L
        val n = core {
            chatIdBuffer.clear()
            chatIdBuffer.put(chatId.hexToBytes())
            chatIdBuffer.rewind()
            MainActivity.tox_group_by_chat_id(chatIdBuffer)
        }
        if (n >= 0) chatIdCache[n] = chatId
        return n
    }

    private fun peerKey(group: Long, peer: Long): String {
        val perGroup = peerKeyCache.getOrPut(group) { java.util.concurrent.ConcurrentHashMap() }
        perGroup[peer]?.let { return it }
        val key = core { MainActivity.tox_group_peer_get_public_key(group, peer) }.orEmpty().uppercase()
        if (key.length == 64) perGroup[peer] = key
        return key
    }

    // Имя кэшируется (звук приходит каждые 40 мс); сбрасывается, когда участник меняет имя или выходит.
    private fun peerName(group: Long, peer: Long): String {
        val perGroup = peerNameCache.getOrPut(group) { java.util.concurrent.ConcurrentHashMap() }
        perGroup[peer]?.let { return it }
        val name = core { MainActivity.tox_group_peer_get_name(group, peer) }.orEmpty()
        if (name.isNotEmpty()) perGroup[peer] = name
        return name
    }

    /** Мой ключ внутри группы (у каждой группы свой). */
    fun groupSelfKey(chatId: String): String {
        val g = groupNumber(chatId)
        if (g < 0) return ""
        val k = core { MainActivity.tox_group_self_get_public_key(g) }.orEmpty()
        return if (k.length == 64) k.uppercase() else ""
    }

    /** Все группы, сохранённые в профиле: chatId -> название. */
    fun groups(): List<Pair<String, String>> {
        val numbers = core { MainActivity.tox_group_get_grouplist() } ?: LongArray(0)
        val result = ArrayList<Pair<String, String>>(numbers.size)
        for (n in numbers) {
            val id = groupChatId(n) ?: continue
            result.add(id to core { MainActivity.tox_group_get_name(n) }.orEmpty())
        }
        return result
    }

    /**
     * Возвращает chatId новой группы. Лимиты ядра - в БАЙТАХ UTF-8 (название 48, имя 128):
     * русская буква - 2 байта, поэтому раньше длинные русские названия отклонялись.
     */
    fun createGroup(name: String, selfName: String): String {
        val n = core {
            MainActivity.tox_group_new(
                GROUP_PRIVACY_PUBLIC,
                name.clean().utf8Limit(GROUP_MAX_NAME_BYTES),
                selfName.clean().utf8Limit(MAX_NAME_BYTES).ifEmpty { "S-messenger" },
            )
        }
        if (n < 0) throw IllegalStateException("ядро отклонило создание (код ${-n})")
        return groupChatId(n) ?: throw IllegalStateException("не удалось получить ID группы")
    }

    fun joinGroup(chatId: String, selfName: String): Boolean {
        if (chatId.length != GROUP_CHAT_ID_SIZE * 2) return false
        val res = core {
            val buf = ByteBuffer.allocateDirect(GROUP_CHAT_ID_SIZE)
            buf.put(chatId.hexToBytes())
            buf.rewind()
            MainActivity.tox_group_join(buf, GROUP_CHAT_ID_SIZE.toLong(), selfName.clean().utf8Limit(MAX_NAME_BYTES), null)
        }
        if (res < 0) Log.e(TAG, "tox_group_join failed: $res")
        return res >= 0
    }

    fun acceptGroupInvite(friend: Long, inviteData: ByteArray, selfName: String): String? {
        val n = core {
            val buf = ByteBuffer.allocateDirect(inviteData.size)
            buf.put(inviteData)
            buf.rewind()
            MainActivity.tox_group_invite_accept(friend, buf, inviteData.size.toLong(), selfName.clean().utf8Limit(MAX_NAME_BYTES), null)
        }
        if (n < 0) {
            Log.e(TAG, "tox_group_invite_accept failed: $n")
            return null
        }
        return groupChatId(n)
    }

    fun inviteToGroup(chatId: String, friendPk: PublicKey): Boolean {
        val g = groupNumber(chatId)
        val f = contactByKey(friendPk)
        if (g < 0 || f < 0) return false
        return core { MainActivity.tox_group_invite_friend(g, f) } > 0
    }

    fun leaveGroup(chatId: String) {
        val g = groupNumber(chatId)
        if (g >= 0) core { MainActivity.tox_group_leave(g, "") }
        forgetGroupCaches()
    }

    fun sendGroupMessage(chatId: String, message: String): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return core { MainActivity.tox_group_send_message(g, 0, message.clean()) } >= 0
    }

    /** true - пакет принят ядром. lossless=false - для звука (быстрее, потери допустимы). */
    fun sendGroupPacket(chatId: String, data: ByteArray, lossless: Boolean): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return core { MainActivity.tox_group_send_custom_packet(g, if (lossless) 1 else 0, data, data.size) } == 1
    }

    /** Пакет одному участнику (ответ на запрос истории). */
    fun sendGroupPrivatePacket(chatId: String, peerId: Long, data: ByteArray): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return core { MainActivity.tox_group_send_custom_private_packet(g, peerId, 1, data, data.size) } == 1
    }

    fun groupSelfRole(chatId: String): Int {
        val g = groupNumber(chatId)
        if (g < 0) return GROUP_ROLE_USER
        val role = core { MainActivity.tox_group_self_get_role(g) }
        return if (role in GROUP_ROLE_FOUNDER..GROUP_ROLE_OBSERVER) role else GROUP_ROLE_USER
    }

    fun groupPeerRole(chatId: String, peerId: Long): Int {
        val g = groupNumber(chatId)
        if (g < 0) return GROUP_ROLE_USER
        val role = core { MainActivity.tox_group_peer_get_role(g, peerId) }
        return if (role in GROUP_ROLE_FOUNDER..GROUP_ROLE_OBSERVER) role else GROUP_ROLE_USER
    }

    fun groupPeerCount(chatId: String): Int {
        val g = groupNumber(chatId)
        if (g < 0) return 0
        return core { MainActivity.tox_group_peer_count(g) }.toInt().coerceAtLeast(0)
    }

    fun groupConnected(chatId: String): Boolean {
        val g = groupNumber(chatId)
        return g >= 0 && core { MainActivity.tox_group_is_connected(g) } == 1
    }

    /** Канал: обычный участник становится читателем (писать не может). Делает только владелец. */
    fun makePeerObserver(chatId: String, peerId: Long) {
        val g = groupNumber(chatId)
        if (g < 0) return
        synchronized(lock) {
            val selfRole = MainActivity.tox_group_self_get_role(g)
            val peerRole = MainActivity.tox_group_peer_get_role(g, peerId)
            if (selfRole == GROUP_ROLE_FOUNDER && peerRole == GROUP_ROLE_USER) {
                MainActivity.tox_group_mod_set_role(g, peerId, GROUP_ROLE_OBSERVER)
            }
        }
    }

    /** Владелец переводит «наблюдателя» в обычные участники (для комментариев в каналах). */
    fun makePeerUser(chatId: String, peerId: Long) {
        val g = groupNumber(chatId)
        if (g < 0) return
        synchronized(lock) {
            val selfRole = MainActivity.tox_group_self_get_role(g)
            val peerRole = MainActivity.tox_group_peer_get_role(g, peerId)
            if (selfRole == GROUP_ROLE_FOUNDER && peerRole == GROUP_ROLE_OBSERVER) {
                MainActivity.tox_group_mod_set_role(g, peerId, GROUP_ROLE_USER)
            }
        }
    }

    fun makeAllUsers(chatId: String) {
        val g = groupNumber(chatId)
        if (g < 0) return
        val peers = core { MainActivity.tox_group_get_peerlist(g) } ?: return
        for (peer in peers) makePeerUser(chatId, peer)
    }

    fun makeAllObservers(chatId: String) {
        val g = groupNumber(chatId)
        if (g < 0) return
        val peers = core { MainActivity.tox_group_get_peerlist(g) } ?: return
        for (peer in peers) makePeerObserver(chatId, peer)
    }

    /** Участники группы (без себя): номер, ключ, имя, роль. */
    fun groupPeers(chatId: String): List<GroupPeerInfo> {
        val g = groupNumber(chatId)
        if (g < 0) return emptyList()
        val peers = core { MainActivity.tox_group_get_peerlist(g) } ?: return emptyList()
        val self = core { MainActivity.tox_group_self_get_peer_id(g) }
        val out = ArrayList<GroupPeerInfo>(peers.size)
        for (p in peers) {
            if (p == self) continue
            val role = core { MainActivity.tox_group_peer_get_role(g, p) }
            out += GroupPeerInfo(
                peerId = p,
                key = peerKey(g, p),
                name = peerName(g, p),
                role = if (role in GROUP_ROLE_FOUNDER..GROUP_ROLE_OBSERVER) role else GROUP_ROLE_USER,
            )
        }
        return out
    }

    /** Есть ли участник с таким номером (проверка после удаления). */
    fun groupHasPeer(chatId: String, peerId: Long): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return core { MainActivity.tox_group_get_peerlist(g) }?.contains(peerId) == true
    }

    /** Удалить участника: может владелец (любого) и модератор (обычных участников). */
    fun kickGroupPeer(chatId: String, peerId: Long): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return synchronized(lock) {
            val selfRole = MainActivity.tox_group_self_get_role(g)
            val peerRole = MainActivity.tox_group_peer_get_role(g, peerId)
            if (selfRole > GROUP_ROLE_MODERATOR || peerRole <= selfRole) {
                false
            } else {
                MainActivity.tox_group_mod_kick_peer(g, peerId)
                true
            }
        }
    }

    /** Владелец назначает/снимает модератора (администратора группы или канала). */
    fun setGroupModerator(chatId: String, peerId: Long, moderator: Boolean): Boolean {
        val g = groupNumber(chatId)
        if (g < 0) return false
        return synchronized(lock) {
            if (MainActivity.tox_group_self_get_role(g) != GROUP_ROLE_FOUNDER) return@synchronized false
            val peerRole = MainActivity.tox_group_peer_get_role(g, peerId)
            if (peerRole == GROUP_ROLE_FOUNDER) return@synchronized false
            MainActivity.tox_group_mod_set_role(g, peerId, if (moderator) GROUP_ROLE_MODERATOR else GROUP_ROLE_USER)
            true
        }
    }

    // ---- Opus для групповых звонков (кодер и декодер у библиотеки общие - один замок) ----
    fun encodeGroupAudio(pcm: ByteArray, out: ByteArray): Int =
        synchronized(opusLock) { MainActivity.toxav_ngc_audio_encode(pcm, GROUP_AUDIO_FRAME_SAMPLES, out) }

    fun decodeGroupAudio(encoded: ByteArray, size: Int, pcmOut: ByteArray): Int =
        synchronized(opusLock) { MainActivity.toxav_ngc_audio_decode(encoded, size, pcmOut) }
}

/** Обрезает строку до max байт UTF-8, не разрывая символы. */
internal fun String.utf8Limit(max: Int): String {
    if (toByteArray().size <= max) return this
    val sb = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val n = String(Character.toChars(cp)).toByteArray().size
        if (bytes + n > max) break
        sb.appendCodePoint(cp)
        bytes += n
        i += Character.charCount(cp)
    }
    return sb.toString()
}

/** Убирает символ NUL: нативная библиотека передаёт строки как C-строки. */
private fun String.clean(): String = replace("\u0000", "")

/** Участник группы для списка «Участники». */
data class GroupPeerInfo(val peerId: Long, val key: String, val name: String, val role: Int)
