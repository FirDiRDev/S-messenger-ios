// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

/**
 * Подсказка ядру об экономии энергии. Экран выключен и нет звонка/передачи файлов -
 * ядро крутится реже (сеть при этом не теряется: служебные пакеты сети идут раз в секунды).
 */
object PowerHint {
    /** Экран телефона выключен. */
    @Volatile
    var screenOff = false

    /** Режим «Экономия батареи» (Настройки) или системный энергосберегающий режим. */
    @Volatile
    var saver = false

    /** Идёт звонок - ядро всегда на полной скорости. */
    @Volatile
    var inCall = false

    /** Интервал цикла ядра с учётом экономии. */
    fun interval(base: Long): Long = when {
        // Звонок: пакеты звука принимаются внутри цикла ядра - при обычных 50 мс они приходят
        // пачками (рывки, «хрипы»), поэтому во время звонка цикл крутится каждые 10 мс.
        inCall -> minOf(base, 10L)
        TransferMonitor.busy -> base
        screenOff && saver -> maxOf(base, 400L)
        screenOff -> maxOf(base, 200L)
        saver -> maxOf(base, 100L)
        else -> base
    }

    /** Звуковой цикл без звонка: можно совсем редко. */
    fun avIdleInterval(): Long = if (screenOff) 1000L else 250L
}
