// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.R

/**
 * Строка «Архив» в начале списка (видна всегда). В режиме архива
 * превращается в «← Назад к чатам».
 */
class ArchiveRow(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val icon = com.stoyko.smessenger.ui.ArchiveAvatarView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val badge = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(8))
        val bg = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) setBackgroundResource(bg.resourceId)

        // Живая аватарка: коробка-архив, в которую время от времени «влетает» письмо.
        addView(icon, LayoutParams(dp(38), dp(38)))

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.textSize = 15f
        title.paint.isFakeBoldText = true
        @Suppress("DEPRECATION")
        title.setTextColor(resources.getColor(R.color.textPrimary))
        subtitle.textSize = 13f
        @Suppress("DEPRECATION")
        subtitle.setTextColor(resources.getColor(R.color.textSecondary))
        texts.addView(title)
        texts.addView(subtitle)
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })

        badge.textSize = 12f
        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(22)
        badge.setPadding(dp(6), dp(2), dp(6), dp(2))
        badge.background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(0xFF8A8FA3.toInt())
        }
        addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Нажатие на «Архив»: в коробку сразу «влетает» письмо. */
    override fun performClick(): Boolean {
        icon.poke()
        return super.performClick()
    }

    /** backMode - мы внутри архива: строка ведёт назад. */
    fun bind(count: Int, unread: Int, backMode: Boolean) {
        if (backMode) {
            title.setText(R.string.archive_back)
            subtitle.visibility = GONE
            badge.visibility = GONE
            icon.alpha = 0.6f
            return
        }
        icon.alpha = 1f
        title.setText(R.string.archive)
        subtitle.visibility = VISIBLE
        subtitle.text = context.getString(R.string.archive_count, count)
        badge.visibility = if (unread > 0) VISIBLE else GONE
        badge.text = unread.toString()
    }
}

/** Маленький аватар из файла (для полосы историй). */
object SmallAvatars {
    private val cache = HashMap<String, Bitmap?>()

    fun load(path: String?, size: Int): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val clean = path.removePrefix("file://")
        return cache.getOrPut("$clean@$size") {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(clean, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeFile(clean, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clear() = cache.clear()
}

/** Контейнер заголовка списка: скрытие без «дыры» в ListView. */
class HeaderBox(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    fun show(child: View, visible: Boolean) {
        child.visibility = if (visible) VISIBLE else GONE
    }
}
