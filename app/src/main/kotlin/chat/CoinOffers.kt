// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.games.CoinSound
import com.stoyko.smessenger.games.Match3Game
import com.stoyko.smessenger.games.SnakeGame
import com.stoyko.smessenger.ui.Btn

/**
 * Выгодные предложения за монеты (меняются каждый день):
 *  - 🐍 «Скин дня» - один из ещё не купленных скинов Змейки со скидкой 40%;
 *  - 🎨 «Набор скинов» - три скина по цене двух;
 *  - 💎 «Набор бустеров» - по 2 каждого бустера «Три в ряд» со скидкой 30%;
 *  - 🎁 «Подарок дня» - легендарный подарок себе со скидкой 35%.
 * Каждое предложение покупается один раз в день. Цена считается от обычной - без обмана.
 */
object CoinOffers {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("coin_offers", Context.MODE_PRIVATE)

    private fun today() = System.currentTimeMillis() / 86_400_000L

    private fun bought(ctx: Context, id: String) = prefs(ctx).getLong("b_$id", -1L) == today()

    private fun markBought(ctx: Context, id: String) = prefs(ctx).edit().putLong("b_$id", today()).apply()

    class Offer(val id: String, val icon: String, val title: String, val desc: String, val full: Int, val price: Int, val apply: () -> Unit)

    /** Случайный, но одинаковый весь день выбор (по дню и «соли»). */
    private fun <T> pick(list: List<T>, salt: Int): T? {
        if (list.isEmpty()) return null
        var x = today() * 2654435761L + salt * 40503L
        x = x xor (x ushr 13)
        return list[((x and 0x7FFFFFFF) % list.size).toInt()]
    }

    fun offers(ctx: Context): List<Offer> {
        val out = ArrayList<Offer>()
        val locked = SnakeGame.SKINS.filter { it.price > 0 && !it.exclusive && !SnakeGame.isOwned(ctx, it) }
        pick(locked, 1)?.let { sk ->
            out += Offer(
                "skin_day", "🐍", ctx.getString(R.string.offer_skin_day, ctx.getString(sk.name)), ctx.getString(R.string.offer_skin_day_d),
                sk.price, sk.price * 60 / 100,
            ) { SnakeGame.unlockSkin(ctx, sk.id) }
        }
        val rest = locked.filter { s -> out.none { it.id == "skin_day" } || s != pick(locked, 1) }
        if (rest.size >= 3) {
            val start = ((today() % rest.size).toInt())
            val three = (0 until 3).map { rest[(start + it * 5) % rest.size] }.distinct()
            if (three.size == 3) {
                val full = three.sumOf { it.price }
                val cheapest = three.minOf { it.price }
                out += Offer(
                    "skin_pack", "🎨", ctx.getString(R.string.offer_skin_pack),
                    three.joinToString(", ") { ctx.getString(it.name) } + " - " + ctx.getString(R.string.offer_skin_pack_d),
                    full, full - cheapest,
                ) { three.forEach { SnakeGame.unlockSkin(ctx, it.id) } }
            }
        }
        val boostFull = Match3Game.BOOST_PRICE.sum() * 2
        out += Offer("boosts", "💎", ctx.getString(R.string.offer_boosts), ctx.getString(R.string.offer_boosts_d), boostFull, boostFull * 70 / 100) {
            for (b in Match3Game.BOOST_PRICE.indices) Match3Game.addBoost(ctx, b, 2)
        }
        val legend = GiftCatalog.all.filter { it.rarity == GiftCatalog.Rarity.LEGENDARY && !GiftCatalog.isExclusive(it) }
        pick(legend, 7)?.let { g ->
            val full = Coins.price(g)
            out += Offer("gift_day", "🎁", ctx.getString(R.string.offer_gift_day), ctx.getString(R.string.offer_gift_day_d), full, full * 65 / 100) {
                Gifts.giveSelf(ctx, g, "")
            }
        }
        return out
    }

    /**
     * Карточки предложений в [box]; [changed] - после покупки. Все карточки одной высоты:
     * заголовок - одна строка (уменьшается, если длинный), описание - ровно две строки
     * (длинное обрезается «…»), цена - одна строка внизу; кнопка - одного размера по центру.
     */
    fun build(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        for (o in offers(ctx)) {
            val done = bought(ctx, o.id)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(104)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x33FFFFFF, 0x14FFFFFF)).apply {
                    cornerRadius = 18 * d
                    setStroke(dp(1), 0x55FDE047)
                }
                alpha = if (done) 0.55f else 1f
            }
            card.addView(TextView(ctx).apply {
                text = o.icon
                textSize = 28f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x22FDE047)
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
            }
            texts.addView(TextView(ctx).apply {
                text = o.title
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, 15, 1, TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
            texts.addView(TextView(ctx).apply {
                text = o.desc
                textSize = 12.5f
                setTextColor(0xCCFFFFFF.toInt())
                // Ровно две строки: короткое описание не делает карточку ниже, длинное - выше.
                setLines(2)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            })
            texts.addView(TextView(ctx).apply {
                // Старая цена зачёркнута, новая - крупно, и сколько экономите.
                val saving = (100 - o.price * 100 / o.full.coerceAtLeast(1))
                text = android.text.SpannableStringBuilder().apply {
                    val s0 = length
                    append("🪙 ${o.full}")
                    setSpan(android.text.style.StrikethroughSpan(), s0, length, 0)
                    append("   ")
                    val s1 = length
                    append("🪙 ${o.price}")
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), s1, length, 0)
                    append("   −$saving%")
                }
                textSize = 13f
                setTextColor(0xFFFDE68A.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                paintFlags = paintFlags or Paint.ANTI_ALIAS_FLAG
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(Btn.nice(ctx, ctx.getString(if (done) R.string.offer_done else R.string.offer_buy), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
                if (bought(ctx, o.id)) return@nice
                if (!Coins.spend(ctx, o.price)) {
                    Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, o.price, Coins.balance(ctx)), Toast.LENGTH_LONG).show()
                    return@nice
                }
                runCatching { o.apply() }
                markBought(ctx, o.id)
                CoinSound.jackpot(4)
                Toast.makeText(ctx, ctx.getString(R.string.offer_bought, o.title), Toast.LENGTH_SHORT).show()
                changed()
            }.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(96), dp(44)))
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }
}
