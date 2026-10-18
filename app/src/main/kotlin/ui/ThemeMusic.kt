// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.stoyko.smessenger.games.runner.RunnerSeason
import com.stoyko.smessenger.games.runner.SeasonMusic

/**
 * Музыка новогоднего (зимнего) события во всём приложении (Настройки → Персонализация →
 * «Музыка темы»): бесшовная петля, пока приложение открыто. У осени, весны и лета музыки нет.
 *
 * Музыка сама замолкает:
 *  - приложение свёрнуто или экран погас;
 *  - идёт звонок или входящий вызов;
 *  - играет голосовое, видео, музыка из чата, сторис, запись голосового или кружка;
 *  - открыта игра «Бег по рельсам» (там своя музыка своего сезона);
 *  - другое приложение забрало звук (аудиофокус).
 * Громкость фоновая (тише, чем в игре) и меняется в настройках.
 */
object ThemeMusic {
    private const val PREFS = "ui"
    private const val KEY_ON = "theme_music"
    private const val KEY_VOL = "theme_music_vol"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var started = 0
    private val holds = HashSet<String>()
    private var focused = false
    private var focusReq: AudioFocusRequest? = null
    private var playing = false

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, v).apply()
        update()
    }

    /** Громкость 0..100 %. */
    fun volume(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VOL, 45).coerceIn(5, 100)

    fun setVolume(ctx: Context, v: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_VOL, v.coerceIn(5, 100)).apply()
        if (playing) SeasonMusic.volume = v.coerceIn(5, 100) / 100f
    }

    /** Трек темы: музыка есть только у зимнего (новогоднего) события; в остальных темах - тишина. */
    fun trackFor(ctx: Context): String? = when (Seasonal.current(ctx)) {
        Seasonal.Kind.WINTER -> RunnerSeason.track(RunnerSeason.WINTER)
        else -> null
    }

    /** Подключить к приложению (один раз, из Application.onCreate). */
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1) synchronized(holds) { holds.remove("focus") }
                update()
            }

            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // Небольшая задержка: при переходе между окнами приложения музыка не прерывается.
                main.removeCallbacks(lateUpdate)
                main.postDelayed(lateUpdate, 400)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    private val lateUpdate = Runnable { update() }

    /** Заглушить музыку на время (звонок, видео, игра...). Ключ снимается [release]. */
    fun hold(key: String) {
        val added = synchronized(holds) { holds.add(key) }
        if (added) post()
    }

    fun release(key: String) {
        val removed = synchronized(holds) { holds.remove(key) }
        if (removed) post()
    }

    fun set(key: String, on: Boolean) = if (on) hold(key) else release(key)

    private fun post() {
        if (Looper.myLooper() == Looper.getMainLooper()) update() else main.post { update() }
    }

    /** Пересчитать: играть или молчать. Тема или настройка сменились - тоже сюда. */
    fun update() {
        val ctx = app ?: return
        val track = runCatching { trackFor(ctx) }.getOrNull()
        val held = synchronized(holds) { holds.isNotEmpty() }
        val want = track != null && started > 0 && !held && runCatching { enabled(ctx) }.getOrDefault(false)
        if (want) {
            // Уже играет музыка другого приложения - не перебиваем её.
            if (!playing && !focused) {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (runCatching { am?.isMusicActive == true }.getOrDefault(false)) return
            }
            if (!requestFocus(ctx)) return
            SeasonMusic.track = track!!
            SeasonMusic.volume = volume(ctx) / 100f
            SeasonMusic.prepare(ctx)
            SeasonMusic.play(ctx)
            playing = true
        } else if (playing) {
            playing = false
            SeasonMusic.pause()
            abandonFocus(ctx)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> release("focus")
                AudioManager.AUDIOFOCUS_LOSS -> {
                    focused = false
                    hold("focus")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hold("focus")
            }
        }
    }

    private fun requestFocus(ctx: Context): Boolean {
        if (focused) return true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val r = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(true)
                    .build()
                focusReq = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focused = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focused
    }

    private fun abandonFocus(ctx: Context) {
        if (!focused) return
        focused = false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) focusReq?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        }
    }
}
