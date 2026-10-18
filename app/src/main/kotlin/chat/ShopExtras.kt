// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.R
import com.stoyko.smessenger.games.CoinSound
import com.stoyko.smessenger.games.GameSfx
import com.stoyko.smessenger.games.SnakeGame
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.DialogTracker
import com.stoyko.smessenger.ui.EnergySaver
import com.stoyko.smessenger.ui.Kinetics
import kotlin.math.sin

/**
 * Витрина магазина монет:
 *  - ⚡ «Эксклюзив»: редкие скины Змейки и подарки ✦, которых нет в обычном выборе. Появляются
 *    случайно на 3 часа (таймер до конца акции), потом пропадают; стоят дорого - на них копят
 *    или докупают монеты;
 *  - каталог скинов Змейки с живым предпросмотром и покупкой;
 *  - подарки себе в коллекцию (с предпросмотром).
 */
object ShopExtras {
    private const val WINDOW = 3 * 3600_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("shop_limited", Context.MODE_PRIVATE)

    class Limited(val window: Long, val endsAt: Long, val skin: SnakeGame.Skin?, val gifts: List<Pair<GiftCatalog.Gift, Int>>)

    private fun rnd(w: Long) = java.util.Random(w * 7919L + 13L)

    /** Есть ли акция в окне [w] (примерно в 7 окнах из 10). */
    private fun active(w: Long) = rnd(w).nextInt(10) < 7

    /** Текущая акция или null (тогда - когда следующая). */
    fun current(now: Long = System.currentTimeMillis()): Limited? {
        val w = now / WINDOW
        if (!active(w)) return null
        val r = rnd(w)
        r.nextInt(10)
        val skins = SnakeGame.SKINS.filter { it.exclusive }
        val skin = skins.getOrNull(r.nextInt(skins.size.coerceAtLeast(1)))
        val ex = GiftCatalog.exclusives
        val picked = LinkedHashSet<GiftCatalog.Gift>()
        repeat(6) { if (ex.isNotEmpty() && picked.size < 2) picked += ex[r.nextInt(ex.size)] }
        val gifts = picked.map { it to (1200 + r.nextInt(14) * 100) * 2 }
        return Limited(w, (w + 1) * WINDOW, skin, gifts)
    }

    /** Когда начнётся следующая акция. */
    fun nextStart(now: Long = System.currentTimeMillis()): Long {
        var w = now / WINDOW + 1
        repeat(20) {
            if (active(w)) return w * WINDOW
            w++
        }
        return w * WINDOW
    }

    private fun boughtKey(l: Limited, id: String) = "b_${l.window}_$id"

    fun bought(ctx: Context, l: Limited, id: String) = prefs(ctx).getBoolean(boughtKey(l, id), false)

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ---------------- витрина «Эксклюзив» ----------------

    /** Карточка акции с таймером (сама обновляется каждую секунду, пока видна). */
    fun buildLimited(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val frame = GlowFrame(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        frame.addView(col)
        val title = TextView(ctx).apply {
            textSize = 17f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setShadowLayer(8f, 0f, 0f, 0xAAF59E0B.toInt())
        }
        val timer = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(8))
        }
        col.addView(title)
        col.addView(timer)
        // Плитки ряда - одной высоты (MATCH_PARENT по высоте внутри ряда WRAP_CONTENT).
        val items = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(items)
        })
        val lim = current()
        if (lim == null) {
            title.setText(R.string.lim_none_title)
            col.addView(TextView(ctx).apply {
                setText(R.string.lim_none_text)
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
            })
        } else {
            title.setText(R.string.lim_title)
            // Все плитки акции - одного размера: картинка, название ровно в две строки
            // (длинное обрезается «…»), кнопка с ценой прижата к низу.
            fun tile(art: View, name: String, price: Int, owned: Boolean, buy: () -> Unit, preview: () -> Unit) {
                val t = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * d
                        setColor(0x33000000)
                        setStroke(dp(1), 0x66FDE047)
                    }
                    setOnClickListener { preview() }
                }
                t.addView(art, LinearLayout.LayoutParams(dp(110), dp(84)))
                t.addView(TextView(ctx).apply {
                    text = name
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    setLines(2)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
                t.addView(View(ctx), LinearLayout.LayoutParams(dp(1), 0, 1f))
                t.addView(Btn.nice(ctx, if (owned) ctx.getString(R.string.offer_done) else "🪙 $price", intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
                    if (!owned) buy()
                }.apply {
                    alpha = if (owned) 0.55f else 1f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(110), dp(40)).apply { topMargin = dp(6) })
                items.addView(t, LinearLayout.LayoutParams(dp(126), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
            }
            lim.skin?.let { sk ->
                val owned = SnakeGame.isOwned(ctx, sk)
                val buy = { buySkin(ctx, sk, sk.price) { prefs(ctx).edit().putBoolean(boughtKey(lim, sk.id), true).apply(); changed() } }
                tile(SnakeGame.skinPreview(ctx, sk).apply { background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(0xFF1E1B4B.toInt()) } },
                    ctx.getString(sk.name), sk.price, owned, buy) { previewSkin(ctx, sk, sk.price, owned, buy) }
            }
            for ((g, price) in lim.gifts) {
                val owned = bought(ctx, lim, "g${g.id}")
                val buy = { buyGift(ctx, g, price) { prefs(ctx).edit().putBoolean(boughtKey(lim, "g${g.id}"), true).apply(); changed() } }
                tile(GiftArt(ctx, g, playEmoji = false), g.shortName(), price, owned, buy) { previewGift(ctx, g, price, owned, buy) }
            }
        }
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // Таймер: каждую секунду; акция закончилась - витрина пересобирается.
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!frame.isAttachedToWindow) return
                val now = System.currentTimeMillis()
                if (lim != null) {
                    val left = lim.endsAt - now
                    if (left <= 0) {
                        changed()
                        return
                    }
                    timer.text = ctx.getString(R.string.lim_left, fmt(left))
                    // Последний час - таймер красный и мигает.
                    timer.setTextColor(if (left < 3600_000L && (now / 500) % 2 == 0L) 0xFFFCA5A5.toInt() else Color.WHITE)
                } else {
                    timer.text = ctx.getString(R.string.lim_next, fmt(nextStart(now) - now))
                }
                h.postDelayed(this, 1000)
            }
        }
        frame.post(tick)
    }

    // ---------------- каталог ----------------

    /** Все обычные скины Змейки с живым предпросмотром + подарки себе. */
    fun buildCatalog(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        box.addView(TextView(ctx).apply {
            setText(R.string.cat_skins)
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(4), dp(18), dp(4), dp(6))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val skins = SnakeGame.SKINS.filter { !it.exclusive && it.price > 0 }.sortedBy { if (SnakeGame.isOwned(ctx, it)) 1 else 0 }
        for (sk in skins) {
            val owned = SnakeGame.isOwned(ctx, sk)
            val buy = { buySkin(ctx, sk, sk.price) { changed() } }
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0x22FFFFFF)
                }
                setOnClickListener { previewSkin(ctx, sk, sk.price, owned, buy) }
            }
            t.addView(SnakeGame.skinPreview(ctx, sk).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(0xFFAAD751.toInt())
                }
            }, LinearLayout.LayoutParams(dp(96), dp(56)))
            t.addView(TextView(ctx).apply {
                text = ctx.getString(sk.name)
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            t.addView(TextView(ctx).apply {
                text = if (owned) "✓" else "🪙 ${sk.price}"
                textSize = 12.5f
                setTextColor(if (owned) 0xFF86EFAC.toInt() else 0xFFFDE68A.toInt())
                gravity = Gravity.CENTER
            })
            row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        box.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        buildRunnerSkins(ctx, box, changed)
        box.addView(Btn.nice(ctx, ctx.getString(R.string.cat_gifts), intArrayOf(0xFFFF6B9D.toInt(), 0xFF7B6CF6.toInt())) {
            Gifts.pick(ctx, ctx.getString(R.string.gift_from_me), self = true) { gift, note ->
                Gifts.giveSelf(ctx, gift, note)
                CoinSound.jackpot(4)
                Toast.makeText(ctx, ctx.getString(R.string.offer_bought, gift.shortName()), Toast.LENGTH_SHORT).show()
                changed()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })
    }

    // ---------------- облики бегуна (4.10.0) ----------------

    /** Магазин открыт из «Бега по рельсам» - сразу к обликам бегуна. */
    const val RUNNER_FOCUS = "runner_skins"

    /**
     * «🏃 Облики бегуна» в магазине: облик дня со скидкой (каждый день новый), ⚡ эксклюзивы,
     * которых нет в самой игре, и все остальные облики - с живым предпросмотром и покупкой.
     */
    fun buildRunnerSkins(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val skins = com.stoyko.smessenger.games.runner.RunnerSkins.all
        val deal = com.stoyko.smessenger.games.runner.RunnerSkins.dealToday(ctx)
        fun owned(i: Int) = com.stoyko.smessenger.games.runner.RunnerSkins.isOwned(ctx, i)
        fun price(i: Int) = com.stoyko.smessenger.games.runner.RunnerSkins.priceNow(ctx, i)
        fun buy(i: Int) {
            val sk = skins[i]
            if (owned(i)) return
            val cost = price(i)
            if (!com.stoyko.smessenger.games.runner.RunnerSkins.buy(ctx, i, fromShop = true)) {
                // Легендарный - сразу предлагаем докупить монеты за рубли.
                if (sk.legendary) return com.stoyko.smessenger.games.runner.RunnerShop.offerCoins(ctx, cost - Coins.balance(ctx), ctx.getString(sk.name))
                return notEnough(ctx, cost)
            }
            CoinSound.jackpot(6)
            GameSfx.play(ctx, GameSfx.S.RAINBOW)
            Toast.makeText(ctx, ctx.getString(R.string.offer_bought, ctx.getString(sk.name)), Toast.LENGTH_SHORT).show()
            changed()
        }
        fun preview(i: Int) {
            val sk = skins[i]
            val art = com.stoyko.smessenger.games.runner.RunnerSkinArt(ctx, sk).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1E3A8A.toInt(), 0xFF0F172A.toInt())).apply { cornerRadius = 18 * d }
            }
            val sub = buildString {
                append(ctx.getString(sk.desc))
                com.stoyko.smessenger.games.runner.Perks.text(sk.perk).takeIf { it.isNotEmpty() }?.let {
                    append("\n").append(com.stoyko.smessenger.ai.t("Способность: ", "Ability: ")).append(it)
                }
                if (i == deal) append("\n🔥 ").append(com.stoyko.smessenger.ai.t("Акция дня: −", "Deal of the day: −")).append(com.stoyko.smessenger.games.runner.RunnerSkins.DEAL_OFF).append("%")
            }
            previewDialog(ctx, art, ctx.getString(sk.name), sub, price(i), owned(i)) { buy(i) }
        }
        fun tile(i: Int, wide: Boolean): View {
            val sk = skins[i]
            val isOwned = owned(i)
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(if (sk.legendary) 0x40FACC15 else if (sk.shopOnly) 0x33E879F9 else if (i == deal) 0x33F97316 else 0x22FFFFFF)
                    if (sk.legendary) setStroke(dp(2), 0xFFFACC15.toInt())
                    else if (sk.shopOnly || i == deal) setStroke(dp(1), if (sk.shopOnly) 0xFFE879F9.toInt() else 0xFFF97316.toInt())
                }
                setOnClickListener { preview(i) }
            }
            Kinetics.squish(t, 0.95f)
            val artW = if (wide) 120 else 96
            t.addView(com.stoyko.smessenger.games.runner.RunnerSkinArt(ctx, sk).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1E3A8A.toInt(), 0xFF0F172A.toInt())).apply { cornerRadius = 10 * d }
            }, LinearLayout.LayoutParams(dp(artW), dp(if (wide) 120 else 96)))
            t.addView(TextView(ctx).apply {
                text = ctx.getString(sk.name)
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(artW), ViewGroup.LayoutParams.WRAP_CONTENT))
            t.addView(TextView(ctx).apply {
                text = when {
                    isOwned -> "✓"
                    i == deal -> android.text.SpannableStringBuilder().apply {
                        val old = "${sk.price}"
                        append(old)
                        setSpan(android.text.style.StrikethroughSpan(), 0, old.length, 0)
                        append("  🪙 ${price(i)}")
                    }
                    else -> "🪙 ${sk.price}"
                }
                textSize = 12.5f
                setTextColor(if (isOwned) 0xFF86EFAC.toInt() else 0xFFFDE68A.toInt())
                gravity = Gravity.CENTER
            })
            return t
        }
        fun header(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(4), dp(18), dp(4), dp(6))
        }
        box.addView(header(com.stoyko.smessenger.ai.t("🏃 Облики бегуна", "🏃 Runner outfits")).apply { tag = RUNNER_FOCUS })
        // Акция дня - крупно, с таймером до смены.
        if (deal >= 0) {
            val left = com.stoyko.smessenger.games.runner.RunnerSkins.dealLeftMs()
            val hrs = left / 3_600_000L
            val mins = (left / 60_000L) % 60
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tile(deal, wide = true))
                addView(TextView(ctx).apply {
                    text = com.stoyko.smessenger.ai.t(
                        "🔥 Акция дня\n−${com.stoyko.smessenger.games.runner.RunnerSkins.DEAL_OFF}% на «${ctx.getString(skins[deal].name)}»\nещё ${hrs} ч ${mins} мин",
                        "🔥 Deal of the day\n−${com.stoyko.smessenger.games.runner.RunnerSkins.DEAL_OFF}% on “${ctx.getString(skins[deal].name)}”\n${hrs} h ${mins} min left",
                    )
                    textSize = 14f
                    setTextColor(0xFFFED7AA.toInt())
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        // 👑 Легендарные и 🦸 персонажи (4.12.0).
        fun row(title: String, color: Int, ids: List<Int>, wide: Boolean) {
            if (ids.isEmpty()) return
            box.addView(TextView(ctx).apply {
                text = title
                textSize = 13.5f
                setTextColor(color)
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ids.sortedBy { if (owned(it)) 1 else 0 }.forEach { i ->
                r.addView(tile(i, wide = wide), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        }
        row(
            com.stoyko.smessenger.ai.t("👑 Легендарные - аура и сильнейшие способности (монеты или ₽)", "👑 Legendary - aura and the strongest abilities"),
            0xFFFDE047.toInt(),
            skins.indices.filter { skins[it].legendary },
            wide = true,
        )
        row(
            com.stoyko.smessenger.ai.t("🦸 Персонажи - своё тело и способность в забеге", "🦸 Characters - own body and in-run ability"),
            0xFFBAE6FD.toInt(),
            skins.indices.filter { skins[it].hero && !skins[it].legendary && !skins[it].shopOnly },
            wide = false,
        )
        // ⚡ Эксклюзивы - только здесь.
        val excl = skins.indices.filter { skins[it].shopOnly }
        if (excl.isNotEmpty()) {
            box.addView(TextView(ctx).apply {
                text = com.stoyko.smessenger.ai.t("⚡ Эксклюзивы - есть только в магазине", "⚡ Exclusives - only in the shop")
                textSize = 13.5f
                setTextColor(0xFFF5D0FE.toInt())
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            excl.sortedBy { if (owned(it)) 1 else 0 }.forEach { i ->
                row.addView(tile(i, wide = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        // Все облики игры.
        val rest = skins.indices.filter { it != 0 && !skins[it].shopOnly && !skins[it].hero && skins[it].price > 0 }.sortedBy { if (owned(it)) 1 else 0 }
        if (rest.isNotEmpty()) {
            box.addView(TextView(ctx).apply {
                text = com.stoyko.smessenger.ai.t("Все облики", "All outfits")
                textSize = 13.5f
                setTextColor(0xCCFFFFFF.toInt())
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rest.forEach { i ->
                row.addView(tile(i, wide = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
    }

    // ---------------- покупка и предпросмотр ----------------

    private fun notEnough(ctx: Context, price: Int) {
        GameSfx.play(ctx, GameSfx.S.BAD)
        Toast.makeText(ctx, ctx.getString(R.string.lim_not_enough, price, Coins.balance(ctx)), Toast.LENGTH_LONG).show()
    }

    private fun buySkin(ctx: Context, sk: SnakeGame.Skin, price: Int, done: () -> Unit) {
        if (SnakeGame.isOwned(ctx, sk)) return
        if (!Coins.spend(ctx, price)) return notEnough(ctx, price)
        SnakeGame.unlockSkin(ctx, sk.id)
        CoinSound.jackpot(6)
        GameSfx.play(ctx, GameSfx.S.RAINBOW)
        Toast.makeText(ctx, ctx.getString(R.string.offer_bought, ctx.getString(sk.name)), Toast.LENGTH_SHORT).show()
        done()
    }

    private fun buyGift(ctx: Context, g: GiftCatalog.Gift, price: Int, done: () -> Unit) {
        if (!Coins.spend(ctx, price)) return notEnough(ctx, price)
        Gifts.giveSelf(ctx, g, "")
        CoinSound.jackpot(6)
        GameSfx.play(ctx, GameSfx.S.RAINBOW)
        Toast.makeText(ctx, ctx.getString(R.string.offer_bought, g.shortName()), Toast.LENGTH_SHORT).show()
        done()
    }

    private fun previewDialog(ctx: Context, art: View, title: String, sub: String, price: Int, owned: Boolean, buy: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        GameSfx.play(ctx, GameSfx.S.STAR, 1f, 0.7f)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        box.addView(art, LinearLayout.LayoutParams(dp(220), dp(180)))
        box.addView(TextView(ctx).apply {
            text = title
            textSize = 19f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(TextView(ctx).apply {
            text = sub
            textSize = 13.5f
            gravity = Gravity.CENTER
            alpha = 0.8f
        })
        lateinit var dlg: android.app.Dialog
        box.addView(Btn.nice(ctx, if (owned) ctx.getString(R.string.offer_done) else ctx.getString(R.string.lim_buy_for, price), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
            if (!owned) {
                buy()
                runCatching { dlg.dismiss() }
            }
        }.apply { alpha = if (owned) 0.55f else 1f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.shop_balance, Coins.balance(ctx))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        dlg = DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setView(box)
                .setNegativeButton(R.string.close, null)
                .create(),
        )
        dlg.show()
        if (Kinetics.enabled) {
            art.scaleX = 0.6f
            art.scaleY = 0.6f
            art.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    fun previewSkin(ctx: Context, sk: SnakeGame.Skin, price: Int, owned: Boolean, buy: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val art = SnakeGame.skinPreview(ctx, sk).apply {
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(if (sk.exclusive) 0xFF1E1B4B.toInt() else 0xFFAAD751.toInt())
            }
        }
        previewDialog(ctx, art, (if (sk.exclusive) "✦ " else "🐍 ") + ctx.getString(sk.name), ctx.getString(if (sk.exclusive) R.string.lim_skin_sub else R.string.cat_skin_sub), price, owned, buy)
    }

    fun previewGift(ctx: Context, g: GiftCatalog.Gift, price: Int, owned: Boolean, buy: () -> Unit) {
        previewDialog(ctx, GiftArt(ctx, g), g.name(), GiftCatalog.rarityName(g.rarity) + " · " + ctx.getString(R.string.lim_gift_sub), price, owned, buy)
    }

    // ---------------- оформление ----------------

    /** Рамка акции: по краю бежит радужно-золотое сияние. */
    @SuppressLint("ViewConstructor")
    private class GlowFrame(ctx: Context) : FrameLayout(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var sweep: SweepGradient? = null
        private val m = android.graphics.Matrix()
        private val start = SystemClock.uptimeMillis()

        init {
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(0xFFFDE047.toInt(), 0xFFF472B6.toInt(), 0xFF818CF8.toInt(), 0xFF22D3EE.toInt(), 0xFFFDE047.toInt()), null)
            bg.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF3B0764.toInt(), 0xFF1E1B4B.toInt(), 0xFF7C2D12.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            r.set(2 * d, 2 * d, width - 2 * d, height - 2 * d)
            c.drawRoundRect(r, 22 * d, 22 * d, bg)
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            sweep?.let {
                m.setRotate(t * 90f, width / 2f, height / 2f)
                it.setLocalMatrix(m)
                p.shader = it
            }
            p.strokeWidth = (2.5f + 0.8f * sin(t * 3f)) * d
            c.drawRoundRect(r, 22 * d, 22 * d, p)
            if (Kinetics.enabled && isShown) postInvalidateDelayed(if (EnergySaver.on) 120 else 40)
        }
    }

    /**
     * Живой значок пака: руки покачиваются и «встряхивают» монеты, мешок подпрыгивает,
     * сундук «дышит» и сверкает, корона переливается и сияет; вокруг - искры.
     */
    @SuppressLint("ViewConstructor")
    class PackIcon(ctx: Context, private val emoji: String, private val style: Int) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val start = SystemClock.uptimeMillis() + style * 170L

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val live = Kinetics.enabled
            val t = if (live) (SystemClock.uptimeMillis() - start) / 1000f else 0f
            tp.textSize = minOf(w, h) * 0.62f
            c.save()
            when (style) {
                // Руки: покачиваются, будто подбрасывают монеты.
                0, 1 -> {
                    c.rotate(sin(t * 3.2f) * 9f, w / 2, h * 0.7f)
                    c.translate(0f, -kotlin.math.abs(sin(t * 3.2f)) * h * 0.05f)
                }
                // Мешок: подпрыгивает и чуть сплющивается при «приземлении».
                2 -> {
                    val b = kotlin.math.abs(sin(t * 2.6f))
                    c.translate(0f, -b * h * 0.08f)
                    c.scale(1f + (1 - b) * 0.06f, 1f - (1 - b) * 0.06f, w / 2, h * 0.9f)
                }
                // Сундук: «дышит», крышка будто приоткрывается.
                3 -> {
                    val s = 1f + 0.06f * sin(t * 2.2f)
                    c.scale(s, s, w / 2, h / 2)
                    c.rotate(sin(t * 1.5f) * 3f, w / 2, h / 2)
                }
                // Корона: парит и сияет.
                else -> {
                    c.translate(0f, sin(t * 2f) * h * 0.04f)
                    sp.shader = android.graphics.RadialGradient(w / 2, h / 2, w * 0.5f, 0x88FDE047.toInt(), 0x00FDE047, android.graphics.Shader.TileMode.CLAMP)
                    sp.alpha = (150 + 100 * sin(t * 3f)).toInt().coerceIn(0, 255)
                    c.drawCircle(w / 2, h / 2, w * 0.5f, sp)
                    sp.shader = null
                }
            }
            c.drawText(emoji, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2, tp)
            c.restore()
            // Искры-монетки разлетаются вверх.
            if (live) {
                for (k in 0 until 4) {
                    val ph = ((t * 0.8f + k * 0.25f) % 1f)
                    val x = w * (0.2f + 0.6f * ((k * 37 + (t * 0.8f + k * 0.25f).toInt() * 13) % 10) / 10f)
                    val y = h * (0.85f - ph * 0.8f)
                    sp.color = if (k % 2 == 0) 0xFFFDE047.toInt() else Color.WHITE
                    sp.alpha = (255 * (1 - ph)).toInt()
                    c.drawCircle(x, y, (1.4f + style * 0.25f) * d * (1 - ph * 0.5f), sp)
                }
                if (isShown) postInvalidateDelayed(if (EnergySaver.on) 100 else 33)
            }
        }
    }
}
