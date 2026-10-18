// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Приятный «дзынь» при принятии подарка - звук синтезируется в приложении (без файлов):
 * колокольчик (неровные обертоны, как у настоящего металла) с мягким затуханием, а у редких и
 * легендарных подарков - ещё и россыпь «искр»: быстрые восходящие звоночки.
 * В беззвучном режиме и режиме вибрации звук не играет.
 */
object GiftSound {
    private const val RATE = 32_000

    /** Обертоны колокольчика: (множитель частоты, громкость, скорость затухания). */
    private val bell = arrayOf(
        floatArrayOf(1.00f, 1.00f, 3.2f),
        floatArrayOf(2.76f, 0.42f, 5.5f),
        floatArrayOf(5.40f, 0.22f, 8.0f),
        floatArrayOf(8.93f, 0.10f, 11f),
    )

    private fun strike(out: FloatArray, at: Float, freq: Float, volume: Float, length: Float) {
        val from = (at * RATE).toInt()
        val n = (length * RATE).toInt()
        val attack = (0.004f * RATE).toInt()
        for (i in 0 until min(n, out.size - from)) {
            val t = i.toFloat() / RATE
            val a = if (i < attack) i.toFloat() / attack else 1f
            var v = 0.0
            for (p in bell) v += p[1] * exp(-p[2] * t) * sin(2 * PI * freq * p[0] * t)
            out[from + i] += (v * a * volume).toFloat()
        }
    }

    private fun render(rarity: GiftCatalog.Rarity): ShortArray {
        val out = FloatArray((1.9f * RATE).toInt())
        // «Дзынь»: две ноты мажорной терции (тёплый звук, без резкости).
        strike(out, 0.00f, 1318.5f, 0.42f, 1.6f) // E6
        strike(out, 0.09f, 1661.2f, 0.30f, 1.5f) // G#6
        if (rarity != GiftCatalog.Rarity.COMMON) {
            // Искры: быстрое восходящее арпеджио.
            val notes = floatArrayOf(1975.5f, 2349.3f, 2637.0f, 3135.9f)
            notes.forEachIndexed { i, f -> strike(out, 0.20f + i * 0.055f, f, 0.12f, 0.6f) }
        }
        if (rarity == GiftCatalog.Rarity.LEGENDARY) {
            // У легендарных - второй, чуть ниже, «дзынь» в конце: звук как у награды.
            strike(out, 0.50f, 1046.5f, 0.26f, 1.3f) // C6
        }
        var peak = 0f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        val k = if (peak > 0f) 0.38f / peak else 1f
        val tail = (0.08f * RATE).toInt()
        return ShortArray(out.size) { i ->
            val fade = if (i > out.size - tail) (out.size - i).toFloat() / tail else 1f
            (out[i] * k * fade * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    fun play(ctx: Context, rarity: GiftCatalog.Rarity) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            runCatching {
                val pcm = render(rarity)
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / RATE) + 200)
                track.release()
            }
        }.apply { isDaemon = true }.start()
    }
}
