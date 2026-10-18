// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Dialog
import java.util.WeakHashMap

/**
 * Полноэкранные окна, собранные в коде (игры, S-AI…): когда экран пересоздаётся или
 * закрывается, их надо закрыть вместе с ним - иначе система пишет «утечку окна»
 * (WindowLeaked) и окно может «зависнуть» поверх нового экрана.
 */
object DialogTracker {
    private val open = WeakHashMap<Dialog, Boolean>()

    fun add(d: Dialog): Dialog {
        synchronized(open) { open[d] = true }
        return d
    }

    /**
     * Закрыть все окна, привязанные к [activity]: сначала отслеживаемые (обычное закрытие),
     * затем - все остальные диалоги, меню и всплывающие окна этого экрана, созданные где угодно
     * в приложении (AlertDialog, BottomSheet, PopupWindow…). Вызывается перед уничтожением экрана,
     * поэтому «утечки окна» больше нет ни у одного диалога.
     */
    fun dismissAll(activity: android.app.Activity) {
        val list = synchronized(open) { open.keys.toList() }
        for (d in list) {
            val owner = generateSequence(d.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .firstOrNull { it is android.app.Activity }
            if (owner === activity) {
                runCatching { if (d.isShowing) d.dismiss() }
                synchronized(open) { open.remove(d) }
            }
        }
        sweep(activity)
    }

    /** Все окна процесса (Android 10+ - открытым способом, раньше - через WindowManagerGlobal). */
    private fun windowViews(): List<android.view.View> {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        }
        return runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val g = c.getMethod("getInstance").invoke(null)
            val f = c.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(g) as? List<android.view.View>)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun sweep(activity: android.app.Activity) {
        val main = activity.window?.decorView ?: return
        val token = main.windowToken ?: return
        // Токен самого экрана: у диалогов (AlertDialog, BottomSheet, полноэкранные окна) в
        // параметрах окна стоит именно он, а не токен главного окна - раньше такие окна не
        // находились и в журнале было «WindowLeaked … DecorView[LauncherAutumn]» (4.10.0).
        val appToken = activity.window?.attributes?.token
        val wm = activity.windowManager
        for (v in windowViews()) {
            if (v === main || !v.isAttachedToWindow) continue
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: continue
            // Окна, привязанные к этому экрану (диалоги, меню, всплывающие подсказки).
            val mine = lp.token === token || v.applicationWindowToken === token || (appToken != null && lp.token === appToken)
            if (!mine) continue
            runCatching { wm.removeViewImmediate(v) }
        }
    }
}
