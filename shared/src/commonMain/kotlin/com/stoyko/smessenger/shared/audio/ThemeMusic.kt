// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.audio

import com.stoyko.smessenger.shared.platform.KeyValueStore
import com.stoyko.smessenger.shared.platform.currentMonth

/** Сезон темы и его музыкальная петля (имя файла без расширения). */
enum class Season(val track: String, val titleRu: String) {
    SPRING("spring_loop", "Весна"),
    SUMMER("summer_loop", "Лето"),
    AUTUMN("autumn_loop", "Осень"),
    WINTER("winter_loop", "Зима");

    companion object {
        /** «Авто»: весна (март-май), лето (июнь-август), осень (сентябрь-ноябрь), зима (декабрь-февраль). */
        fun forMonth(month: Int): Season = when (month) {
            3, 4, 5 -> SPRING
            6, 7, 8 -> SUMMER
            9, 10, 11 -> AUTUMN
            else -> WINTER
        }

        fun current(): Season = forMonth(currentMonth())
    }
}

/**
 * Проигрыватель бесшовной петли. Файлы:
 *  - Android: assets/music/<track>.ogg + <track>.json (loopStart в сэмплах);
 *  - iOS: music/<track>_intro.caf (вступление) + music/<track>.caf (петля), их делает
 *    scripts/ios/convert_audio.sh из тех же .ogg/.json.
 */
expect class LoopPlayer() {
    /** Начать или продолжить [track] с громкостью 0..1. */
    fun play(track: String, volume: Float)
    fun pause()
    fun setVolume(volume: Float)
    fun release()
}

/**
 * Музыка сезонной темы - общая логика (перенос app/.../ui/ThemeMusic.kt без Android API).
 * Музыка молчит, пока есть хоть одна «заглушка» ([hold]): приложение в фоне, звонок,
 * видео/голосовое, игра со своей музыкой, другое приложение забрало звук и т.п.
 *
 * Вызывать с главного потока.
 */
object ThemeMusic {
    private const val KEY_ON = "theme_music"
    private const val KEY_VOL = "theme_music_vol"
    private const val KEY_SEASON = "theme_season"

    private val prefs by lazy { KeyValueStore("ui") }
    private val player by lazy { LoopPlayer() }
    private val holds = HashSet<String>()
    private var playing: String? = null

    /** Сезон темы из настроек; null - «Авто» (по месяцу). Сохраняется между запусками. */
    var seasonOverride: Season?
        get() = Season.entries.getOrNull(prefs.getInt(KEY_SEASON, -1))
        set(v) { prefs.putInt(KEY_SEASON, v?.ordinal ?: -1); update() }

    val season: Season get() = seasonOverride ?: Season.current()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ON, true)
        set(v) { prefs.putBoolean(KEY_ON, v); update() }

    /** Громкость 5..100 %. */
    var volumePercent: Int
        get() = prefs.getInt(KEY_VOL, 45).coerceIn(5, 100)
        set(v) {
            prefs.putInt(KEY_VOL, v.coerceIn(5, 100))
            if (playing != null) player.setVolume(volumePercent / 100f)
        }

    fun hold(key: String) { if (holds.add(key)) update() }
    fun release(key: String) { if (holds.remove(key)) update() }
    fun set(key: String, on: Boolean) = if (on) hold(key) else release(key)
    fun isHeld(): Boolean = holds.isNotEmpty()

    fun update() {
        val track = if (enabled) season.track else null
        if (track == null) {
            // Музыка выключена - освобождаем память.
            if (playing != null) player.release()
            playing = null
            return
        }
        if (holds.isNotEmpty()) {
            if (playing != null) player.pause()
            return
        }
        if (playing != track && playing != null) player.release()
        player.play(track, volumePercent / 100f)
        playing = track
    }
}
