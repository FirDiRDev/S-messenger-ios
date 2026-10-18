// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Отступы под системные панели на любом телефоне: строка состояния сверху, кнопки/жестовая полоска
 * «Домой/Назад» снизу, вырез камеры, клавиатура. Кнопки и текст больше не уходят под чёрную полосу.
 */
fun View.fitSystemBars(top: Boolean = true, bottom: Boolean = true, ime: Boolean = true) {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val kb = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        v.setPadding(
            l + bars.left,
            t + if (top) bars.top else 0,
            r + bars.right,
            b + if (bottom) maxOf(bars.bottom, kb) else 0,
        )
        insets
    }
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
