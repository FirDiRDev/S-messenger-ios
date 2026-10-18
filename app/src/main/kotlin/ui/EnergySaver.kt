// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Экономия энергии интерфейса: включён системный режим энергосбережения, «Экономия батареи»
 * в настройках или заряд ниже 15% (не на зарядке) - отключаем «украшения», которые
 * постоянно перерисовывают экран: бегущие блики кнопок, волны по стеклу, бесконечные
 * живые эмодзи. Всё остальное работает как обычно.
 */
object EnergySaver {
    @Volatile
    var on = false
        private set

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        val charging = android.os.Build.VERSION.SDK_INT >= 23 && bm?.isCharging == true
        val mode = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).getInt("battery_mode", 1)
        }.getOrDefault(1)
        on = pm?.isPowerSaveMode == true || mode == 2 || (level in 1..15 && !charging)
        com.stoyko.smessenger.domain.feature.PowerHint.saver = pm?.isPowerSaveMode == true || mode == 2
    }
}
