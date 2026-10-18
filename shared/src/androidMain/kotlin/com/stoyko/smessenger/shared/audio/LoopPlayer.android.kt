// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.stoyko.smessenger.shared.platform.SharedAndroid
import org.json.JSONObject

/**
 * Android-реализация на MediaPlayer (assets/music/<track>.ogg + <track>.json).
 * В самом приложении по-прежнему работает свой SeasonMusic (AudioTrack, сэмпл-точная петля);
 * этот класс нужен, чтобы общий ThemeMusic работал и на Android, если вы переведёте его на :shared.
 */
actual class LoopPlayer actual constructor() {
    private var mp: MediaPlayer? = null
    private var track: String? = null

    actual fun play(track: String, volume: Float) {
        if (this.track != track) release()
        val p = mp ?: create(track) ?: return
        p.setVolume(volume, volume)
        if (!p.isPlaying) p.start()
    }

    actual fun pause() { mp?.takeIf { it.isPlaying }?.pause() }

    actual fun setVolume(volume: Float) { mp?.setVolume(volume, volume) }

    actual fun release() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        track = null
    }

    private fun create(name: String): MediaPlayer? = runCatching {
        val ctx = SharedAndroid.context
        val loopMs = runCatching {
            val j = JSONObject(ctx.assets.open("music/$name.json").bufferedReader().use { it.readText() })
            j.optLong("loopStart") * 1000L / j.optLong("rate", 32000L).coerceAtLeast(1L)
        }.getOrDefault(0L)
        val p = MediaPlayer()
        ctx.assets.openFd("music/$name.ogg").use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        // Петля с точки loopStart (вступление играет только один раз).
        p.setOnCompletionListener { it.seekTo(loopMs.toInt()); it.start() }
        p.prepare()
        mp = p
        track = name
        p
    }.getOrNull()
}
