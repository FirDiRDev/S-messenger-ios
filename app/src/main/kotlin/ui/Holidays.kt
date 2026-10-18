// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import com.stoyko.smessenger.R
import java.util.Calendar

/**
 * Календарь осенних и зимних праздников: заставка при запуске поздравляет с праздником
 * в его день, а в обычные дни показывает одну из сезонных фраз (по 10 на осень и зиму).
 */
object Holidays {
    /** (месяц 0..11, день) → строка поздравления. */
    private val FIXED = mapOf(
        (Calendar.SEPTEMBER to 1) to R.string.hol_knowledge,
        (Calendar.SEPTEMBER to 22) to R.string.hol_equinox,
        (Calendar.OCTOBER to 1) to R.string.hol_elders,
        (Calendar.OCTOBER to 5) to R.string.hol_teacher,
        (Calendar.OCTOBER to 31) to R.string.hol_halloween,
        (Calendar.NOVEMBER to 4) to R.string.hol_unity,
        (Calendar.DECEMBER to 12) to R.string.hol_constitution,
        (Calendar.DECEMBER to 22) to R.string.hol_solstice,
        (Calendar.DECEMBER to 25) to R.string.hol_xmas_west,
        (Calendar.DECEMBER to 31) to R.string.hol_ny_eve,
        (Calendar.JANUARY to 1) to R.string.hol_ny,
        (Calendar.JANUARY to 2) to R.string.hol_ny,
        (Calendar.JANUARY to 7) to R.string.hol_xmas,
        (Calendar.JANUARY to 14) to R.string.hol_old_ny,
        (Calendar.JANUARY to 25) to R.string.hol_tatiana,
        (Calendar.FEBRUARY to 14) to R.string.hol_valentine,
        (Calendar.FEBRUARY to 23) to R.string.hol_defender,
        (Calendar.MARCH to 8) to R.string.hol_women,
        (Calendar.APRIL to 12) to R.string.hol_cosmo,
        (Calendar.MAY to 1) to R.string.hol_may,
        (Calendar.MAY to 9) to R.string.hol_victory,
        (Calendar.JUNE to 1) to R.string.hol_children,
        (Calendar.JUNE to 12) to R.string.hol_russia,
        (Calendar.JULY to 8) to R.string.hol_family,
        (Calendar.AUGUST to 22) to R.string.hol_flag,
        (Calendar.MARCH to 1) to R.string.hol_spring_day,
        (Calendar.APRIL to 1) to R.string.hol_fools,
        (Calendar.APRIL to 22) to R.string.hol_nature,
        (Calendar.JUNE to 2) to R.string.hol_summer_day,
        (Calendar.JULY to 30) to R.string.hol_friendship,
        (Calendar.OCTOBER to 2) to R.string.hol_smile,
        (Calendar.DECEMBER to 1) to R.string.hol_winter_day,
    )

    private val WINTER = intArrayOf(
        R.string.ph_w1, R.string.ph_w2, R.string.ph_w3, R.string.ph_w4, R.string.ph_w5,
        R.string.ph_w6, R.string.ph_w7, R.string.ph_w8, R.string.ph_w9, R.string.ph_w10,
        R.string.ph_w11, R.string.ph_w12, R.string.ph_w13, R.string.ph_w14, R.string.ph_w15,
        R.string.ph_w16, R.string.ph_w17, R.string.ph_w18, R.string.ph_w19, R.string.ph_w20,
    )
    private val AUTUMN = intArrayOf(
        R.string.ph_a1, R.string.ph_a2, R.string.ph_a3, R.string.ph_a4, R.string.ph_a5,
        R.string.ph_a6, R.string.ph_a7, R.string.ph_a8, R.string.ph_a9, R.string.ph_a10,
        R.string.ph_a11, R.string.ph_a12, R.string.ph_a13, R.string.ph_a14, R.string.ph_a15,
        R.string.ph_a16, R.string.ph_a17, R.string.ph_a18, R.string.ph_a19, R.string.ph_a20,
    )

    private val SPRING = intArrayOf(
        R.string.ph_s1, R.string.ph_s2, R.string.ph_s3, R.string.ph_s4, R.string.ph_s5,
        R.string.ph_s6, R.string.ph_s7, R.string.ph_s8,
        R.string.ph_s9, R.string.ph_s10, R.string.ph_s11, R.string.ph_s12,
        R.string.ph_s13, R.string.ph_s14, R.string.ph_s15, R.string.ph_s16,
    )
    private val SUMMER = intArrayOf(
        R.string.ph_u1, R.string.ph_u2, R.string.ph_u3, R.string.ph_u4, R.string.ph_u5,
        R.string.ph_u6, R.string.ph_u7, R.string.ph_u8,
        R.string.ph_u9, R.string.ph_u10, R.string.ph_u11, R.string.ph_u12,
        R.string.ph_u13, R.string.ph_u14, R.string.ph_u15, R.string.ph_u16,
    )

    /** Праздник сегодня (или null). */
    fun today(ctx: Context, cal: Calendar = Calendar.getInstance()): String? {
        val m = cal.get(Calendar.MONTH)
        val d = cal.get(Calendar.DAY_OF_MONTH)
        FIXED[m to d]?.let { return ctx.getString(it) }
        // День матери - последнее воскресенье ноября.
        if (m == Calendar.NOVEMBER && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && d + 7 > cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            return ctx.getString(R.string.hol_mother)
        }
        // День программиста - 256-й день года (13 сентября, в високосный - 12-го).
        if (cal.get(Calendar.DAY_OF_YEAR) == 256) return ctx.getString(R.string.hol_programmer)
        // Новогодние каникулы.
        if (m == Calendar.JANUARY && d in 3..8) return ctx.getString(R.string.hol_holidays)
        return null
    }

    /** Поздравление для заставки: праздник дня или случайная фраза сезона. */
    fun greeting(ctx: Context, kind: Seasonal.Kind): String? {
        today(ctx)?.let { return it }
        val list = when (kind) {
            Seasonal.Kind.WINTER -> WINTER
            Seasonal.Kind.AUTUMN -> AUTUMN
            Seasonal.Kind.SPRING -> SPRING
            Seasonal.Kind.SUMMER -> SUMMER
            Seasonal.Kind.NONE -> return null
        }
        return ctx.getString(list[(Math.random() * list.size).toInt().coerceIn(0, list.size - 1)])
    }
}
