// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.av.AudioCapture
import com.stoyko.smessenger.domain.tox.Tox

sealed class CallState {
    object NotInCall : CallState()
    data class InCall(val publicKey: PublicKey, val startTime: Long) : CallState()
}

/**
 * Этап звонка (для экрана и звука):
 *  CONNECTING - «Соединение…»: связь с собеседником;
 *  SECURING   - «Подключение…»: проверка защищённого канала (сквозное шифрование, Wi-Fi или моб. сеть);
 *  RINGING    - «Вызов…»: у собеседника звонит телефон, у вас - гудки;
 *  ACTIVE     - собеседник ответил: идёт разговор, таймер и микрофон работают только с этого момента.
 */
enum class CallPhase { NONE, CONNECTING, SECURING, RINGING, ACTIVE }

private const val TAG = "CallManager"

private const val AUDIO_CHANNELS = 1
private const val AUDIO_SAMPLING_RATE_HZ = 48_000
private const val AUDIO_SEND_INTERVAL_MS = 20

@Singleton
class CallManager @Inject constructor(private val tox: Tox, private val scope: CoroutineScope, private val context: Context) {
    /**
     * Звонок начинается через разговорный динамик (у уха), как обычный телефонный,
     * а не через громкую связь. Видеозвонок - всегда через громкую связь. Задаёт приложение.
     */
    @Volatile
    var preferEarpiece = true

    private val _inCall = MutableStateFlow<CallState>(CallState.NotInCall)
    val inCall: StateFlow<CallState> get() = _inCall

    private val _pendingCalls = MutableStateFlow<MutableSet<Contact>>(mutableSetOf())
    val pendingCalls: StateFlow<Set<Contact>> get() = _pendingCalls

    private val _phase = MutableStateFlow(CallPhase.NONE)
    val phase: StateFlow<CallPhase> get() = _phase

    /** Когда собеседник ответил (для таймера разговора); 0 - ещё не ответил. */
    @Volatile
    var activeSince = 0L
        private set

    /** true - звоним мы (ждём ответа), false - мы ответили на входящий. */
    @Volatile
    var outgoing = false
        private set

    /** Этапы идут только вперёд: поздний ответ «Вызов…» не перебьёт уже начавшийся разговор. */
    fun setPhase(p: CallPhase) {
        if (p == CallPhase.NONE) {
            _phase.value = CallPhase.NONE
            activeSince = 0L
            return
        }
        if (inCall.value !is CallState.InCall) return
        if (p.ordinal <= _phase.value.ordinal) return
        if (p == CallPhase.ACTIVE) activeSince = SystemClock.elapsedRealtime()
        _phase.value = p
    }

    /** Ядро сообщило: собеседник принял звонок (пошёл звук/видео) - разговор начался. */
    fun onPeerAnswered(publicKey: PublicKey) {
        val s = inCall.value
        if (s is CallState.InCall && s.publicKey == publicKey) setPhase(CallPhase.ACTIVE)
    }

    private val _sendingAudio = MutableStateFlow(false)
    val sendingAudio: StateFlow<Boolean> get() = _sendingAudio

    private val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)

    fun addPendingCall(from: Contact) {
        val calls = mutableSetOf<Contact>().apply { addAll(_pendingCalls.value) }
        calls.addAll(_pendingCalls.value)
        if (calls.add(from)) {
            Log.i(TAG, "Added pending call ${from.publicKey.take(8)}")
            _pendingCalls.value = calls
        }
    }

    fun removePendingCall(pk: PublicKey) {
        val calls = mutableSetOf<Contact>().apply { addAll(_pendingCalls.value) }
        val removed = calls.firstOrNull { it.publicKey == pk.string() }
        if (removed != null) {
            Log.i(TAG, "Removed pending call ${pk.fingerprint()}")
            calls.remove(removed)
            _pendingCalls.value = calls
        }
    }

    /** false - звонок не начался (собеседник не в сети). */
    fun startCall(publicKey: PublicKey, video: Boolean = false): Boolean {
        val answering = pendingCalls.value.any { it.publicKey == publicKey.string() }
        if (answering) {
            tox.answerCall(publicKey, video)
        } else if (!tox.startCall(publicKey, video)) {
            return false
        }
        outgoing = !answering
        activeSince = 0L
        com.stoyko.smessenger.domain.Diagnostics.crumb(if (answering) "ответ на звонок" + (if (video) " (видео)" else "") else "исходящий звонок" + (if (video) " (видео)" else ""))
        _phase.value = CallPhase.CONNECTING
        // Голосовой звонок - у уха (громкую связь включает сам человек кнопкой в звонке),
        // видеозвонок - на громкой: телефон держат перед собой, как в обычных мессенджерах.
        com.stoyko.smessenger.domain.av.AudioRoute.enterCall(context, speakerOn = video || !preferEarpiece)
        _inCall.value = CallState.InCall(publicKey, SystemClock.elapsedRealtime())
        removePendingCall(publicKey)
        com.stoyko.smessenger.domain.av.CallDsp.reset()
        if (answering) {
            // Мы ответили сами - разговор идёт сразу: микрофон включается без задержки
            // (раньше первые 2,5 секунды собеседник нас не слышал и первые слова терялись).
            setPhase(CallPhase.ACTIVE)
        }
        return true
    }

    fun endCall(publicKey: PublicKey) {
        val state = inCall.value
        if (state is CallState.InCall && state.publicKey == publicKey) {
            com.stoyko.smessenger.domain.av.AudioRoute.exitCall(context)
            com.stoyko.smessenger.domain.Diagnostics.crumb("звонок завершён")
            _inCall.value = CallState.NotInCall
            setPhase(CallPhase.NONE)
        }

        removePendingCall(publicKey)

        tox.endCall(publicKey)
    }

    fun startSendingAudio(): Boolean {
        val to = (inCall.value as CallState.InCall?)?.publicKey ?: return false
        val recorder =
            AudioCapture.create(AUDIO_SAMPLING_RATE_HZ, AUDIO_CHANNELS, AUDIO_SEND_INTERVAL_MS) ?: return false
        startAudioSender(recorder, to)
        return true
    }

    fun stopSendingAudio() {
        _sendingAudio.value = false
    }

    var speakerphoneOn: Boolean
        get() = com.stoyko.smessenger.domain.av.AudioRoute.isSpeaker(context)
        set(value) {
            com.stoyko.smessenger.domain.av.AudioRoute.setSpeaker(context, value)
        }

    private fun startAudioSender(recorder: AudioCapture, to: PublicKey) {
        // Два потока с приоритетом звука: один только читает микрофон (темп задаёт сам микрофон -
        // read ждёт 20 мс кадра), второй кодирует и отправляет. Отправка ждёт ядро Tox (общий
        // замок с циклом сети) - раньше в это время микрофон не читался и звук «рвался».
        _sendingAudio.value = true
        val queue = java.util.concurrent.ArrayBlockingQueue<ShortArray>(6)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        fun running() = !stop.get() && inCall.value is CallState.InCall && sendingAudio.value
        val sender = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
            }
            while (!stop.get()) {
                val frame = try {
                    queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                // До ответа собеседника звук не уходит: звонок ещё не начался.
                if (_phase.value != CallPhase.ACTIVE) continue
                try {
                    tox.sendAudio(to, frame, AUDIO_CHANNELS, AUDIO_SAMPLING_RATE_HZ)
                } catch (e: Exception) {
                    Log.e(TAG, e.toString())
                }
            }
        }, "call-audio-send").apply {
            isDaemon = true
            start()
        }
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
            }
            try {
                recorder.start()
                while (running()) {
                    val audioFrame = recorder.read()
                    // Отправка не успевает (сеть/ядро заняты) - выкидываем самый старый кадр:
                    // задержка не копится, голос остаётся «живым».
                    if (!queue.offer(audioFrame)) {
                        queue.poll()
                        queue.offer(audioFrame)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio sender", e)
            } finally {
                stop.set(true)
                runCatching { sender.interrupt() }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                _sendingAudio.value = false
            }
        }, "call-audio-in").apply {
            isDaemon = true
            start()
        }
    }
}
