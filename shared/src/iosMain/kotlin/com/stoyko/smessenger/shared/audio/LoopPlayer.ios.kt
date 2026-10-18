// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalForeignApi::class)

package com.stoyko.smessenger.shared.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive // setActive:error: объявлен в категории -> extension
import platform.Foundation.NSBundle
import platform.Foundation.NSURL

/**
 * Бесшовная петля сезонной темы на AVAudioPlayer.
 *
 * iOS не играет Ogg Vorbis, поэтому в CI ffmpeg делает из <track>.ogg + <track>.json:
 *   music/<track>_intro.caf - вступление (сэмплы 0..loopStart), играет один раз;
 *   music/<track>.caf       - петля (loopStart..конец), numberOfLoops = -1.
 * ALAC в CAF не добавляет «тишину-разгон» кодека (как AAC/MP3), поэтому стык петли без щелчка,
 * а переход вступление -> петля сводится сэмпл-в-сэмпл через playAtTime(deviceCurrentTime + остаток).
 *
 * Аудиосессия Ambient: уважает переключатель «Без звука» и не глушит музыку других приложений
 * (фоновая музыка темы - «второстепенный» звук; если другое приложение играет, ThemeMusic
 * получает подсказку silenceSecondaryAudioHint и замолкает сам - см. IosPlatform).
 */
actual class LoopPlayer actual constructor() {
    private var track: String? = null
    private var intro: AVAudioPlayer? = null
    private var loop: AVAudioPlayer? = null
    private var introDone = true
    private var loopStartsAt = 0.0
    private var active = false

    actual fun play(track: String, volume: Float) {
        if (this.track != track) {
            release()
            if (!load(track)) return
        }
        val l = loop ?: return
        setVolume(volume)
        if (active) return
        activateSession()
        val i = intro
        if (i != null && !introDone) {
            val remaining = i.duration - i.currentTime
            i.play()
            l.currentTime = 0.0
            loopStartsAt = i.deviceCurrentTime + remaining
            l.playAtTime(loopStartsAt)
        } else {
            l.play()
        }
        active = true
    }

    actual fun pause() {
        if (!active) return
        val i = intro
        val l = loop
        if (i != null && !introDone) {
            if (i.deviceCurrentTime >= loopStartsAt) {
                // Вступление уже доиграло - дальше только петля.
                introDone = true
                i.stop()
                l?.pause()
            } else {
                i.pause()
                l?.stop()          // отменить запланированный старт петли
                l?.currentTime = 0.0
                l?.prepareToPlay()
            }
        } else {
            l?.pause()
        }
        active = false
    }

    actual fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        intro?.volume = v
        loop?.volume = v
    }

    actual fun release() {
        intro?.stop()
        loop?.stop()
        intro = null
        loop = null
        track = null
        active = false
        introDone = true
    }

    private fun load(name: String): Boolean {
        val l = player(name) ?: return false
        l.numberOfLoops = -1
        l.prepareToPlay()
        val i = player("${name}_intro")?.apply {
            numberOfLoops = 0
            prepareToPlay()
        }
        loop = l
        intro = i
        introDone = i == null
        track = name
        return true
    }

    private fun player(name: String): AVAudioPlayer? {
        val url = resource(name) ?: return null
        return runCatching { AVAudioPlayer(contentsOfURL = url, error = null) }.getOrNull()
    }

    private fun resource(name: String): NSURL? {
        // caf (ALAC, по умолчанию) или m4a (AAC, если CI собран с IOS_AUDIO_CODEC=aac).
        for (ext in listOf("caf", "m4a")) {
            NSBundle.mainBundle.URLForResource(name, withExtension = ext, subdirectory = "music")?.let { return it }
        }
        return null
    }

    private fun activateSession() {
        val s = AVAudioSession.sharedInstance()
        s.setCategory(AVAudioSessionCategoryAmbient, error = null)
        s.setActive(true, error = null)
    }
}
