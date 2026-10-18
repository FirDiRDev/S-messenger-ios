// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window

/**
 * «Режим для слабых устройств». Всё остаётся красивым (градиенты, цвета сезона, рисунки,
 * плавные переходы), но приложение экономит процессор, память и батарею:
 *  - «живые» эффекты (блики, искорки, дыхание карт) - реже, ~20 кадров/с вместо 60;
 *  - меньше частиц в играх и анимациях, без «жидкого стекла» (переливы по стеклу);
 *  - фото в чатах декодируются меньшего размера и в экономном формате, кэш картинок компактнее;
 *  - экран работает на стандартной частоте (без 90/120 Гц) - меньше нагрев.
 *
 * Значения: 0 - авто, 1 - всегда включён, 2 - выключен.
 *
 * «Авто» включает режим:
 *  - на слабых телефонах (мало памяти, 4 ядра и меньше, бюджетные модели по [DeviceProfile]);
 *  - на время перегрева (Android 10+: система сообщает о сильном нагреве);
 *  - если интерфейс заметно подтормаживает (больше четверти кадров не успевают
 *    несколько раз подряд) - до следующего запуска приложения.
 */
object LiteMode {
    const val AUTO = 0
    const val ON = 1
    const val OFF = 2

    @Volatile
    var on = false
        private set

    /** Телефон слабый (по памяти, процессору и модели) - для режима «Авто». */
    @Volatile
    var weakDevice = false
        private set

    /** Телефон перегрет (по данным системы) - временно облегчаем. */
    @Volatile
    var hot = false
        private set

    /** Интерфейс подтормаживал - облегчаем до перезапуска. */
    @Volatile
    var janky = false
        private set

    @Volatile
    private var thermalHooked = false

    /** Текущая настройка (кэш - чтобы не читать настройки на каждом кадре). */
    @Volatile
    private var mode = AUTO

    @Volatile
    private var appCtx: Context? = null

    private fun prefs(ctx: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    fun setting(ctx: Context): Int = runCatching { prefs(ctx).getInt("lite_mode", AUTO) }.getOrDefault(AUTO).coerceIn(0, 2)

    fun set(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("lite_mode", v.coerceIn(0, 2)).apply()
        if (v != AUTO) janky = false
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        weakDevice = runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = mi.totalMem / (1024.0 * 1024 * 1024)
            am.isLowRamDevice || gb < 3.2 || Runtime.getRuntime().availableProcessors() <= 4 || am.memoryClass <= 192
        }.getOrDefault(false) || runCatching { DeviceProfile.lite(app) }.getOrDefault(false)
        hookThermal(app)
        recompute(app)
    }

    private fun recompute(app: Context) {
        mode = setting(app)
        on = when (mode) {
            ON -> true
            OFF -> false
            else -> weakDevice || hot || janky
        }
    }

    /** Перегрев (Android 10+): «сильный» и выше - облегчаем, пока не остынет. */
    private fun hookThermal(app: Context) {
        if (thermalHooked || Build.VERSION.SDK_INT < 29) return
        thermalHooked = true
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            hot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            pm.addThermalStatusListener { status ->
                val h = status >= PowerManager.THERMAL_STATUS_SEVERE
                if (h != hot) {
                    hot = h
                    recompute(app)
                    runCatching { com.stoyko.smessenger.domain.Diagnostics.crumb("перегрев: $status, облегчённый режим: $on") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ подтормаживания

    private var watchThread: HandlerThread? = null

    /**
     * Следить за плавностью окна (Android 7+): считаем кадры, которые рисовались дольше
     * двух интервалов экрана. Окно из 120 нарисованных кадров с долей таких больше 25% -
     * «страйк»; три страйка подряд в режиме «Авто» - включаем облегчённый режим.
     * Кадры считаются только когда экран действительно перерисовывается (без лишней нагрузки).
     */
    @Volatile
    private var quietUntil = 0L

    /** На [ms] не считать медленные кадры (идёт заведомо тяжёлая анимация). */
    fun quiet(ms: Long) {
        quietUntil = maxOf(quietUntil, android.os.SystemClock.uptimeMillis() + ms)
    }

    fun watch(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) return
        val window: Window = activity.window ?: return
        val thread = watchThread ?: HandlerThread("frame-watch").also {
            it.start()
            watchThread = it
        }
        val refresh = runCatching {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }.getOrDefault(60f).coerceIn(30f, 240f)
        val budgetNs = (2_000_000_000L / refresh).toLong()
        var frames = 0
        var slow = 0
        var strikes = 0
        runCatching {
            window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                if (janky || mode != AUTO) return@addOnFrameMetricsAvailableListener
                // Тяжёлые разовые эффекты (размытие предпросмотра, переход) - не считаем.
                if (android.os.SystemClock.uptimeMillis() < quietUntil) return@addOnFrameMetricsAvailableListener
                if (Build.VERSION.SDK_INT >= 26 && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@addOnFrameMetricsAvailableListener
                frames++
                if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > budgetNs) slow++
                if (frames >= 120) {
                    strikes = if (slow * 4 > frames) strikes + 1 else 0
                    frames = 0
                    slow = 0
                    if (strikes >= 3) {
                        janky = true
                        appCtx?.let { recompute(it) }
                        runCatching { com.stoyko.smessenger.domain.Diagnostics.crumb("подтормаживания: облегчённый режим включён автоматически") }
                    }
                }
            }, Handler(thread.looper))
        }
    }

    /** Интервал кадров для «живых» украшений (мс): 60 кадров/с, в лёгком режиме - 30 (раньше 20 - заметно дёргалось). */
    val liveFrameMs: Long get() = if (on) 33L else 16L

    /** Сколько частиц рисовать: в лёгком режиме - примерно вдвое меньше. */
    fun particles(n: Int): Int = if (on) (n + 1) / 2 else n
}
