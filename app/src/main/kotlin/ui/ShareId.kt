// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import io.nayuki.qrcodegen.QrCode
import java.io.File

/**
 * Общие действия с ID: скопировать, показать QR, отправить вместе с установочным файлом
 * мессенджера и инструкцией (как установить, создать профиль и добавить человека).
 */
object ShareId {
    fun copy(ctx: Context, id: String, toast: Int = R.string.copied) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("ID", id))
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }

    /** QR в том же формате, что и «Мой QR» (сканер в «Добавить контакт» его понимает). */
    fun qr(id: String, sizePx: Int): Bitmap {
        // Только ID - без «smessenger:» и прочего текста.
        val q = QrCode.encodeText(id.trim().uppercase(), QrCode.Ecc.MEDIUM)
        val quiet = 2
        val cells = q.size + quiet * 2
        val scale = (sizePx / cells).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(cells * scale, cells * scale, Bitmap.Config.RGB_565)
        val px = IntArray(bmp.width * bmp.height) { Color.WHITE }
        for (y in 0 until q.size) for (x in 0 until q.size) {
            if (!q.getModule(x, y)) continue
            val x0 = (x + quiet) * scale
            val y0 = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (y0 + dy) * bmp.width
                for (dx in 0 until scale) px[row + x0 + dx] = Color.BLACK
            }
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    fun showQr(ctx: Context, title: CharSequence, id: String) {
        val d = ctx.resources.displayMetrics.density
        val size = (minOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) * 0.7f).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, 0)
        }
        box.addView(
            ImageView(ctx).apply {
                setImageBitmap(qr(id, size))
                adjustViewBounds = true
            },
            LinearLayout.LayoutParams(size, size),
        )
        box.addView(
            TextView(ctx).apply {
                text = id
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                gravity = Gravity.CENTER
                setPadding(0, (10 * d).toInt(), 0, 0)
            },
        )
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.copy) { _, _ -> copy(ctx, id) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Копия установочного файла этого приложения (для отправки другу). null - не получилось. */
    fun apkUri(ctx: Context): Uri? = runCatching {
        val src = File(ctx.applicationInfo.sourceDir)
        val dir = File(ctx.cacheDir, "invite").apply { mkdirs() }
        val out = File(dir, "StoykoMessenger-${BuildConfig.VERSION_NAME}.apk")
        if (!out.exists() || out.length() != src.length()) src.copyTo(out, overwrite = true)
        FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", out)
    }.getOrNull()

    /** Инструкция отдельным файлом: многие мессенджеры при отправке файла теряют текст. */
    private fun instructionUri(ctx: Context, text: String): Uri? = runCatching {
        val dir = File(ctx.cacheDir, "invite").apply { mkdirs() }
        val out = File(dir, ctx.getString(R.string.invite_instruction_file))
        out.writeText(text)
        FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", out)
    }.getOrNull()

    /**
     * Отправить текст с ID. withApk - приложить установочный файл и инструкцию:
     * получатель ставит приложение, создаёт профиль и добавляет ID.
     */
    fun send(ctx: Context, text: String, withApk: Boolean, chooserTitle: CharSequence) {
        val streams = ArrayList<Uri>()
        if (withApk) {
            apkUri(ctx)?.let { streams += it }
            instructionUri(ctx, text)?.let { streams += it }
        }
        val intent = when {
            streams.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            streams.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, streams[0])
                clipData = ClipData.newRawUri("apk", streams[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_TEXT, text)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                clipData = ClipData.newRawUri("apk", streams[0]).apply {
                    for (i in 1 until streams.size) addItem(ClipData.Item(streams[i]))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, chooserTitle)) }
            .onFailure { Toast.makeText(ctx, R.string.share_failed, Toast.LENGTH_LONG).show() }
    }
}
