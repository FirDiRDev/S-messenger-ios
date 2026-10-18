// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R

/**
 * Окно экспорта/импорта профиля: этап, полоса и проценты, сколько обработано. Обновлять
 * можно из любого потока - перерисовка идёт в главном.
 */
class BackupProgress private constructor(
    private val ctx: Context,
    private val dialog: AlertDialog,
    private val bar: ProgressBar,
    private val percent: TextView,
    private val info: TextView,
) : FullBackup.Progress {
    private val main = Handler(Looper.getMainLooper())

    override fun update(stage: FullBackup.Stage, done: Long, total: Long) {
        main.post {
            if (!dialog.isShowing) return@post
            val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
            bar.isIndeterminate = p < 0
            if (p >= 0) {
                bar.progress = p
                percent.text = ctx.getString(R.string.smsave_percent, p)
            }
            val sizes = if (total > 0 && stage != FullBackup.Stage.PREPARE) {
                Formatter.formatShortFileSize(ctx, done.coerceAtMost(total)) + " / " + Formatter.formatShortFileSize(ctx, total)
            } else {
                ""
            }
            info.text = ctx.getString(stageText(stage)) + if (sizes.isEmpty()) "" else "\n" + sizes
        }
    }

    fun dismiss() {
        main.post { runCatching { if (dialog.isShowing) dialog.dismiss() } }
    }

    private fun stageText(stage: FullBackup.Stage) = when (stage) {
        FullBackup.Stage.PREPARE -> R.string.smsave_stage_prepare
        FullBackup.Stage.WRITE -> R.string.smsave_stage_write
        FullBackup.Stage.VERIFY -> R.string.smsave_stage_verify
        FullBackup.Stage.READ -> R.string.smsave_stage_read
    }

    companion object {
        /** Показать окно (из главного потока). [title] - «Экспорт профиля» или «Импорт профиля». */
        fun show(ctx: Context, title: Int): BackupProgress {
            val d = ctx.resources.displayMetrics.density
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = true
            }
            val percent = TextView(ctx).apply {
                textSize = 28f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                text = ctx.getString(R.string.smsave_percent, 0)
            }
            val info = TextView(ctx).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                text = ctx.getString(R.string.smsave_stage_prepare)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
                addView(percent)
                addView(bar)
                addView(info)
            }
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .show()
            return BackupProgress(ctx, dialog, bar, percent, info)
        }
    }
}
