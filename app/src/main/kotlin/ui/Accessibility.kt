// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView

/**
 * Режим для слабовидящих (Настройки → Персонализация).
 *
 * Общие правила (как и раньше):
 *  - элементы НЕ растягиваются (плотность прежняя) - всё помещается на экране;
 *  - шрифт крупнее и жирнее (Android 12+), текст сообщений крупный;
 *  - без «жидкого стекла» и размытия - текст на однотонном фоне контрастнее.
 *
 * Доработка (4.7.2):
 *  - три уровня размера текста («Крупный», «Очень крупный», «Максимальный»); предел
 *    зависит от устройства ([DeviceProfile]): бюджетные 720p и «раскладушки» - ниже,
 *    обычные телефоны - средний, широкие телефоны, планшеты и складные - выше;
 *  - «Высокий контраст»: серые и полупрозрачные подписи (время, статусы, подсказки)
 *    становятся чисто белыми или чёрными - в зависимости от фона под ними;
 *  - «Меньше движения»: без пружинок, бликов и волн (как при выключенных анимациях),
 *    а также автоматически, если в системе отключены анимации (Спец. возможности).
 * При выключении всё возвращается как было. Включение пересоздаёт окно - видно сразу.
 */
object Accessibility {
    private const val PREFS = "ui"
    private const val KEY = "a11y"
    private const val KEY_LEVEL = "a11y_level"
    private const val KEY_CONTRAST = "a11y_contrast"
    private const val KEY_MOTION = "a11y_motion"

    /** Масштаб шрифта уровня «Крупный» (для совместимости со старым кодом). */
    const val FONT_SCALE = 1.2f

    /** Уровни размера текста: крупный / очень крупный / максимальный. */
    private val LEVEL_SCALE = floatArrayOf(1.2f, 1.3f, 1.4f)
    private val LEVEL_MESSAGE = floatArrayOf(20f, 22f, 24f)

    /** Прибавка к толщине шрифта (Android 12+): 400 → 600, полужирный читается заметно лучше. */
    const val FONT_WEIGHT_BOOST = 200
    const val MESSAGE_TEXT = 20f

    @Volatile
    private var cached: Boolean? = null

    // Что было до включения режима - чтобы вернуть при выключении.
    @Volatile
    private var userMsgSize: Float? = null

    @Volatile
    private var userLiquid: Boolean? = null

    @Volatile
    private var userKinetics: Boolean? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = cached ?: runCatching {
        prefs(ctx).getBoolean(KEY, false)
    }.getOrDefault(false).also { cached = it }

    fun set(ctx: Context, on: Boolean) {
        cached = on
        prefs(ctx).edit().putBoolean(KEY, on).apply()
        applyRuntime(ctx)
    }

    /** Уровень размера текста: 0 - крупный, 1 - очень крупный, 2 - максимальный. */
    fun level(ctx: Context): Int = runCatching { prefs(ctx).getInt(KEY_LEVEL, 0) }.getOrDefault(0).coerceIn(0, 2)

    fun setLevel(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_LEVEL, v.coerceIn(0, 2)).apply()
        applyRuntime(ctx)
    }

    /** Высокий контраст (по умолчанию включён вместе с режимом). */
    fun contrast(ctx: Context): Boolean = isOn(ctx) && runCatching { prefs(ctx).getBoolean(KEY_CONTRAST, true) }.getOrDefault(true)

    fun setContrast(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_CONTRAST, v).apply()

    fun contrastSetting(ctx: Context): Boolean = runCatching { prefs(ctx).getBoolean(KEY_CONTRAST, true) }.getOrDefault(true)

    /** «Меньше движения» (по умолчанию включено вместе с режимом). */
    fun motionSetting(ctx: Context): Boolean = runCatching { prefs(ctx).getBoolean(KEY_MOTION, true) }.getOrDefault(true)

    fun setMotion(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MOTION, v).apply()
        applyRuntime(ctx)
    }

    /** В системе отключены анимации (Настройки → Спец. возможности → «Убрать анимации»). */
    fun systemNoAnimations(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Нужно ли сейчас убирать «лишнее» движение. */
    fun reduceMotion(ctx: Context): Boolean = (isOn(ctx) && motionSetting(ctx)) || systemNoAnimations(ctx)

    /**
     * Предел шрифта в режиме для слабовидящих - по классу устройства: на узких и бюджетных
     * экранах крупнее нельзя (кнопки и шапка перестают помещаться), на планшетах можно больше.
     */
    fun maxFontScale(ctx: Context): Float {
        val sw = ctx.resources.configuration.smallestScreenWidthDp
        return when (runCatching { DeviceProfile.get(ctx).kind }.getOrDefault(DeviceProfile.Kind.PHONE)) {
            DeviceProfile.Kind.BUDGET -> 1.25f
            DeviceProfile.Kind.FLIP -> 1.22f
            DeviceProfile.Kind.TABLET, DeviceProfile.Kind.FOLD -> 1.45f
            else -> if (sw >= 400) 1.4f else 1.32f
        }
    }

    /** Масштаб шрифта для текущего уровня с учётом устройства. */
    fun fontScale(ctx: Context): Float = minOf(LEVEL_SCALE[level(ctx)], maxFontScale(ctx))

    /** Размер текста сообщений: по уровню, на планшетах - на 2 sp крупнее. */
    fun messageText(ctx: Context): Float {
        val tablet = runCatching { DeviceProfile.tabletLike(ctx) }.getOrDefault(false)
        return LEVEL_MESSAGE[level(ctx)] + if (tablet) 2f else 0f
    }

    /**
     * То, что меняется без перезапуска: размер текста сообщений, стекло, движение.
     * @param fresh - значения только что загружены из настроек (запоминаем их как «свои»).
     */
    fun applyRuntime(ctx: Context, fresh: Boolean = false) {
        if (isOn(ctx)) {
            if (fresh || userMsgSize == null) userMsgSize = UiPrefs.messageTextSize
            if (fresh || userLiquid == null) userLiquid = GlassFrameLayout.liquidEnabled
            val target = messageText(ctx)
            // Не меньше уровня режима, но и не меньше того, что пользователь выбрал сам.
            UiPrefs.messageTextSize = maxOf(userMsgSize ?: UiPrefs.messageTextSize, target)
            GlassFrameLayout.liquidEnabled = false
        } else {
            userMsgSize?.let { UiPrefs.messageTextSize = it }
            userLiquid?.let { GlassFrameLayout.liquidEnabled = it }
            userMsgSize = null
            userLiquid = null
        }
        if (reduceMotion(ctx)) {
            if (fresh || userKinetics == null) userKinetics = Kinetics.enabled
            Kinetics.enabled = false
            GlassFrameLayout.liquidEnabled = false
        } else {
            userKinetics?.let { Kinetics.enabled = it }
            userKinetics = null
        }
    }

    // ------------------------------------------------------------------ высокий контраст

    /**
     * Подключить «Высокий контраст» к окну: после каждой перекладки экрана (не чаще 4 раз
     * в секунду) серые и полупрозрачные подписи доводятся до полного контраста с фоном.
     * Цветные надписи (ссылки, ошибки, акценты) не трогаем.
     */
    fun install(activity: Activity) {
        if (!contrast(activity)) return
        val root = activity.window?.decorView ?: return
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val windowBg = if (night) Color.BLACK else Color.WHITE
        var last = 0L
        var pending = false
        val pass = Runnable {
            pending = false
            last = SystemClock.uptimeMillis()
            runCatching { boost(root, windowBg) }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener {
                if (pending) return@OnGlobalLayoutListener
                pending = true
                val wait = (250L - (SystemClock.uptimeMillis() - last)).coerceAtLeast(16L)
                root.postDelayed(pass, wait)
            },
        )
    }

    /**
     * Обход экрана. [knownBg] - однотонный фон под элементом, если его удалось определить
     * (null - фон картинкой/9-patch, например пузырь сообщения).
     */
    private fun boost(v: View, knownBg: Int?) {
        if (v.visibility != View.VISIBLE) return
        val d = v.background
        val bg = if (d == null) {
            knownBg
        } else {
            val sc = solidColor(d)
            when {
                sc == null -> null // картинка, 9-patch, слои - цвет фона неизвестен
                Color.alpha(sc) >= 200 -> sc
                else -> knownBg // полупрозрачная подложка - решает фон под ней
            }
        }
        if (v is TextView) fixText(v, bg)
        if (v is ViewGroup) for (i in 0 until v.childCount) boost(v.getChildAt(i), bg)
    }

    private fun fixText(t: TextView, bg: Int?) {
        // Неактивные кнопки и пункты должны оставаться бледными - так видно, что они недоступны.
        if (!t.isEnabled) return
        target(t.currentTextColor, bg)?.let { t.setTextColor(it) }
        if (t is EditText) target(t.currentHintTextColor, bg)?.let { t.setHintTextColor(withAlpha(it, 0xB0)) }
    }

    /**
     * Куда довести цвет текста (null - не трогать). Меняем только серые/полупрозрачные цвета
     * и только «в свою сторону»: светлый - до белого, тёмный - до чёрного. Так белая подпись
     * на цветном пузыре не станет чёрной, даже если фон под ней неизвестен.
     */
    private fun target(c: Int, bg: Int?): Int? {
        if (Color.alpha(c) == 0) return null
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] >= 0.18f && Color.alpha(c) == 255) return null
        val solid = c or 0xFF000000.toInt()
        val light = luminance(solid) > 0.35
        val want = if (light) Color.WHITE else Color.BLACK
        // Фон: известный - он; неизвестный - противоположный цвету текста (как задумано темой).
        val back = bg ?: if (light) Color.BLACK else Color.WHITE
        // Фон «не того» тона (светлый текст на светлом) - не наш случай, оставляем.
        if (contrastRatio(want, back) < 4.5f) return null
        return if (contrastRatio(blend(c, back), back) < 7f && c != want) want else null
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun blend(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun ch(f: Int, b: Int) = (f * a + b * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)))
    }

    private fun luminance(c: Int): Double {
        fun lin(x: Int): Double {
            val s = x / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
    }

    private fun contrastRatio(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return ((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)).toFloat()
    }

    /** Цвет однотонного фона (или средний цвет градиента); null - фон неизвестен/картинка. */
    private fun solidColor(d: Drawable?): Int? {
        if (d is ColorDrawable) return d.color
        if (d is GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            val cols = d.colors
            return if (cols != null && cols.isNotEmpty()) averageColor(cols) else d.color?.defaultColor
        }
        return null
    }

    private fun averageColor(cols: IntArray): Int {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (c in cols) {
            a += Color.alpha(c)
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
        }
        val n = cols.size
        return Color.argb(a / n, r / n, g / n, b / n)
    }
}
