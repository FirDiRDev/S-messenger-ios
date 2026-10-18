// SPDX-FileCopyrightText: 2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.av

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

private const val TAG = "AudioCapture"

private fun intToChannel(channels: Int) = when (channels) {
    1 -> AudioFormat.CHANNEL_IN_MONO
    else -> AudioFormat.CHANNEL_IN_STEREO
}

// The permission linting doesn't work very well unless you sprinkle
// ContextCompat.checkSelfPermission in way too many places. It doesn't even
// agree with results from ActivityResultContracts.RequestPermission, requiring
// an extra permission check in there as well.
@SuppressLint("MissingPermission")
private fun findAudioRecord(sampleRate: Int, channels: Int): AudioRecord? {
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val channelConfig = intToChannel(channels)

    val minSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minSize <= 0) {
        return null
    }
    // Запас ~200 мс: при кратких задержках потока звук не теряется (раньше был минимум - щелчки/пропуски).
    val bufferSize = maxOf(minSize * 4, sampleRate * channels * 2 / 5)

    // Seems like not all Xiaomi phones have a VOICE_COMMUNICATION audio source, so try a few different ones.
    val audioSources = arrayOf(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    )
    for (audioSource in audioSources) {
        val recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Failed to initialize audio record $audioSource")
            continue
        }
        return recorder
    }

    return null
}

class AudioCapture private constructor(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameLengthMs: Int,
    private val audioRecord: AudioRecord,
) {
    // Эхо- и шумоподавление, автоусиление: заметно чище звук в звонках.
    private val effects: List<AudioEffect> = buildList {
        val session = audioRecord.audioSessionId
        fun add(available: Boolean, make: () -> AudioEffect?) {
            if (!available) return
            try {
                make()?.let {
                    it.enabled = true
                    add(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio effect unavailable: $e")
            }
        }
        add(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
        add(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
        add(AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
    }.also { list -> CallDsp.platformAec = list.any { it is AcousticEchoCanceler } }

    fun start() = audioRecord.startRecording()
    fun stop() = audioRecord.stop()
    fun release() {
        effects.forEach { runCatching { it.release() } }
        audioRecord.release()
    }
    /** Читает ровно один кадр (блокирует до его готовности - это и задаёт темп отправки). */
    fun read(): ShortArray {
        val frame = ShortArray((sampleRate * channels * frameLengthMs / 1000.0).toInt())
        var got = 0
        var errors = 0
        while (got < frame.size) {
            val n = audioRecord.read(frame, got, frame.size - got)
            if (n > 0) {
                got += n
            } else if (++errors > 3) {
                break // микрофон недоступен - отдаём тишину, а не зависаем
            }
        }
        // Срез гула, эхо- и шумоподавление, ограничитель (см. CallDsp).
        if (channels == 1) CallDsp.processMic(frame, sampleRate)
        return frame
    }

    companion object {
        fun create(sampleRate: Int, channels: Int, frameLengthMs: Int): AudioCapture? {
            val audioRecord = findAudioRecord(sampleRate, channels) ?: return null
            return AudioCapture(sampleRate, channels, frameLengthMs, audioRecord)
        }
    }
}
