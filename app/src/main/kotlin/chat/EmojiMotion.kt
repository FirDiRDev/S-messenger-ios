// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Собственные «характерные» анимации эмодзи и стикеров - у каждого вида своя:
 * сердце бьётся, смех трясётся, огонь трепещет, рука машет, грусть «оседает», праздник
 * взрывается, злость дрожит, звезда вращается, животные подпрыгивают и т. д.
 * Работают для любых эмодзи - и там, где нет готовой анимации (флаги, редкие символы),
 * и для стикеров при появлении. [burst] - салют из маленьких копий по нажатию на стикер.
 */
object EmojiMotion {
    enum class Style { HEART, LAUGH, FIRE, WAVE, SAD, PARTY, ANGRY, SPIN, LOVE, COOL, SHOCK, SLEEP, ANIMAL, FOOD, BOUNCE }

    private val hearts = setOf(0x2764, 0x1F9E1, 0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1F90D, 0x1F90E, 0x1F495, 0x1F49E, 0x1F493, 0x1F497, 0x1F496, 0x1F498, 0x1F49D, 0x2763, 0x1F48B)
    private val laugh = setOf(0x1F602, 0x1F923, 0x1F606, 0x1F605, 0x1F601, 0x1F604, 0x1F603, 0x1F600, 0x1F639, 0x1F92A, 0x1F61C, 0x1F61D)
    private val fire = setOf(0x1F525, 0x2728, 0x26A1, 0x1F4A5, 0x1F4AF, 0x1F31F, 0x1F4AB, 0x2604)
    private val wave = setOf(0x1F44B, 0x1F44D, 0x1F44E, 0x1F44F, 0x1F64C, 0x1F918, 0x1F919, 0x270C, 0x1F91E, 0x1F44C, 0x1F4AA, 0x1F64F, 0x1F91D, 0x270B, 0x1F590, 0x1F596, 0x1F90C, 0x1FAF6)
    private val sad = setOf(0x1F622, 0x1F62D, 0x1F61E, 0x1F614, 0x1F97A, 0x1F625, 0x1F613, 0x1F494, 0x1F63F, 0x1F641, 0x2639, 0x1F615)
    private val party = setOf(0x1F389, 0x1F38A, 0x1F973, 0x1F381, 0x1F382, 0x1F386, 0x1F387, 0x1F388, 0x1F37E, 0x1F942, 0x1F3C6, 0x1F947, 0x1F680)
    private val angry = setOf(0x1F621, 0x1F620, 0x1F92C, 0x1F624, 0x1F47F, 0x1F608, 0x1F4A2)
    private val spin = setOf(0x2B50, 0x1F320, 0x1F300, 0x1F31E, 0x2600, 0x1F30D, 0x1F30E, 0x1F30F, 0x26BD, 0x1F3C0, 0x1F3BE, 0x1F3D0, 0x1F3B1, 0x1F4BF, 0x1F4C0, 0x1F3A1, 0x2699, 0x1F36A, 0x1F369)
    private val love = setOf(0x1F60D, 0x1F970, 0x1F618, 0x1F63B, 0x1F617, 0x1F619, 0x1F61A, 0x1F917)
    private val cool = setOf(0x1F60E, 0x1F929, 0x1F911, 0x1F451, 0x1F48E, 0x1F4B0, 0x1F4B8, 0x1F4B5)
    private val shock = setOf(0x1F631, 0x1F628, 0x1F630, 0x1F92F, 0x1F633, 0x1F632, 0x1F62E, 0x1F635, 0x1F47B, 0x1F480, 0x1F6A8)
    private val sleep = setOf(0x1F634, 0x1F971, 0x1F62A, 0x1F4A4, 0x1F319, 0x1F31B, 0x1F31C)

    private fun cps(text: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out += cp
            i += Character.charCount(cp)
        }
        return out
    }

    fun styleFor(emoji: String): Style {
        val c = cps(emoji.trim()).firstOrNull() ?: return Style.BOUNCE
        return when (c) {
            in hearts -> Style.HEART
            in laugh -> Style.LAUGH
            in fire -> Style.FIRE
            in wave -> Style.WAVE
            in sad -> Style.SAD
            in party -> Style.PARTY
            in angry -> Style.ANGRY
            in spin -> Style.SPIN
            in love -> Style.LOVE
            in cool -> Style.COOL
            in shock -> Style.SHOCK
            in sleep -> Style.SLEEP
            in 0x1F400..0x1F43F, in 0x1F980..0x1F9AF, 0x1F648, 0x1F649, 0x1F64A -> Style.ANIMAL
            in 0x1F32D..0x1F37F, in 0x1F950..0x1F96F, 0x2615, 0x1F9C0, 0x1F9C1 -> Style.FOOD
            else -> Style.BOUNCE
        }
    }

    /** Эмодзи из кода Noto («1f602», «2764_fe0f»). */
    fun fromCode(code: String): String = buildString {
        code.split('_').forEach { part -> part.toIntOrNull(16)?.let { appendCodePoint(it) } }
    }

    private fun kf(prop: String, vararg v: Float) = PropertyValuesHolder.ofFloat(prop, *v)

    /**
     * Проиграть анимацию, соответствующую эмодзи, на [v] (текст с эмодзи, картинка стикера).
     * [strong] - стикер: движения крупнее и дольше.
     */
    fun play(v: View, emoji: String, strong: Boolean = false, onEnd: (() -> Unit)? = null) {
        v.animate().cancel()
        val k = if (strong) 1.25f else 1f
        val run = {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            val anim: Animator = when (styleFor(emoji)) {
                // Сердце: двойной удар («тук-тук»), пауза, ещё раз.
                Style.HEART -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("scaleX", 0.5f, 1.22f * k, 1f, 1.16f * k, 1f, 1f, 1.18f * k, 1f, 1.12f, 1f),
                    kf("scaleY", 0.5f, 1.22f * k, 1f, 1.16f * k, 1f, 1f, 1.18f * k, 1f, 1.12f, 1f),
                ).setDuration(1500)
                // Смех: трясётся от хохота и подпрыгивает.
                Style.LAUGH -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("rotation", 0f, -14f * k, 12f * k, -12f * k, 10f * k, -8f, 6f, -3f, 0f),
                    kf("translationY", 0f, -10f * k, 0f, -8f * k, 0f, -5f, 0f, -2f, 0f),
                    kf("scaleX", 0.6f, 1.1f, 1f, 1.06f, 1f, 1.03f, 1f, 1f, 1f),
                    kf("scaleY", 0.6f, 1.1f, 1f, 1.06f, 1f, 1.03f, 1f, 1f, 1f),
                ).setDuration(1100)
                // Огонь и искры: трепещет, «вытягивается» вверх и мерцает.
                Style.FIRE -> {
                    v.pivotY = v.height.toFloat()
                    ObjectAnimator.ofPropertyValuesHolder(
                        v,
                        kf("scaleY", 0.3f, 1.25f * k, 0.94f, 1.12f, 0.97f, 1.08f, 1f),
                        kf("scaleX", 0.5f, 0.92f, 1.06f, 0.96f, 1.03f, 0.98f, 1f),
                        kf("alpha", 0.2f, 1f, 0.82f, 1f, 0.88f, 1f, 1f),
                        kf("rotation", 0f, -4f, 4f, -3f, 2f, -1f, 0f),
                    ).setDuration(1200)
                }
                // Жесты: рука машет от запястья.
                Style.WAVE -> {
                    v.pivotX = v.width * 0.7f
                    v.pivotY = v.height * 0.95f
                    ObjectAnimator.ofPropertyValuesHolder(
                        v,
                        kf("rotation", 0f, -22f * k, 16f * k, -18f * k, 12f, -8f, 0f),
                        kf("scaleX", 0.5f, 1.08f, 1f, 1f, 1f, 1f, 1f),
                        kf("scaleY", 0.5f, 1.08f, 1f, 1f, 1f, 1f, 1f),
                    ).setDuration(1150)
                }
                // Грусть: медленно опускается, «вздыхает» и возвращается.
                Style.SAD -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("translationY", -20f, 0f, 8f * k, 8f * k, 0f),
                    kf("scaleY", 0.8f, 1f, 0.9f, 0.92f, 1f),
                    kf("scaleX", 0.8f, 1f, 1.06f, 1.04f, 1f),
                    kf("rotation", 0f, 0f, -5f, -5f, 0f),
                    kf("alpha", 0f, 1f, 0.85f, 0.85f, 1f),
                ).setDuration(1600)
                // Праздник: вылетает с оборотом и пружинит.
                Style.PARTY -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("scaleX", 0f, 1.35f * k, 0.9f, 1.08f, 1f),
                    kf("scaleY", 0f, 1.35f * k, 0.9f, 1.08f, 1f),
                    kf("rotation", -200f, 20f, -8f, 4f, 0f),
                ).setDuration(1000)
                // Злость: мелкая быстрая дрожь и «надувается».
                Style.ANGRY -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("translationX", 0f, -6f * k, 6f * k, -6f * k, 6f * k, -5f, 5f, -3f, 3f, 0f),
                    kf("scaleX", 0.7f, 1.05f, 1.1f, 1.14f * k, 1.18f * k, 1.12f, 1.06f, 1.02f, 1f, 1f),
                    kf("scaleY", 0.7f, 1.05f, 1.1f, 1.14f * k, 1.18f * k, 1.12f, 1.06f, 1.02f, 1f, 1f),
                ).setDuration(900)
                // Звёзды, мячи, планета: полный оборот с замедлением.
                Style.SPIN -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("rotation", 0f, 360f * (if (strong) 2 else 1)),
                    kf("scaleX", 0.4f, 1.15f, 1f),
                    kf("scaleY", 0.4f, 1.15f, 1f),
                ).setDuration(1300).apply { interpolator = DecelerateInterpolator(1.6f) }
                // Влюблённость: покачивание из стороны в сторону с «пульсом».
                Style.LOVE -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("rotation", 0f, -10f, 10f, -8f, 8f, 0f),
                    kf("scaleX", 0.6f, 1.12f * k, 1f, 1.1f, 1f, 1f),
                    kf("scaleY", 0.6f, 1.12f * k, 1f, 1.1f, 1f, 1f),
                    kf("translationY", 0f, -6f, 0f, -4f, 0f, 0f),
                ).setDuration(1400)
                // Круто: выезжает сбоку и «щёлкает» на месте.
                Style.COOL -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("translationX", -60f * k, 6f, -2f, 0f),
                    kf("rotation", -25f, 6f, -2f, 0f),
                    kf("alpha", 0f, 1f, 1f, 1f),
                ).setDuration(700).apply { interpolator = DecelerateInterpolator() }
                // Шок: резко увеличивается, вздрагивает.
                Style.SHOCK -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("scaleX", 1f, 1.4f * k, 1.2f, 1.3f, 1f),
                    kf("scaleY", 1f, 1.4f * k, 1.2f, 1.3f, 1f),
                    kf("translationY", 0f, -14f, 0f, -6f, 0f),
                    kf("translationX", 0f, 3f, -3f, 2f, 0f),
                ).setDuration(800)
                // Сон: плавно клонится и мерно «дышит».
                Style.SLEEP -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("rotation", 0f, 12f, 10f, 12f, 0f),
                    kf("scaleX", 1f, 1.06f, 0.98f, 1.06f, 1f),
                    kf("scaleY", 1f, 1.06f, 0.98f, 1.06f, 1f),
                    kf("alpha", 0.4f, 1f, 0.85f, 1f, 1f),
                ).setDuration(2000)
                // Животные: «прыг-скок» с приседанием.
                Style.ANIMAL -> {
                    v.pivotY = v.height.toFloat()
                    ObjectAnimator.ofPropertyValuesHolder(
                        v,
                        kf("translationY", 0f, 0f, -22f * k, 0f, 0f, -10f, 0f, 0f),
                        kf("scaleY", 1f, 0.8f, 1.12f, 0.86f, 1f, 1.05f, 0.95f, 1f),
                        kf("scaleX", 1f, 1.15f, 0.92f, 1.1f, 1f, 0.98f, 1.03f, 1f),
                    ).setDuration(1100)
                }
                // Еда: аппетитное «ням» - сжатие с покачиванием.
                Style.FOOD -> ObjectAnimator.ofPropertyValuesHolder(
                    v,
                    kf("scaleX", 0.4f, 1.15f, 0.9f, 1.08f, 0.96f, 1f),
                    kf("scaleY", 0.4f, 0.9f, 1.12f, 0.95f, 1.04f, 1f),
                    kf("rotation", 0f, 6f, -6f, 4f, -2f, 0f),
                ).setDuration(1000)
                // Остальные: прежний мягкий «прыжок» с покачиванием.
                Style.BOUNCE -> {
                    v.pivotY = v.height.toFloat()
                    ObjectAnimator.ofPropertyValuesHolder(
                        v,
                        kf("scaleX", 0.4f, 1.25f, 0.92f, 1.06f, 1f),
                        kf("scaleY", 0.4f, 1.25f, 0.92f, 1.06f, 1f),
                        kf("rotation", 0f, -10f, 8f, -5f, 3f, 0f),
                    ).setDuration(900)
                }
            }
            // Ключевые кадры уже задают «характер» движения - между ними едем равномерно.
            val st = styleFor(emoji)
            if (st != Style.SPIN && st != Style.COOL) anim.interpolator = LinearInterpolator()
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    reset(v)
                    onEnd?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) = reset(v)
            })
            v.setTag(com.stoyko.smessenger.R.id.tag_emoji_motion, anim)
            anim.start()
        }
        (v.getTag(com.stoyko.smessenger.R.id.tag_emoji_motion) as? Animator)?.cancel()
        if (v.width > 0) run() else v.post { run() }
    }

    private fun reset(v: View) {
        v.rotation = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.alpha = 1f
    }

    /** Остановить характерную анимацию (строка списка переиспользуется). */
    fun cancel(v: View) {
        (v.getTag(com.stoyko.smessenger.R.id.tag_emoji_motion) as? Animator)?.cancel()
        v.setTag(com.stoyko.smessenger.R.id.tag_emoji_motion, null)
        reset(v)
    }

    /**
     * Салют: из центра [anchor] разлетаются маленькие копии эмодзи (у сердец - сердечки,
     * у праздника - конфетти, у огня - искры), поднимаются и тают. Рисуется поверх окна.
     */
    fun burst(anchor: View, emoji: String) {
        val root = anchor.rootView as? ViewGroup ?: return
        if (!anchor.isAttachedToWindow) return
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val cx = loc[0] - rootLoc[0] + anchor.width / 2f
        val cy = loc[1] - rootLoc[1] + anchor.height / 2f
        val density = anchor.resources.displayMetrics.density
        val pieces = when (styleFor(emoji)) {
            Style.HEART, Style.LOVE -> listOf("❤️", "💖", "💕", emoji)
            Style.PARTY -> listOf("🎊", "✨", "🎉", emoji)
            Style.FIRE -> listOf("✨", "🔥", "💫", emoji)
            Style.LAUGH -> listOf("😂", "🤣", "💦", emoji)
            Style.SAD -> listOf("💧", "💧", emoji)
            Style.ANGRY -> listOf("💢", "💥", emoji)
            Style.SLEEP -> listOf("💤", "💤", emoji)
            Style.COOL -> listOf("✨", "💎", emoji)
            else -> listOf("✨", emoji, emoji)
        }
        val layer = FrameLayout(anchor.context).apply {
            isClickable = false
            isFocusable = false
        }
        root.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val count = 12
        val sets = ArrayList<Animator>()
        for (i in 0 until count) {
            val tv = TextView(anchor.context).apply {
                text = pieces[i % pieces.size]
                textSize = 14f + Random.nextInt(0, 12)
                gravity = Gravity.CENTER
            }
            layer.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tv.measure(0, 0)
            tv.x = cx - tv.measuredWidth / 2f
            tv.y = cy - tv.measuredHeight / 2f
            tv.scaleX = 0.2f
            tv.scaleY = 0.2f
            val a = (i.toFloat() / count) * 2 * Math.PI + Random.nextDouble(-0.25, 0.25)
            val dist = (70 + Random.nextInt(0, 70)) * density
            val dx = (cos(a) * dist).toFloat()
            val dy = (sin(a) * dist).toFloat() - 30 * density
            val fly = ObjectAnimator.ofPropertyValuesHolder(
                tv,
                kf("translationX", tv.x, tv.x + dx),
                kf("translationY", tv.y, tv.y + dy),
                kf("scaleX", 0.2f, 1.2f, 1f),
                kf("scaleY", 0.2f, 1.2f, 1f),
                kf("rotation", 0f, Random.nextInt(-90, 90).toFloat()),
            ).apply {
                duration = 520L + Random.nextInt(0, 160)
                interpolator = DecelerateInterpolator(1.6f)
            }
            // Затем медленно опускаются, как конфетти, и тают.
            val fall = ObjectAnimator.ofPropertyValuesHolder(
                tv,
                kf("translationY", tv.y + dy, tv.y + dy + 40 * density),
                kf("alpha", 1f, 0f),
            ).apply {
                duration = 520
                interpolator = AccelerateInterpolator()
            }
            sets += AnimatorSet().apply { playSequentially(fly, fall) }
        }
        AnimatorSet().apply {
            playTogether(sets)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(layer)
                }
            })
            start()
        }
        // Сам стикер в ответ «подпрыгивает» в своём стиле.
        play(anchor, emoji, strong = true)
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Мягкое появление нового стикера (не повторяется при прокрутке). */
    fun entrance(v: View, emoji: String) {
        v.scaleX = 0.3f
        v.scaleY = 0.3f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction { play(v, emoji, strong = true) }
            .start()
    }
}
