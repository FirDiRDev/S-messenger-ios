// SPDX-FileCopyrightText: 2021 aTox contributors
// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.av

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque

private const val TAG = "AudioPlayer"

// Адаптивный джиттер-буфер: сеть (особенно мобильная и через релеи) приносит пакеты рывками.
// Запас сам растёт после «провалов» (до 260 мс) и медленно уменьшается, когда сеть ровная (до 60 мс).
// На Wi-Fi пакеты идут ровнее - начинаем с меньшего запаса (меньше задержка в разговоре).
private val MIN_TARGET_MS get() = if (com.stoyko.smessenger.domain.feature.NetHint.wifi) 40 else 60
private val START_TARGET_MS get() = if (com.stoyko.smessenger.domain.feature.NetHint.wifi) 70 else 100
private const val MAX_TARGET_MS = 260
private const val FRAME_MS = 20

private fun intToChannel(channels: Int) = when (channels) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    else -> AudioFormat.CHANNEL_OUT_STEREO
}

/**
 * Воспроизведение звука звонка. [buffer] не блокирует вызывающий поток (поток ядра),
 * запись в AudioTrack идёт в отдельном потоке с приоритетом звука.
 */
class AudioPlayer(private val sampleRate: Int, private val channels: Int) {
    private val samplesPerMs = (sampleRate * channels / 1000).coerceAtLeast(1)
    private val minBufferSize =
        AudioTrack.getMinBufferSize(sampleRate, intToChannel(channels), AudioFormat.ENCODING_PCM_16BIT)

    // Буфер AudioTrack с запасом (~120 мс): минимальный размер даёт щелчки и «рваный» звук.
    private val trackBufferBytes = maxOf(minBufferSize * 4, samplesPerMs * 2 * 120)

    private val audioTrack = if (Build.VERSION.SDK_INT < 23) {
        @Suppress("DEPRECATION")
        AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            sampleRate,
            intToChannel(channels),
            AudioFormat.ENCODING_PCM_16BIT,
            trackBufferBytes,
            AudioTrack.MODE_STREAM,
        )
    } else {
        AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(intToChannel(channels))
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun matches(rate: Int, ch: Int) = rate == sampleRate && ch == channels

    private val queue = LinkedBlockingDeque<ShortArray>()

    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Текущий целевой запас, мс (растёт после провалов, снижается при ровной сети). */
    @Volatile
    private var targetMs = START_TARGET_MS

    @Volatile
    var underruns = 0
        private set

    fun buffer(data: ShortArray) {
        if (!running || data.isEmpty()) return
        queue.offer(data)
        queued.addAndGet(data.size)
        // Аварийный предел (сеть «выплюнула» секунду звука разом) - старое выбрасываем.
        while (queued.get() > (MAX_TARGET_MS + 240) * samplesPerMs) {
            val old = queue.pollFirst() ?: break
            queued.addAndGet(-old.size)
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ playLoop() }, "call-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    private val frameSamples = samplesPerMs * FRAME_MS
    private var last: ShortArray? = null
    private var concealed = 0
    private var fadeIn = false

    /** Кадр-заплатка вместо потерянного: повтор последнего с затуханием, потом тишина. */
    private fun conceal(): ShortArray {
        val src = last
        val out = ShortArray(frameSamples)
        if (src != null && concealed < 3) {
            val g0 = when (concealed) { 0 -> 0.8f; 1 -> 0.45f; else -> 0.18f }
            val g1 = when (concealed) { 0 -> 0.45f; 1 -> 0.18f; else -> 0f }
            val n = minOf(out.size, src.size)
            for (i in 0 until n) {
                val g = g0 + (g1 - g0) * i / n
                out[i] = (src[i] * g).toInt().toShort()
            }
        }
        concealed++
        fadeIn = true
        return out
    }

    private fun playLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
        }
        var playing = false
        var calmFrames = 0
        var lastData = System.nanoTime()
        var refill = false
        while (running) {
            if (!playing) {
                // Копим запас перед стартом - так неровность сети не слышна.
                if (queued.get() < targetMs * samplesPerMs) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                try {
                    audioTrack.play()
                } catch (e: Exception) {
                    Log.e(TAG, "play", e)
                    break
                }
                playing = true
                calmFrames = 0
            }
            // После провала ждём, пока снова наберётся запас (а не играем по одному кадру впритык).
            if (refill && queued.get() >= targetMs * samplesPerMs) refill = false
            var frame = if (refill) null else queue.pollFirst()
            if (frame == null) {
                refill = true
                // Провал: не останавливаем динамик (это щелчок и «хрип»), а подкладываем заплатку
                // и увеличиваем запас на будущее.
                if (concealed == 0) {
                    underruns++
                    targetMs = (targetMs + 40).coerceAtMost(MAX_TARGET_MS)
                }
                if (System.nanoTime() - lastData > 1_500_000_000L) {
                    // Долгая тишина/обрыв: пауза и новое накопление запаса.
                    runCatching { audioTrack.pause() }
                    playing = false
                    refill = false
                    concealed = 0
                    last = null
                    continue
                }
                frame = conceal()
            } else {
                queued.addAndGet(-frame.size)
                lastData = System.nanoTime()
                if (fadeIn) {
                    // Плавный вход после заплатки - без щелчка.
                    val n = minOf(frame.size, samplesPerMs * 5)
                    for (i in 0 until n) frame[i] = (frame[i] * (i.toFloat() / n)).toInt().toShort()
                    fadeIn = false
                }
                concealed = 0
                last = frame
                // Сеть ровная - понемногу уменьшаем задержку.
                val haveMs = queued.get() / samplesPerMs
                if (haveMs > targetMs + 60) {
                    // Лишний запас (задержка растёт): выбрасываем тихий кадр, громкие не трогаем.
                    val next = queue.peekFirst()
                    if (next != null && CallDsp.rms(next) < 500f) {
                        queue.pollFirst()?.let { queued.addAndGet(-it.size) }
                    }
                }
                if (++calmFrames > 250) { // ~5 с без провалов
                    calmFrames = 0
                    targetMs = (targetMs - 20).coerceAtLeast(MIN_TARGET_MS)
                }
            }
            try {
                audioTrack.write(frame, 0, frame.size)
                CallDsp.onPlayout(frame)
            } catch (e: Exception) {
                Log.e(TAG, "write", e)
            }
        }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        t?.interrupt()
        try {
            if (t != null && t != Thread.currentThread()) t.join(300)
        } catch (_: InterruptedException) {
        }
        queue.clear()
        queued.set(0)
        runCatching {
            audioTrack.pause()
            audioTrack.flush()
        }
    }

    fun release() {
        if (running) stop()
        runCatching { audioTrack.release() }
    }
}
