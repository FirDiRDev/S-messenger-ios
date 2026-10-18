// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R

/**
 * Настройки сети (узлы, прокси, «скрыть IP», стабильная связь) применяются после
 * переподключения. Раньше нужно было самому перезапускать приложение - теперь окно
 * «Применить сейчас»: сеть переподключается за несколько секунд, приложение не закрывается.
 */
object RestartPrompt {
    fun network(ctx: Context, message: Int = R.string.apply_network_text) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.apply_network_title)
            .setMessage(message)
            .setPositiveButton(R.string.apply_now) { _, _ -> applyNetwork(ctx) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    /** Переподключить сеть сейчас (ядро перезапускается с новыми настройками). */
    fun applyNetwork(ctx: Context) {
        val c = (ctx.applicationContext as App).component
        // Список узлов перечитывается заново (новые/проверенные узлы - сразу в работу).
        runCatching { c.nodeRegistry().reset() }
        val ok = runCatching { c.toxStarter().restartAsync() }.isSuccess
        Toast.makeText(ctx, if (ok) R.string.applying_network else R.string.setting_applies_after_restart, Toast.LENGTH_SHORT).show()
    }
}
