// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.stoyko.smessenger.domain.av.AudioCapture
import com.stoyko.smessenger.domain.av.AudioPlayer
import com.stoyko.smessenger.domain.tox.GROUP_AUDIO_FRAME_SAMPLES
import com.stoyko.smessenger.domain.tox.GROUP_AUDIO_SAMPLE_RATE
import com.stoyko.smessenger.domain.tox.GROUP_PKT_AUDIO
import com.stoyko.smessenger.domain.tox.Tox

private const val TAG = "GroupCall"
private const val FRAME_MS = 40 // 1920 сэмплов при 48 кГц
private const val ENCODED_MAX = 1200
private const val DECODED_MAX_BYTES = 5760 * 2
private const val SILENCE_LEVEL = 300 // тишину не отправляем - экономим трафик и батарею

/** Участник звонка, от которого приходит звук. */
data class CallParticipant(val key: String, val name: String, val muted: Boolean)

/**
 * Групповой голосовой звонок (без видео), максимально просто:
 * микрофон -> Opus -> пакет всем участникам группы; входящие пакеты -> Opus -> динамик.
 * Звук слышат только те, кто тоже нажал «Групповой звонок». Шифрует ядро.
 */
@Singleton
class GroupCallManager @Inject constructor(
    private val context: Context,
    private val tox: Tox,
) {
    private val errors = CoroutineExceptionHandler { _, e -> Log.e(TAG, "background error", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val audioManager: AudioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL

    private val _activeChat = MutableStateFlow<String?>(null)
    val activeChat: StateFlow<String?> = _activeChat

    private val _speakerOn = MutableStateFlow(false)

    /** Громкая связь в групповом звонке (иначе - динамик у уха или гарнитура). */
    val speakerOn: StateFlow<Boolean> = _speakerOn

    @Volatile
    var preferEarpiece = true

    fun toggleSpeaker() {
        _speakerOn.value = !_speakerOn.value
        com.stoyko.smessenger.domain.av.AudioRoute.setSpeaker(context, _speakerOn.value)
    }

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted

    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())
    val participants: StateFlow<List<CallParticipant>> = _participants

    private var captureJob: Job? = null

    private class PeerStream(
        val name: String,
        val player: AudioPlayer,
        val queue: Channel<ShortArray>,
        val job: Job,
    )

    private val peers = ConcurrentHashMap<String, PeerStream>()
    // newKeySet() есть только с Android 7 - используем вариант, работающий на Android 5+.
    private val mutedPeers: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun toggleMute() {
        _micMuted.value = !_micMuted.value
    }

    /** Отключить/включить звук конкретного участника (только у себя). */
    fun togglePeerMute(key: String) {
        if (!mutedPeers.add(key)) mutedPeers.remove(key)
        publishParticipants()
    }

    private fun publishParticipants() {
        _participants.value = peers.map { (key, p) -> CallParticipant(key, p.name, key in mutedPeers) }
            .sortedBy { it.name.lowercase() }
    }

    @Suppress("DEPRECATION")
    fun join(chatId: String): Boolean {
        if (_activeChat.value == chatId) return true
        leave()
        val capture = AudioCapture.create(GROUP_AUDIO_SAMPLE_RATE, 1, FRAME_MS) ?: run {
            Log.e(TAG, "No microphone")
            return false
        }
        _activeChat.value = chatId
        // Режим связи (эхоподавление). По умолчанию - как обычный разговор (у уха),
        // громкую связь можно включить кнопкой.
        runCatching { previousMode = audioManager.mode }
        _speakerOn.value = !preferEarpiece
        com.stoyko.smessenger.domain.av.AudioRoute.enterCall(context, speakerOn = _speakerOn.value)
        captureJob = scope.launch {
            val pcmBytes = ByteArray(GROUP_AUDIO_FRAME_SAMPLES * 2)
            val encoded = ByteArray(ENCODED_MAX)
            val pcmView = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var seq = 0
            var silentFrames = 0
            try {
                capture.start()
                while (isActive && _activeChat.value == chatId) {
                    val samples = capture.read()
                    if (_micMuted.value) continue
                    val frame = if (samples.size >= GROUP_AUDIO_FRAME_SAMPLES) {
                        samples
                    } else {
                        samples.copyOf(GROUP_AUDIO_FRAME_SAMPLES)
                    }
                    // Долгая тишина не передаётся (первые кадры тишины - передаются, чтобы не обрезать речь).
                    if (peak(frame) < SILENCE_LEVEL) {
                        if (++silentFrames > 10) continue
                    } else {
                        silentFrames = 0
                    }
                    pcmView.clear()
                    pcmView.put(frame, 0, GROUP_AUDIO_FRAME_SAMPLES)
                    val len = tox.encodeGroupAudio(pcmBytes, encoded)
                    if (len <= 0 || len > ENCODED_MAX) continue
                    val pkt = ByteArray(3 + len)
                    pkt[0] = GROUP_PKT_AUDIO
                    pkt[1] = (seq shr 8).toByte()
                    pkt[2] = seq.toByte()
                    System.arraycopy(encoded, 0, pkt, 3, len)
                    seq = (seq + 1) and 0xFFFF
                    tox.sendGroupPacket(chatId, pkt, lossless = false)
                }
            } finally {
                runCatching { capture.stop() }
                capture.release()
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    fun leave() {
        val wasActive = _activeChat.value != null
        _activeChat.value = null
        captureJob?.cancel()
        captureJob = null
        peers.values.forEach { p ->
            p.queue.close()
            p.job.cancel()
            runCatching {
                p.player.stop()
                p.player.release()
            }
        }
        peers.clear()
        mutedPeers.clear()
        publishParticipants()
        if (wasActive) {
            com.stoyko.smessenger.domain.av.AudioRoute.exitCall(context)
            runCatching { audioManager.mode = previousMode }
        }
    }

    /** Вызывается из потока ядра: только декодируем и кладём в очередь, играем в отдельном потоке. */
    fun onAudioPacket(chatId: String, peerKey: String, peerName: String, data: ByteArray) {
        if (_activeChat.value != chatId || data.size <= 3) return
        val stream = streamFor(peerKey, peerName)
        if (peerKey in mutedPeers) return
        val encoded = data.copyOfRange(3, data.size)
        val pcmBytes = ByteArray(DECODED_MAX_BYTES)
        val samples = tox.decodeGroupAudio(encoded, encoded.size, pcmBytes)
        if (samples <= 0) return
        val count = minOf(samples, DECODED_MAX_BYTES / 2)
        val pcm = ShortArray(count)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, count)
        stream.queue.trySend(pcm)
    }

    private fun streamFor(peerKey: String, peerName: String): PeerStream {
        peers[peerKey]?.let { return it }
        val player = AudioPlayer(GROUP_AUDIO_SAMPLE_RATE, 1).apply { start() }
        // Небольшая очередь: при отставании старые кадры выбрасываются, задержка не копится.
        val queue = Channel<ShortArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val job = scope.launch {
            for (frame in queue) player.buffer(frame)
        }
        val stream = PeerStream(peerName.ifEmpty { peerKey.take(8) }, player, queue, job)
        val existing = peers.putIfAbsent(peerKey, stream)
        if (existing != null) {
            // Кто-то успел раньше - наш поток не нужен.
            queue.close()
            job.cancel()
            runCatching { player.release() }
            return existing
        }
        publishParticipants()
        return stream
    }
}

private fun peak(frame: ShortArray): Int {
    var max = 0
    var i = 0
    // Достаточно проверить каждый 4-й сэмпл.
    while (i < frame.size) {
        val v = kotlin.math.abs(frame[i].toInt())
        if (v > max) max = v
        i += 4
    }
    return max
}
