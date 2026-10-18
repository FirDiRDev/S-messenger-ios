// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.squareup.picasso.Picasso
import com.stoyko.smessenger.R

/**
 * Перед отправкой медиа и файлов: превью выбранного, поле «Подпись» и
 * кнопка «Отправить». Подпись уходит сообщением сразу после файлов.
 */
object SendPreview {
    /** Выбор «под спойлером» в последнем окне (читается в onSend). */
    var lastSpoiler = false
        private set

    fun show(ctx: Context, uris0: List<Uri>, initialCaption: String = "", onSend: (caption: String) -> Unit) {
        // Один и тот же файл, выбранный дважды, - одна отправка.
        val uris = uris0.distinct()
        if (uris.isEmpty()) return
        lastSpoiler = false
        val thumbs = ArrayList<ImageView>()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        root.addView(
            TextView(ctx).apply {
                text = ctx.resources.getQuantityString(R.plurals.share_files_title, uris.size, uris.size)
                textSize = 17f
                paint.isFakeBoldText = true
                setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                setPadding(0, 0, 0, dp(10))
            },
        )
        // Превью: картинки и видео - миниатюры, остальное - имя файла.
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        uris.take(20).forEach { u ->
            val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull().orEmpty()
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(0x22000000)
                    }
                }
                Picasso.get().load(u).resize(dp(96), dp(96)).centerCrop().into(iv)
                thumbs += iv
                strip.addView(iv, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) })
            } else {
                val name = runCatching {
                    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: u.lastPathSegment.orEmpty()
                strip.addView(
                    TextView(ctx).apply {
                        text = "📎 $name"
                        maxLines = 3
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 12 * d
                            setColor(0x1F5B4BDB)
                        }
                    },
                    LinearLayout.LayoutParams(dp(120), dp(96)).apply { marginEnd = dp(8) },
                )
            }
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        val caption = EditText(ctx).apply {
            setHint(R.string.caption_hint)
            setText(initialCaption)
            maxLines = 5
        }
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        // Фото/видео можно скрыть под спойлер: у получателя - размытая пелена с искрами до нажатия.
        var spoiler = false
        if (thumbs.isNotEmpty()) {
            val sw = android.widget.Switch(ctx).apply {
                setText(R.string.spoiler_media)
                textSize = 14.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, on ->
                    spoiler = on
                    thumbs.forEachIndexed { i, iv ->
                        iv.animate().alpha(if (on) 0.35f else 1f).scaleX(if (on) 0.94f else 1f).scaleY(if (on) 0.94f else 1f)
                            .setStartDelay(i * 30L).setDuration(220).start()
                    }
                }
            }
            root.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val cancel = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(android.R.string.cancel)
            isAllCaps = false
            setOnClickListener { sheet.dismiss() }
        }
        // Окно закрывается с анимацией - второе быстрое нажатие «Отправить» раньше отправляло всё дважды.
        var sent = false
        val send = MaterialButton(ctx).apply {
            setText(R.string.send)
            isAllCaps = false
            setOnClickListener {
                if (sent) return@setOnClickListener
                sent = true
                isEnabled = false
                sheet.dismiss()
                lastSpoiler = spoiler
                onSend(caption.text.toString().trim())
            }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        row.addView(send, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(row)
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

}
