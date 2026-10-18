// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R

/**
 * Аккаунт разработчика S-messenger (Стойко Андрей).
 *
 * Проверка идёт по ОТКРЫТОМУ КЛЮЧУ аккаунта (первые 64 символа Tox ID): ключ подтверждается
 * самим протоколом при каждом соединении, подделать его без закрытого ключа нельзя.
 * Поэтому значок «Разработчик S-messenger» нельзя присвоить себе, а у разработчика:
 *  - монеты не кончаются: все покупки (подарки, скины, бустеры, ходы, ставки) бесплатны;
 *  - дарить друзьям можно сколько угодно монет и подарков.
 */
object DevAccount {
    /** Tox ID разработчика: F8D9…1245 + nospam 519F6107 + контрольная сумма B930. */
    const val TOX_ID = "F8D9758FDE926935379CD87AD8F7021790D5180F4F6ADCDE95F4539149111245519F6107B930"
    const val PUBLIC_KEY = "F8D9758FDE926935379CD87AD8F7021790D5180F4F6ADCDE95F4539149111245"

    /**
     * Дизайнер S-messenger (Tox ID 7D30…C45C + nospam 7721CC4C + контрольная сумма D43A):
     * у всех в его профиле - плашка «🎨 Дизайнер S-messenger», в группах имя - сиреневым.
     * Только значок, без особых прав на монеты.
     */
    const val DESIGNER_ID = "7D30D68307BB9182D0B4F638B6818B9A0C566038A8369382A007EC3696FDC45C7721CC4CD43A"
    const val DESIGNER_KEY = "7D30D68307BB9182D0B4F638B6818B9A0C566038A8369382A007EC3696FDC45C"

    /**
     * Тестировщики S-messenger (4.10.0): у всех в их профиле - золотая плашка
     * «🧪 Тестировщик S-messenger». Только значок, без особых прав. Проверка - по открытому ключу
     * (первые 64 символа ID), его подтверждает протокол при каждом соединении.
     */
    val TESTER_IDS = listOf(
        "C15EB3973461B83BCEF069D3351E559BAF434094A24B4AFB04EF0636F410515CE356395743C6",
        "A1D8DCD9B83397189BDC43A84E23D7ECC148F11C11271DEE5355BCFE104C7B7FC359DFA4B769",
    )
    private val TESTER_KEYS = TESTER_IDS.map { it.substring(0, 64).uppercase() }.toSet()

    /** Ключ [pk] (или Tox ID) - тестировщика? */
    fun isTester(pk: String?): Boolean = pk != null && pk.length >= 64 && pk.substring(0, 64).uppercase() in TESTER_KEYS

    /** Открыт ли сейчас аккаунт тестировщика. */
    fun isMeTester(ctx: Context): Boolean {
        val tox = runCatching { (ctx.applicationContext as App).component.tox() }.getOrNull() ?: return false
        if (!runCatching { tox.started }.getOrDefault(false)) return false
        return isTester(runCatching { tox.publicKey.string() }.getOrNull())
    }

    /** Ключ [pk] (или Tox ID) - дизайнера? */
    fun isDesigner(pk: String?): Boolean = pk != null && pk.length >= 64 && pk.substring(0, 64).equals(DESIGNER_KEY, ignoreCase = true)

    /** Открыт ли сейчас аккаунт дизайнера. */
    fun isMeDesigner(ctx: Context): Boolean {
        val tox = runCatching { (ctx.applicationContext as App).component.tox() }.getOrNull() ?: return false
        if (!runCatching { tox.started }.getOrDefault(false)) return false
        return isDesigner(runCatching { tox.publicKey.string() }.getOrNull())
    }

    /** Сколько монет «видно» на счету разработчика (тратится - не убывает). */
    const val DEV_BALANCE = 9_999_999

    @Volatile
    private var me: Boolean? = null

    /** Ключ [pk] (или Tox ID) - разработчика? */
    fun isDev(pk: String?): Boolean = pk != null && pk.length >= 64 && pk.substring(0, 64).equals(PUBLIC_KEY, ignoreCase = true)

    /** Открыт ли сейчас аккаунт разработчика (ядро запущено и его ключ совпал). */
    fun isMe(ctx: Context): Boolean {
        val tox = runCatching { (ctx.applicationContext as App).component.tox() }.getOrNull() ?: return false
        if (!runCatching { tox.started }.getOrDefault(false)) {
            // Профиль закрыт (или меняется на другой) - при следующем запуске проверим заново.
            me = null
            return false
        }
        me?.let { return it }
        val pk = runCatching { tox.publicKey.string() }.getOrNull() ?: return false
        return isDev(pk).also { me = it }
    }

    // ---------------- защита от «самодельных» сборок ----------------
    //
    // Исходники открыты: кто-то может собрать свою версию и вписать в неё свой ID - тогда
    // монеты у него «бесконечные», но ТОЛЬКО на его телефоне. Чтобы такие монеты не
    // расходились по чужим телефонам, ПОЛУЧАТЕЛЬ сам ограничивает входящие переводы:
    //  - от разработчика (ключ проверяет протокол, подделать нельзя) - без ограничений;
    //  - от остальных - не больше [PER_SENDER_DAY] монет в сутки от одного человека и
    //    [TOTAL_DAY] в сутки от всех вместе. Перевод сверх лимита автоматически
    //    отклоняется - честному отправителю монеты возвращаются.

    const val PER_SENDER_DAY = 500

    const val TOTAL_DAY = 2000

    private fun limits(ctx: Context) = ctx.applicationContext.getSharedPreferences("coin_limits", Context.MODE_PRIVATE)

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    /**
     * Можно ли принять перевод [n] монет от [fromPk]. true - перевод засчитан в дневной лимит
     * (монеты можно начислять); false - сверх лимита (перевод надо отклонить).
     */
    @Synchronized
    fun allowIncoming(ctx: Context, fromPk: String, n: Int): Boolean {
        if (n <= 0) return false
        if (isDev(fromPk)) return true
        val p = limits(ctx)
        val day = today()
        if (p.getLong("day", -1L) != day) p.edit().clear().putLong("day", day).commit()
        val key = "s_" + fromPk.take(64).uppercase()
        val fromSender = p.getInt(key, 0)
        val total = p.getInt("total", 0)
        if (fromSender + n > PER_SENDER_DAY || total + n > TOTAL_DAY) return false
        p.edit().putInt(key, fromSender + n).putInt("total", total + n).commit()
        return true
    }

    // ---------------- группы ----------------
    // В группах у каждого участника свой ключ (не ключ аккаунта). Разработчик сообщает
    // друзьям свои групповые ключи по личному защищённому каналу; чужой человек прислать
    // «ключи разработчика» не может - пакет принимается только от ключа разработчика.

    @Volatile
    private var groupKeys: Set<String>? = null

    private fun groupPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dev_group_keys", Context.MODE_PRIVATE)

    @Volatile
    private var designerKeys: Set<String>? = null

    /** Групповые ключи разработчика ([designer] = false) или дизайнера. */
    fun rememberGroupKeys(ctx: Context, keys: List<String>, designer: Boolean = false) {
        val clean = keys.map { it.trim().uppercase() }.filter { it.length in 32..128 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }.take(500)
        if (clean.isEmpty()) return
        val k = if (designer) "d" else "k"
        val all = (groupPrefs(ctx).getStringSet(k, emptySet()).orEmpty() + clean).toList().takeLast(1000).toSet()
        groupPrefs(ctx).edit().putStringSet(k, all).apply()
        if (designer) designerKeys = all else groupKeys = all
    }

    /** Сообщение в группе от дизайнера? */
    fun isDesignerInGroup(ctx: Context, senderKey: String?): Boolean {
        if (senderKey.isNullOrEmpty()) return false
        if (isDesigner(senderKey)) return true
        val set = designerKeys ?: groupPrefs(ctx).getStringSet("d", emptySet()).orEmpty().toSet().also { designerKeys = it }
        return senderKey.uppercase() in set
    }

    /** Сиреневое имя дизайнера в группах. */
    const val LILAC = 0xFFC084FC.toInt()

    /** Сообщение в группе от разработчика? [senderKey] - ключ участника в этой группе. */
    fun isDevInGroup(ctx: Context, senderKey: String?): Boolean {
        if (senderKey.isNullOrEmpty()) return false
        if (isDev(senderKey)) return true
        val set = groupKeys ?: groupPrefs(ctx).getStringSet("k", emptySet()).orEmpty().toSet().also { groupKeys = it }
        return senderKey.uppercase() in set
    }

    /** Профиль сменили / вышли - проверить заново. */
    fun reset() {
        me = null
    }

    /** Золотой значок «🛠 Разработчик S-messenger» для профиля. */
    fun badge(ctx: Context): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            setText(R.string.dev_badge)
            textSize = 13f
            setTextColor(0xFF4A2410.toInt())
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (5 * d).toInt(), (14 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFE9A8.toInt(), 0xFFF6C344.toInt(), 0xFFE8A317.toInt())).apply {
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.WHITE)
            }
            elevation = 3 * d
            contentDescription = ctx.getString(R.string.dev_badge)
        }
    }

    /**
     * Крупная плашка «🛠 Разработчик S-messenger» - ставится прямо НАД витриной подарков
     * (в своём профиле и в профиле разработчика у всех его контактов). Золото с бегущим
     * бликом; в режиме экономии блик стоит на месте.
     */
    fun bigBadge(ctx: Context, designer: Boolean = false, tester: Boolean = false): android.widget.LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return android.widget.LinearLayout(ctx).apply {
            tag = "dev_big_badge"
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GoldShine(d, designer)
            elevation = 6 * d
            addView(TextView(ctx).apply {
                setText(if (designer) R.string.designer_badge else if (tester) R.string.tester_badge else R.string.dev_badge)
                textSize = 20f
                // Узкие экраны (720 px, крупный шрифт): надпись в одну строку, без обрезки.
                maxLines = 2
                setAutoSizeTextTypeUniformWithConfigurationCompat(this, 12, 20)
                setTextColor(if (designer) 0xFF3B0764.toInt() else 0xFF3B1D08.toInt())
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, 0x66FFFFFF)
            })
            addView(TextView(ctx).apply {
                setText(if (designer) R.string.designer_badge_sub else if (tester) R.string.tester_badge_sub else R.string.dev_badge_sub)
                textSize = 13f
                setTextColor(if (designer) 0xCC4C1D95.toInt() else 0xCC4A2410.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
            contentDescription = ctx.getString(if (designer) R.string.designer_badge else if (tester) R.string.tester_badge else R.string.dev_badge)
            if (com.stoyko.smessenger.ui.Kinetics.enabled) {
                alpha = 0f
                scaleX = 0.92f
                scaleY = 0.92f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            }
        }
    }

    /**
     * Под золотой плашкой разработчика: кнопка канала разработчика (видна всем) и, в своём
     * профиле разработчика, кнопка «📊 Статистика приложения».
     */
    fun devExtras(ctx: Context, mine: Boolean): android.widget.LinearLayout {
        val d = ctx.resources.displayMetrics.density
        return android.widget.LinearLayout(ctx).apply {
            tag = "dev_extras"
            orientation = android.widget.LinearLayout.VERTICAL
            val lp = { top: Int ->
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (top * d).toInt() }
            }
            addView(com.stoyko.smessenger.ui.DevChannel.button(ctx), lp(0))
            if (mine) addView(com.stoyko.smessenger.stats.DevStats.button(ctx), lp(8))
        }
    }

    /** Золотой фон с бегущим бликом (сам перерисовывается ~30 раз/с, пока виден). */
    private class GoldShine(private val d: Float, private val lilac: Boolean = false) : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * d
            color = 0xFFFFF7D6.toInt()
        }
        private val r = android.graphics.RectF()
        private val start = android.os.SystemClock.uptimeMillis()

        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            r.set(b.left + d, b.top + d, b.right - d, b.bottom - d)
            val rad = 18 * d
            if (fill.shader == null || r.width().toInt() != lastW) {
                fill.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom,
                    if (lilac) intArrayOf(0xFFFAE8FF.toInt(), 0xFFE9A8FF.toInt(), 0xFFA78BFA.toInt(), 0xFFF5D0FE.toInt())
                    else intArrayOf(0xFFFFF1B8.toInt(), 0xFFF6C344.toInt(), 0xFFE8A317.toInt(), 0xFFFFD86B.toInt()), null,
                    android.graphics.Shader.TileMode.CLAMP)
                lastW = r.width().toInt()
            }
            c.drawRoundRect(r, rad, rad, fill)
            val saver = com.stoyko.smessenger.ui.EnergySaver.on
            val t = if (saver) 0.3f else ((android.os.SystemClock.uptimeMillis() - start) % 2600L) / 2600f
            val x = r.left - r.width() * 0.4f + t * r.width() * 1.8f
            shine.shader = android.graphics.LinearGradient(x - 60 * d, 0f, x + 60 * d, 0f,
                intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
            c.save()
            c.clipRect(r)
            c.drawRoundRect(r, rad, rad, shine)
            c.restore()
            c.drawRoundRect(r, rad, rad, stroke)
            if (!saver) scheduleSelf({ invalidateSelf() }, android.os.SystemClock.uptimeMillis() + 33)
        }

        private var lastW = -1

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun setAutoSizeTextTypeUniformWithConfigurationCompat(tv: TextView, minSp: Int, maxSp: Int) {
        runCatching {
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv, minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
    }

    /** Золотое имя разработчика в группах и каналах. */
    const val GOLD = 0xFFF6B800.toInt()
}
