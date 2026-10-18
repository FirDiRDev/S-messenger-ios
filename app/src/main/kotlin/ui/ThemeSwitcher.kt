// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.Settings

/**
 * Смена дневной/ночной темы одной кнопкой «солнце/луна» с плавным переходом, как в macOS:
 * иконка поворачивается и превращается из луны в солнце (и обратно), а экран не «мигает» -
 * старая картинка мягко растворяется поверх новой темы.
 */
object ThemeSwitcher {
    private var snapshot: Bitmap? = null
    private var snapshotAt = 0L

    fun isNight(activity: Activity) =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Правильная иконка: ночью - солнце (включить день), днём - луна (включить ночь). */
    fun bindIcon(button: ImageView, activity: Activity) {
        button.setImageResource(if (isNight(activity)) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
        button.rotation = 0f
        button.scaleX = 1f
        button.scaleY = 1f
    }

    fun toggle(activity: Activity, button: ImageView) {
        val night = isNight(activity)
        // Снимок экрана до смены темы (для плавного растворения после пересоздания экрана).
        runCatching {
            val decor = activity.window.decorView
            if (decor.width > 0 && decor.height > 0) {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bmp))
                snapshot?.recycle()
                snapshot = bmp
                snapshotAt = System.currentTimeMillis()
            }
        }
        // Иконка: поворот и «схлопывание», затем смена луны на солнце и «раскрытие».
        button.animate().cancel()
        button.animate().rotation(180f).scaleX(0.2f).scaleY(0.2f).setDuration(170)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                button.setImageResource(if (night) R.drawable.ic_dark_mode else R.drawable.ic_light_mode)
                button.rotation = -180f
                button.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(2f))
                    .withEndAction {
                        Settings(activity).theme =
                            if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                    }
                    .start()
            }
            .start()
    }

    /** Вызывается из MainActivity.onCreate после setContentView: растворяем старую картинку. */
    fun onActivityCreated(activity: Activity) {
        val bmp = snapshot ?: return
        snapshot = null
        // Старый снимок (тема так и не сменилась) не показываем.
        if (System.currentTimeMillis() - snapshotAt > 5000) {
            bmp.recycle()
            return
        }
        val decor = activity.window.decorView as? ViewGroup ?: run {
            bmp.recycle()
            return
        }
        val cover = ImageView(activity).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cover.animate().alpha(0f).setStartDelay(60).setDuration(520)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                decor.removeView(cover)
                cover.setImageDrawable(null)
                bmp.recycle()
            }
            .start()
    }
}
