// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File
import com.stoyko.smessenger.R

/**
 * Окно расшифровки голосового - общее для личных чатов, групп и каналов:
 * готовый текст показывается сразу, иначе - «Расшифровываю…» и результат.
 */
object VoiceTextUi {
    /**
     * [file] - файл голосового (null - не найден). [requestMic] - попросить разрешение микрофона
     * (нужно распознаванию телефона, если нет ключа Groq). [alive] - экран ещё открыт.
     */
    fun run(ctx: Context, file: File?, name: String, size: Long, requestMic: () -> Unit, alive: () -> Boolean = { true }) {
        VoiceTranscriber.get(ctx, name, size)?.let { show(ctx, it); return }
        if (file == null || !file.exists()) {
            Toast.makeText(ctx, R.string.voice_text_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        if (!VoiceTranscriber.hasCloud(ctx) &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, R.string.voice_text_mic, Toast.LENGTH_LONG).show()
            requestMic()
            return
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_text_title)
            .setMessage(R.string.voice_text_progress)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        VoiceTranscriber.transcribe(ctx, file, name, size) { r ->
            // Окно закрыли «Отмена» - текст всё равно сохранится и появится под голосовым.
            val wasShown = dlg.isShowing
            runCatching { dlg.dismiss() }
            if (!alive() || !wasShown) return@transcribe
            r.onSuccess { show(ctx, it) }.onFailure {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.voice_text_title)
                    .setMessage(it.message ?: it.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    fun show(ctx: Context, text: String) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_text_title)
            .setMessage(text)
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("voice", text))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Длительность голосового нажимается (расшифровка) и поэтому забирает касание у строки -
     * удержание на ней передаём списку, чтобы меню сообщения открывалось как обычно.
     */
    fun forwardLongPress(v: android.view.View): Boolean {
        var p = v.parent
        var child: android.view.View = v
        while (p != null && p !is android.widget.AbsListView) {
            if (p is android.view.View) child = p
            p = p.parent
        }
        val list = p as? android.widget.AbsListView ?: return false
        val pos = list.getPositionForView(child)
        if (pos < 0) return false
        list.onItemLongClickListener?.let { return it.onItemLongClick(list, child, pos, list.getItemIdAtPosition(pos)) }
        return list.showContextMenuForChild(child)
    }
}
