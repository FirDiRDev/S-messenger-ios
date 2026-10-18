// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import org.json.JSONObject

/**
 * Перевод монет карточкой в чате (как подарок): отправитель дарит N монет, у друга в чате
 * появляется карточка с живой монетой и кнопками «Принять» / «Отклонить». Принял - звон
 * монет, монеты слетаются в кошелёк; отклонил - монеты возвращаются отправителю.
 *
 * Сообщение уходит обычным путём чата: если друг не в сети, оно дождётся его и уйдёт само.
 */
object CoinGift {
    private const val PREFIX = "⁣🪙T:"
    private const val STATUS = "⁣🪙S:"
    private const val PREFS = "coin_gifts"

    fun isGift(text: String) = text.startsWith(PREFIX)
    fun isStatus(text: String) = text.startsWith(STATUS)

    class Offer(val uid: String, val amount: Int, val note: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(ctx: Context, uid: String): String? = prefs(ctx).getString("s_$uid", null)
    private fun setState(ctx: Context, uid: String, v: String) = prefs(ctx).edit().putString("s_$uid", v).apply()

    fun parse(text: String): Offer? = runCatching {
        val o = JSONObject(text.removePrefix(PREFIX))
        Offer(o.getString("u").take(32), o.getInt("n").coerceIn(1, 1_000_000), o.optString("m").take(120))
    }.getOrNull()

    /** Подарить [n] монет: списываются сразу, сообщение-карточка уходит в чат. false - не хватает монет. */
    /** Больше за один перевод нельзя (разработчику - можно, см. [DevAccount]). */
    const val MAX_PER_TRANSFER = 500

    fun send(ctx: Context, pk: String, n: Int, note: String = ""): Boolean {
        if (n <= 0) return false
        if (n > MAX_PER_TRANSFER && !DevAccount.isMe(ctx)) return false
        if (!Coins.spend(ctx, n)) return false
        val uid = System.currentTimeMillis().toString(36) + (1000..9999).random()
        prefs(ctx).edit().putString("o_$uid", "$pk|$n").apply()
        val text = PREFIX + JSONObject().put("u", uid).put("n", n).put("m", note.take(120)).toString()
        runCatching {
            (ctx.applicationContext as App).component.chatManager().sendMessage(PublicKey(pk), text, MessageType.Normal)
        }
        return true
    }

    /** Ответ на перевод в чате. */
    fun respond(ctx: Context, fromPk: String, text: String, accept: Boolean) {
        val o = parse(text) ?: return
        if (state(ctx, o.uid) != null) return
        // Лимит входящих переводов (защита от монет из самодельных сборок, см. DevAccount):
        // сверх лимита перевод отклоняется, и монеты возвращаются отправителю.
        var ok = accept
        if (ok && !DevAccount.allowIncoming(ctx, fromPk, o.amount)) {
            ok = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.coin_limit_reached, DevAccount.PER_SENDER_DAY, DevAccount.TOTAL_DAY), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        setState(ctx, o.uid, if (ok) "ok" else "no")
        if (ok) Coins.add(ctx, o.amount)
        runCatching {
            (ctx.applicationContext as App).component.chatManager()
                .sendMessage(PublicKey(fromPk), STATUS + JSONObject().put("u", o.uid).put("s", if (ok) "ok" else "no"), MessageType.Normal)
        }
        Cards.notifyChanged()
    }

    /** Друг ответил на мой перевод: отклонил - монеты возвращаются. */
    fun onStatus(ctx: Context, text: String) {
        runCatching {
            val o = JSONObject(text.removePrefix(STATUS))
            val uid = o.getString("u").take(32)
            if (state(ctx, uid) != null) return@runCatching
            val ok = o.optString("s") == "ok"
            setState(ctx, uid, if (ok) "ok" else "no")
            val out = prefs(ctx).getString("o_$uid", null)
            if (!ok && out != null) {
                out.substringAfter('|').toIntOrNull()?.let { Coins.add(ctx, it) }
            }
            prefs(ctx).edit().remove("o_$uid").apply()
        }
        Cards.notifyChanged()
    }

    fun preview(ctx: Context, text: String): String? = parse(text)?.let { ctx.getString(R.string.coin_gift_notification, it.amount) }

    /** Карточка в чате. */
    fun chatCard(ctx: Context, text: String, received: Boolean, onAnswer: (Boolean, View) -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val o = parse(text)
        val st = o?.let { state(ctx, it.uid) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF59E0B.toInt(), 0xFF7C2D12.toInt()))
                .apply { cornerRadius = 22 * d }
            elevation = 3 * d
            layoutParams = FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(ctx).apply {
            this.text = ctx.getString(if (received) R.string.coin_gift_in else R.string.coin_gift_out)
            textSize = 12f
            setTextColor(0xEEFFFFFF.toInt())
            paint.isFakeBoldText = true
        })
        val coin = CoinView(ctx).apply { tag = "coin_gift_coin" }
        root.addView(coin, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        root.addView(TextView(ctx).apply {
            this.text = ctx.resources.getQuantityString(R.plurals.coins_count, o?.amount ?: 0, o?.amount ?: 0)
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setShadowLayer(3 * d, 0f, d, 0x66000000)
        })
        if (!o?.note.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                this.text = "«" + o!!.note + "»"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(4), dp(6), dp(4), 0)
            })
        }
        if (received && st == null && o != null) {
            root.addView(
                Btn.row(
                    ctx,
                    listOf(
                        Btn.tonal(ctx, ctx.getString(R.string.card_decline), Color.WHITE) { onAnswer(false, coin) },
                        Btn.primary(ctx, ctx.getString(R.string.gift_accept), intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) { onAnswer(true, coin) },
                    ),
                    height = 42,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
            )
        } else {
            root.addView(TextView(ctx).apply {
                this.text = ctx.getString(
                    when (st) {
                        "ok" -> if (received) R.string.coin_gift_accepted_me else R.string.coin_gift_accepted_peer
                        "no" -> if (received) R.string.coin_gift_declined_me else R.string.coin_gift_declined_peer
                        else -> R.string.gift_waiting
                    },
                )
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (st == "ok") 0xFFBBF7D0.toInt() else 0xEEFFFFFF.toInt())
                setPadding(0, dp(10), 0, 0)
            })
        }
        return root
    }

    /**
     * Монеты принимаются: металлический звон, монеты вылетают из карточки и слетаются
     * в кошелёк вверху экрана, счёт растёт по одной.
     */
    fun celebrate(host: ViewGroup, from: View, amount: Int) {
        val ctx = host.context
        com.stoyko.smessenger.games.CoinSound.jackpot(amount)
        val root = (host.rootView as? ViewGroup) ?: host
        val frame = FrameLayout(ctx)
        root.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val before = Coins.balance(ctx) - amount
        // Кошелёк: пилюля с монетой и счётом сверху по центру.
        val wallet = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = 30 * d
                setColor(0xEE1E1B4B.toInt())
            }
            elevation = 12 * d
        }
        val wCoin = CoinView(ctx)
        wallet.addView(wCoin, LinearLayout.LayoutParams(dp(30), dp(30)))
        val count = TextView(ctx).apply {
            this.text = before.coerceAtLeast(0).toString()
            textSize = 19f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        wallet.addView(count)
        frame.addView(wallet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(72) })
        wallet.alpha = 0f
        wallet.translationY = -dp(30).toFloat()
        wallet.animate().alpha(1f).translationY(0f).setDuration(220).start()
        // Откуда летят - запоминаем сразу: карточка сейчас перерисуется.
        val a = IntArray(2)
        from.getLocationInWindow(a)
        val fromW = from.width
        val fromH = from.height
        frame.post {
            val b = IntArray(2)
            val f = IntArray(2)
            wCoin.getLocationInWindow(b)
            frame.getLocationInWindow(f)
            val size = dp(30)
            val n = amount.coerceIn(4, 14)
            var landed = 0
            repeat(n) { i ->
                val c = CoinView(ctx).apply { period = 0.5f }
                frame.addView(c, FrameLayout.LayoutParams(size, size))
                val sx = a[0] - f[0] + fromW / 2f - size / 2
                val sy = a[1] - f[1] + fromH / 2f - size / 2
                c.x = sx
                c.y = sy
                val ang = Math.random() * 2 * Math.PI
                val r = (50 + Math.random() * 70) * d
                c.animate().x((sx + kotlin.math.cos(ang) * r).toFloat()).y((sy + kotlin.math.sin(ang) * r).toFloat())
                    .rotation(360f).setDuration(300).setStartDelay(i * 45L).withEndAction {
                        c.animate().x(b[0] - f[0].toFloat()).y(b[1] - f[1].toFloat()).scaleX(0.7f).scaleY(0.7f)
                            .setDuration(430).withEndAction {
                                frame.removeView(c)
                                landed++
                                wCoin.pulse()
                                count.text = (before + amount * landed / n).coerceAtLeast(0).toString()
                                if (landed == n) {
                                    count.text = Coins.balance(ctx).toString()
                                    wallet.animate().alpha(0f).setStartDelay(900).setDuration(300)
                                        .withEndAction { root.removeView(frame) }.start()
                                }
                            }.start()
                    }.start()
            }
        }
    }
}
