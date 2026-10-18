// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Плавность 60+ кадров/с: многие телефоны (Xiaomi, POCO, Samsung, OnePlus, Huawei) держат
 * обычные приложения на 60 Гц, даже если экран умеет 90/120/144 Гц. Просим у системы режим
 * экрана с наибольшей частотой при том же разрешении - прокрутка, анимации и игры плавнее.
 *
 * В режиме для слабых устройств и при экономии энергии возвращаем выбор системе
 * (обычно 60 Гц) - меньше нагрев и расход батареи.
 */
object FrameRate {
    /** Частота, на которую сейчас настроено окно (для «О приложении»/диагностики). */
    @Volatile
    var current: Float = 60f
        private set

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            val window = activity.window ?: return
            val display: Display = (if (Build.VERSION.SDK_INT >= 30) activity.display else null)
                ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            val lp = window.attributes
            val wantId = if (LiteMode.on || EnergySaver.on) {
                0
            } else {
                val cur = display.mode
                display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate }
                    ?.takeIf { it.refreshRate > cur.refreshRate + 1f || lp.preferredDisplayModeId != 0 }
                    ?.modeId ?: lp.preferredDisplayModeId
            }
            if (lp.preferredDisplayModeId != wantId) {
                lp.preferredDisplayModeId = wantId
                window.attributes = lp
            }
            current = display.supportedModes.firstOrNull { it.modeId == wantId }?.refreshRate ?: display.refreshRate
        }
    }
}
