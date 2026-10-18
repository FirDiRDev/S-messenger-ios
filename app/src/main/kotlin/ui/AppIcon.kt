// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.stoyko.smessenger.R

/**
 * Иконка приложения на рабочем столе (Настройки → Персонализация → Иконка):
 * «Авто» - вместе с темой (зимой - зимняя, осенью - осенняя, иначе обычная), либо любая
 * на выбор: обычная, зимняя, осенняя, тёмная, неон, золото, океан.
 * Технически - псевдонимы главного экрана в манифесте: включён ровно один.
 */
object AppIcon {
    class Icon(val id: String, val title: Int, val res: Int)

    val ICONS = listOf(
        Icon("auto", R.string.icon_auto, R.mipmap.ic_launcher),
        Icon("default", R.string.icon_default, R.mipmap.ic_launcher),
        Icon("winter", R.string.icon_winter, R.mipmap.ic_launcher_winter),
        Icon("autumn", R.string.icon_autumn, R.mipmap.ic_launcher_autumn),
        Icon("dark", R.string.icon_dark, R.mipmap.ic_launcher_dark),
        Icon("neon", R.string.icon_neon, R.mipmap.ic_launcher_neon),
        Icon("gold", R.string.icon_gold, R.mipmap.ic_launcher_gold),
        Icon("ocean", R.string.icon_ocean, R.mipmap.ic_launcher_ocean),
    )

    private val ALIASES = listOf("default", "winter", "autumn", "dark", "neon", "gold", "ocean")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun choice(ctx: Context): String = prefs(ctx).getString("app_icon", "auto") ?: "auto"

    fun setChoice(ctx: Context, id: String) {
        prefs(ctx).edit().putString("app_icon", id).commit()
    }

    /** Какая иконка должна стоять сейчас. */
    fun resolved(ctx: Context): String = when (val c = choice(ctx)) {
        "auto" -> when (Seasonal.current(ctx)) {
            Seasonal.Kind.WINTER -> "winter"
            Seasonal.Kind.AUTUMN -> "autumn"
            Seasonal.Kind.NONE, Seasonal.Kind.SPRING, Seasonal.Kind.SUMMER -> "default"
        }
        else -> c.takeIf { it in ALIASES } ?: "default"
    }

    private fun component(ctx: Context, id: String) =
        ComponentName(ctx.packageName, "com.stoyko.smessenger.Launcher" + id.replaceFirstChar { it.uppercase() })

    /** Включить нужную иконку. true - иконка поменялась. */
    fun apply(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val want = resolved(ctx)
        val target = component(ctx, want)
        val cur = runCatching { pm.getComponentEnabledSetting(target) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val enabledNow = cur == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (cur == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && want == "default")
        val othersOff = ALIASES.filter { it != want }.all { id ->
            val s = runCatching { pm.getComponentEnabledSetting(component(ctx, id)) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || (s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && id != "default")
        }
        if (enabledNow && othersOff) return false
        runCatching {
            // Сначала включаем новую, потом выключаем остальные - значок не пропадает с экрана.
            pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (id in ALIASES) if (id != want) {
                pm.setComponentEnabledSetting(component(ctx, id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
        return true
    }
}
