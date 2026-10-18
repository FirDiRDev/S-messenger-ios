// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

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
import com.stoyko.smessenger.chat.Cards
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.ui.Btn
import org.json.JSONObject

/**
 * Приглашение в игру карточкой прямо в чате с соперником: «Принять» / «Отклонить»,
 * потом - «Открыть партию» и итог. Карточка - только у себя в чате (по сети уходит
 * невидимый пакет игры), поэтому на старых версиях ничего лишнего не появляется.
 */
object GameInvite {
    private const val PREFIX = "⁣🎮I:"

    fun isCard(text: String) = text.startsWith(PREFIX)

    /** [joined] - не приглашение, а «соперник вернулся в партию» (кнопка «Присоединиться»). */
    class Info(val game: String, val id: String, val stake: Int, val joined: String? = null)

    fun parse(text: String): Info? = runCatching {
        val o = JSONObject(text.removePrefix(PREFIX))
        Info(o.getString("g"), o.getString("id"), o.optInt("s"), o.optString("j").takeIf { o.has("j") })
    }.getOrNull()

    /**
     * Соперник открыл партию, чтобы продолжить: карточка в чат с ним и уведомление.
     * Не чаще раза в 10 минут на партию - чтобы не засыпать чат.
     */
    fun joined(ctx: Context, pk: String, game: String, id: String, stake: Int, name: String) {
        val p = ctx.applicationContext.getSharedPreferences("game_joined", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(id, 0L) < 10 * 60_000L) return
        p.edit().putLong(id, now).apply()
        val app = ctx.applicationContext as? App ?: return
        val text = PREFIX + JSONObject().put("g", game).put("id", id).put("s", stake).put("j", name.take(40)).toString()
        Thread {
            runCatching {
                app.component.messageRepository().add(
                    Message(pk, text, Sender.Received, MessageType.Normal, Int.MIN_VALUE, System.currentTimeMillis()),
                )
            }
        }.start()
        notifyJoined(ctx, game, id, name)
    }

    private fun notifyJoined(ctx: Context, game: String, id: String, name: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.games_title), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val open = android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(GameNet.ACTION_OPEN_GAME)
                .putExtra(GameNet.EXTRA_GAME, if (game == "durak") "durak:$id" else if (game == BJ) "$BJ:$id" else id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(ctx, ("j$id").hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val gameName = ctx.getString(if (game == "durak") R.string.durak_title else if (game == BJ) R.string.bjack_title_online else R.string.chess_title)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.game_joined_title, gameName))
                .setContentText(ctx.getString(R.string.game_joined_text, name.ifBlank { "?" }))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(("j$id").hashCode(), n)
        }
    }

    @Volatile
    private var inited = false

    /** Карточки в открытых чатах обновляются, когда меняется состояние партии. */
    fun init() {
        if (inited) return
        inited = true
        GameNet.listen { Cards.notifyChanged() }
        DurakNet.listen { Cards.notifyChanged() }
        com.stoyko.smessenger.games.blackjack.BjNet.listen { Cards.notifyChanged() }
    }

    /** Онлайн-блэкджек (стол с дилером): своя ветка в карточке, логика - в blackjack/BjNet. */
    private const val BJ = com.stoyko.smessenger.games.blackjack.BjNet.GAME

    /** Положить карточку в свой чат с [pk]. */
    fun post(ctx: Context, pk: String, game: String, id: String, stake: Int, outgoing: Boolean) {
        val app = ctx.applicationContext as? App ?: return
        val text = PREFIX + JSONObject().put("g", game).put("id", id).put("s", stake).toString()
        Thread {
            runCatching {
                // Время не 0: иначе сообщение считалось бы неотправленным и ушло бы по сети.
                app.component.messageRepository().add(
                    Message(pk, text, if (outgoing) Sender.Sent else Sender.Received, MessageType.Normal, Int.MIN_VALUE, System.currentTimeMillis()),
                )
            }
        }.start()
    }

    fun preview(ctx: Context, text: String): String? = parse(text)?.let {
        "🎮 " + ctx.getString(if (it.game == "durak") R.string.durak_title else if (it.game == BJ) R.string.bjack_title_online else R.string.chess_title)
    }

    // ---------------- отклонённые приглашения ----------------

    private fun declinedPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("game_declined", Context.MODE_PRIVATE)

    /**
     * Я отклонил приглашение [game]/[id]: сохранение партии удаляется, а эта отметка не даёт
     * повторному пакету приглашения вернуть его и показывает на карточке «Вы отклонили».
     * Любая игра (шахматы, «Дурак», блэкджек…) отмечает отказ здесь.
     */
    fun markDeclined(ctx: Context, game: String, id: String) {
        runCatching {
            val p = declinedPrefs(ctx)
            val now = System.currentTimeMillis()
            val e = p.edit().putLong("$game|$id", now)
            // Старше двух месяцев - забываем (карточка такого приглашения уже давно «устарела»).
            for ((k, v) in p.all) if (v is Long && now - v > 60L * 24 * 3600 * 1000) e.remove(k)
            e.apply()
        }
    }

    fun wasDeclined(ctx: Context, game: String, id: String): Boolean =
        runCatching { declinedPrefs(ctx).contains("$game|$id") }.getOrDefault(false)

    /** Подпись состояния и доступные действия. */
    private class View2(val status: String, val incoming: Boolean, val canOpen: Boolean)

    private fun stateOf(ctx: Context, i: Info): View2? {
        if (i.game == BJ) {
            val (st, incoming, canOpen) = com.stoyko.smessenger.games.blackjack.BjOnline.cardState(ctx, i.id) ?: return null
            return View2(st, incoming, canOpen)
        }
        // Отклонил сам - ни «Принять», ни «Открыть»: в лобби/на доску с этой карточки не попасть.
        if (wasDeclined(ctx, i.game, i.id)) return View2(ctx.getString(R.string.gamesb_invite_declined_me), false, false)
        if (i.game == "durak") {
            val s = DurakNet.load(ctx, i.id) ?: return null
            val e = s.engineOrNull()
            val st = when (s.state) {
                "incoming" -> ctx.getString(R.string.game_card_incoming)
                "waiting" -> ctx.getString(R.string.durak_waiting_players, s.accepted.size, s.players)
                "declined" -> ctx.getString(R.string.chess_state_declined)
                "canceled" -> ctx.getString(R.string.durak_canceled)
                "finished" -> when {
                    e == null -> ctx.getString(R.string.chess_state_draw)
                    e.durak == -2 -> ctx.getString(R.string.chess_state_draw)
                    e.durak == s.mySeat -> ctx.getString(R.string.durak_you_durak_short)
                    else -> ctx.getString(R.string.chess_state_won)
                }
                else -> ctx.getString(R.string.chess_state_playing)
            }
            return View2(st, s.state == "incoming", s.state == "playing" || s.state == "finished" || s.state == "waiting")
        }
        val s = GameNet.load(ctx, i.id) ?: return null
        val st = when {
            s.state == "incoming" -> ctx.getString(R.string.game_card_incoming)
            s.state == "invited" -> ctx.getString(R.string.chess_state_invited)
            s.state == "declined" -> ctx.getString(R.string.chess_state_declined)
            s.result == 1 -> ctx.getString(R.string.chess_state_won)
            s.result == -1 -> ctx.getString(R.string.chess_state_lost)
            s.result == 0 -> ctx.getString(R.string.chess_state_draw)
            else -> ctx.getString(R.string.chess_state_playing)
        }
        return View2(st, s.state == "incoming", s.state != "declined")
    }

    /** Подпись для кэша карточки в списке (пересобирать, только когда что-то поменялось). */
    fun signature(ctx: Context, text: String): String = text + ":" + (parse(text)?.let { stateOf(ctx, it)?.let { v -> v.status + v.incoming } } ?: "")

    fun chatCard(ctx: Context, text: String, received: Boolean, onDone: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val info = parse(text)
        val durak = info?.game == "durak"
        val bj = info?.game == BJ
        val v = info?.let { stateOf(ctx, it) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (bj) intArrayOf(0xFF22C55E.toInt(), 0xFF14532D.toInt()) else if (durak) intArrayOf(0xFFFB923C.toInt(), 0xFF9A3412.toInt()) else intArrayOf(0xFF8B7CFF.toInt(), 0xFF312E81.toInt()),
            ).apply { cornerRadius = 20 * d }
            elevation = 3 * d
            layoutParams = FrameLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(ctx).apply {
            this.text = info?.joined?.let { j -> ctx.getString(R.string.game_joined_text, j.ifBlank { "?" }) } ?: ctx.getString(if (received) R.string.game_card_in else R.string.game_card_out)
            textSize = 12f
            setTextColor(0xDDFFFFFF.toInt())
            paint.isFakeBoldText = true
        })
        root.addView(TextView(ctx).apply {
            this.text = (if (bj) "♠ " + ctx.getString(R.string.bjack_title_online) else if (durak) "🃏 " + ctx.getString(R.string.durak_title) else "♞ " + ctx.getString(R.string.chess_title)) +
                if ((info?.stake ?: 0) > 0) "  ·  🪙 ${info!!.stake}" else ""
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        root.addView(TextView(ctx).apply {
            this.text = v?.status ?: ctx.getString(R.string.card_expired)
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(8))
        })
        if (info != null && v != null) {
            if (info.joined != null && v.canOpen) {
                // Соперник вернулся - присоединиться и продолжить.
                root.addView(
                    Btn.primary(ctx, ctx.getString(R.string.game_joined_btn), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { open(ctx, info) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
                )
            } else if (v.incoming && received) {
                root.addView(
                    Btn.row(
                        ctx,
                        listOf(
                            Btn.tonal(ctx, ctx.getString(R.string.chess_decline), Color.WHITE) {
                                decline(ctx, info)
                                onDone()
                            },
                            Btn.primary(ctx, ctx.getString(R.string.chess_accept), intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) {
                                accept(ctx, info)
                                onDone()
                            },
                        ),
                        height = 42,
                    ),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            } else if (v.canOpen) {
                root.addView(
                    Btn.primary(ctx, ctx.getString(R.string.game_card_open), intArrayOf(0xFFFFFFFF.toInt(), 0xFFE0E7FF.toInt())) { open(ctx, info) }
                        .apply { setTextColor(0xFF312E81.toInt()) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
                )
            }
        }
        return root
    }

    private fun accept(ctx: Context, i: Info) {
        if (i.game == BJ) {
            com.stoyko.smessenger.games.blackjack.BjOnline.acceptFromCard(ctx, i.id)
            return
        }
        if (i.game == "durak") {
            val s = DurakNet.load(ctx, i.id) ?: return
            if (DurakNet.accept(ctx, s)) {
                DurakGame.showOnline(ctx, s.id)
            } else {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, s.stake, Coins.balance(ctx)), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val s = GameNet.load(ctx, i.id) ?: return
        if (GameNet.accept(ctx, s)) {
            ChessGame.show(ctx, s.id)
        } else {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, s.stake, Coins.balance(ctx)), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun decline(ctx: Context, i: Info) {
        if (i.game == BJ) {
            com.stoyko.smessenger.games.blackjack.BjOnline.declineFromCard(ctx, i.id)
            markDeclined(ctx, i.game, i.id)
            return
        }
        if (i.game == "durak") DurakNet.load(ctx, i.id)?.let { DurakNet.decline(ctx, it) } else GameNet.load(ctx, i.id)?.let { GameNet.decline(ctx, it) }
        // Сохранения уже нет (удалили раньше) - карточка всё равно больше не предлагает войти.
        markDeclined(ctx, i.game, i.id)
    }

    fun open(ctx: Context, i: Info) {
        if (i.game == BJ) {
            // Отклонённое приглашение стол не открывает.
            if (wasDeclined(ctx, i.game, i.id)) return
            com.stoyko.smessenger.games.blackjack.BjNet.load(ctx, i.id)?.takeIf { it.state != "declined" }
                ?.let { com.stoyko.smessenger.games.blackjack.BjOnline.open(ctx, it) }
            return
        }
        if (wasDeclined(ctx, i.game, i.id)) return
        if (i.game == "durak") DurakGame.showOnline(ctx, i.id) else ChessGame.show(ctx, i.id)
    }
}
