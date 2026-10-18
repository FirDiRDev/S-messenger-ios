// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.Kinetics

/**
 * Перед публикацией истории: превью (фото или кадр видео), подпись и кнопка «Опубликовать».
 */
object StoryComposer {
    fun show(ctx: Context, preview: Bitmap?, video: Boolean, durationMs: Long, onPublish: (caption: String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        root.addView(
            TextView(ctx).apply {
                setText(R.string.story_compose_title)
                textSize = 18f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary))
            },
        )
        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(Color.BLACK)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        frame.addView(
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(preview)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        if (video) {
            frame.addView(
                TextView(ctx).apply {
                    text = "▶  " + formatDuration(durationMs)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(0x88000000.toInt())
                    }
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(10), 0, 0, dp(10))
                },
            )
        }
        root.addView(frame, LinearLayout.LayoutParams(dp(180), dp(300)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        val caption = EditText(ctx).apply {
            setHint(R.string.story_caption_hint)
            maxLines = 4
            textSize = 15f
            filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(
            Btn.primary(ctx, ctx.getString(R.string.story_publish)) {
                sheet.dismiss()
                onPublish(caption.text.toString().trim())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) },
        )
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
        Kinetics.springIn(frame, dy = 30f)
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Превью: фото (уменьшенное) или первый кадр видео. */
    fun previewOf(ctx: Context, uri: Uri, video: Boolean): Bitmap? = runCatching {
        if (video) {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                mmr.getFrameAtTime(0)
            } finally {
                runCatching { mmr.release() }
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 900) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()
}
