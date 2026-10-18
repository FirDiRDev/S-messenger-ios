// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan

/** Реакции крупными «таблетками»: эмодзи, число и аватарки поставивших (без наложения). */
object ReactionChips {
    /** (эмодзи, поставил я, поставил собеседник) → строка таблеток для подписи под сообщением. */
    fun build(
        ctx: Context,
        parts: List<Triple<String, Boolean, Boolean>>,
        peer: android.graphics.Bitmap?,
        mine: android.graphics.Bitmap?,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append(" ")
            val avatars = listOfNotNull(if (p.third) peer else null, if (p.second) mine else null)
            val count = (if (p.second) 1 else 0) + (if (p.third) 1 else 0)
            val st = sb.length
            sb.append(p.first)
            sb.setSpan(Chip(ctx, p.first, if (avatars.isEmpty() && count > 1) count else 0, avatars, p.second), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** Одна таблетка (рисуется сама: без лишних объектов при отрисовке). */
    class Chip(
        ctx: Context,
        private val emoji: String,
        private val count: Int,
        private val avatars: List<android.graphics.Bitmap>,
        private val mine: Boolean,
    ) : ReplacementSpan() {
        private val d = ctx.resources.displayMetrics.density
        private val emojiSize = 19 * d
        private val avatar = 20 * d
        private val padH = 8 * d
        private val gap = 4 * d
        private val height = 30 * d
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (mine) 0x405B4BDB else 0x26808080 }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            color = if (mine) 0xCC7B6CF6.toInt() else 0x33808080
        }
        private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = emojiSize }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            color = android.graphics.Color.WHITE
        }
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val dst = RectF()
        private val clip = android.graphics.Path()
        private val emojiW = emojiPaint.measureText(emoji)
        private val label = if (count > 1) count.toString() else ""

        private fun width(paint: Paint): Float {
            var w = padH * 2 + emojiW
            if (label.isNotEmpty()) w += gap + paint.measureText(label) * 1.1f
            if (avatars.isNotEmpty()) w += gap + avatars.size * avatar + (avatars.size - 1) * gap
            return w
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val m = paint.fontMetricsInt
                val extra = ((height - (m.descent - m.ascent)) / 2).toInt().coerceAtLeast(0) + (2 * d).toInt()
                it.ascent = m.ascent - extra
                it.top = m.top - extra
                it.descent = m.descent + extra
                it.bottom = m.bottom + extra
            }
            return (width(paint) + 2 * d).toInt()
        }

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val fm = paint.fontMetrics
            val cy = y + (fm.ascent + fm.descent) / 2
            val w = width(paint)
            rect.set(x + d, cy - height / 2, x + d + w, cy + height / 2)
            c.drawRoundRect(rect, height / 2, height / 2, bg)
            c.drawRoundRect(rect, height / 2, height / 2, stroke)
            var cx = rect.left + padH
            val efm = emojiPaint.fontMetrics
            c.drawText(emoji, cx, cy - (efm.ascent + efm.descent) / 2, emojiPaint)
            cx += emojiW
            if (label.isNotEmpty()) {
                cx += gap
                val oldBold = paint.isFakeBoldText
                val oldSize = paint.textSize
                paint.isFakeBoldText = true
                paint.textSize = oldSize * 1.1f
                c.drawText(label, cx, cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
                cx += paint.measureText(label)
                paint.textSize = oldSize
                paint.isFakeBoldText = oldBold
            }
            for (bmp in avatars) {
                cx += gap
                dst.set(cx, cy - avatar / 2, cx + avatar, cy + avatar / 2)
                c.save()
                clip.reset()
                clip.addOval(dst, android.graphics.Path.Direction.CW)
                c.clipPath(clip)
                c.drawBitmap(bmp, null, dst, bmpPaint)
                c.restore()
                c.drawOval(dst, ring)
                cx += avatar
            }
        }
    }
}
