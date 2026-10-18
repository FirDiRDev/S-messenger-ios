// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.stoyko.smessenger.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Надёжные сохранения онлайн-партий (`SavePlayer.save`) и «удалить у обоих».
 *
 * Файлы пишутся атомарно: сначала во временный `.tmp` (с записью на диск), прежний вариант
 * становится `.bak`, затем `.tmp` переименовывается в основной файл. Приложение убили посреди
 * записи, файл испорчен - при загрузке берётся `.tmp` (если он целый) или `.bak`.
 *
 * Удаление: кто-то удалил сохранение партии - остальным участникам уходит пакет `del`.
 * Пакет повторяется, пока участник не ответит `delok` (соперник мог быть не в сети).
 * Получатель видит уведомление (и окно, если приложение открыто): «Удалить и у вас?».
 */
object GameSaveSync {
    private const val TAG = "GameSaveSync"
    private const val OUTBOX = "game_del_out"

    /** Неподтверждённые удаления досылаются не дольше месяца. */
    private const val KEEP_MS = 30L * 24 * 3600 * 1000

    const val ACTION_DELETE = "com.stoyko.smessenger.GAME_SAVE_DELETE"
    const val ACTION_KEEP = "com.stoyko.smessenger.GAME_SAVE_KEEP"
    const val EXTRA_KIND = "game_kind"
    const val EXTRA_ID = "game_id"

    // ---------------- атомарные файлы ----------------

    private fun sibling(f: File, ext: String) = File(f.parentFile, f.name + ext)

    /** Записать [text] в [f] атомарно, прежний вариант остаётся в `.bak`. */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = sibling(f, ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (f.exists()) {
            val bak = sibling(f, ".bak")
            bak.delete()
            if (!f.renameTo(bak)) runCatching { f.copyTo(bak, overwrite = true) }
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    /** Прочитать сохранение: основной файл, иначе целый `.tmp`, иначе `.bak`. */
    fun <T> read(f: File, parse: (JSONObject) -> T): T? {
        for (c in listOf(f, sibling(f, ".tmp"), sibling(f, ".bak"))) {
            if (!c.isFile) continue
            val v = runCatching { parse(JSONObject(c.readText(Charsets.UTF_8))) }.getOrNull()
            if (v != null) {
                if (c !== f) Log.w(TAG, "restored ${f.parentFile?.name} from ${c.name}")
                return v
            }
        }
        return null
    }

    // ---------------- досылка удалений ----------------

    private fun key(game: String, id: String, pk: String) = "$game|$id|${pk.uppercase()}"

    /** Отправить участнику [pk] пакет удаления и повторять, пока он не подтвердит. */
    fun queue(ctx: Context, game: String, id: String, pk: String, packet: JSONObject) {
        ctx.getSharedPreferences(OUTBOX, Context.MODE_PRIVATE).edit()
            .putString(key(game, id, pk), JSONObject().put("ts", System.currentTimeMillis()).put("p", packet).toString())
            .commit()
        GameNet.send(pk, packet)
    }

    /** Участник подтвердил, что получил удаление. */
    fun acked(ctx: Context, game: String, id: String, pk: String) {
        ctx.getSharedPreferences(OUTBOX, Context.MODE_PRIVATE).edit().remove(key(game, id, pk)).apply()
    }

    /** Друг снова в сети - досылаем ему неподтверждённые удаления. */
    fun resend(ctx: Context, pk: String) {
        val p = ctx.getSharedPreferences(OUTBOX, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val drop = ArrayList<String>()
        for ((k, v) in p.all) {
            if (!k.endsWith("|" + pk.uppercase())) continue
            val o = runCatching { JSONObject(v as String) }.getOrNull()
            val packet = o?.optJSONObject("p")
            if (o == null || packet == null || now - o.optLong("ts") > KEEP_MS) {
                drop += k
                continue
            }
            GameNet.send(pk, packet)
        }
        if (drop.isNotEmpty()) p.edit().apply { drop.forEach { remove(it) } }.apply()
    }

    // ---------------- «соперник удалил - удалить и у вас?» ----------------

    private val dialogs = HashMap<String, android.app.Dialog>()

    private fun notifId(game: String, id: String) = ("gdel$game$id").hashCode()

    private fun gameTitle(ctx: Context, game: String) = ctx.getString(
        when (game) {
            "durak" -> R.string.durak_title
            com.stoyko.smessenger.games.blackjack.BjNet.GAME -> R.string.bjack_title_online
            else -> R.string.chess_title
        },
    )

    /** Участник [who] удалил сохранение партии [id]: уведомление + окно, если приложение открыто. */
    fun ask(ctx: Context, game: String, id: String, who: String) {
        val text = ctx.getString(R.string.gamesave_deleted_text, who.ifBlank { "?" }, gameTitle(ctx, game))
        notify(ctx, game, id, text)
        val act = com.stoyko.smessenger.update.Updater.foreground?.get() ?: return
        if (act.isFinishing || act.isDestroyed) return
        val k = "$game|$id"
        if (dialogs[k]?.isShowing == true) return
        runCatching {
            val d = AlertDialog.Builder(act)
                .setTitle(R.string.gamesave_deleted_title)
                .setMessage(text)
                .setPositiveButton(R.string.gamesave_delete) { _, _ ->
                    deleteLocal(act.applicationContext, game, id)
                    android.widget.Toast.makeText(act, R.string.gamesave_deleted_done, android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.gamesave_keep) { _, _ -> cancel(act, game, id) }
                .create()
            d.setOnDismissListener { if (dialogs[k] === d) dialogs.remove(k) }
            dialogs[k] = d
            com.stoyko.smessenger.ui.DialogTracker.add(d).show()
        }.onFailure { Log.w(TAG, "dialog: $it") }
    }

    /** Убрать уведомление и окно (ответили в одном из мест). */
    fun cancel(ctx: Context, game: String, id: String) {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(game, id)) }
        dialogs.remove("$game|$id")?.let { d -> runCatching { if (d.isShowing) d.dismiss() } }
    }

    /** Удалить сохранение у себя (ответ «Удалить» в окне или в уведомлении). */
    fun deleteLocal(ctx: Context, game: String, id: String) {
        cancel(ctx, game, id)
        runCatching {
            if (game == "durak") {
                DurakNet.load(ctx, id)?.let { DurakNet.deleteByUser(ctx, it) }
            } else if (game == com.stoyko.smessenger.games.blackjack.BjNet.GAME) {
                com.stoyko.smessenger.games.blackjack.BjNet.load(ctx, id)?.let { com.stoyko.smessenger.games.blackjack.BjNet.deleteByUser(ctx, it) }
            } else {
                GameNet.load(ctx, id)?.let { GameNet.deleteByUser(ctx, it) }
            }
        }.onFailure { Log.w(TAG, "delete: $it") }
    }

    private fun notify(ctx: Context, game: String, id: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(ch, ctx.getString(R.string.games_title), NotificationManager.IMPORTANCE_HIGH))
            }
            val nid = notifId(game, id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun action(a: String, code: Int) = PendingIntent.getBroadcast(
                ctx, nid + code,
                Intent(ctx, GameSaveReceiver::class.java).setAction(a).putExtra(EXTRA_KIND, game).putExtra(EXTRA_ID, id),
                flags,
            )
            val open = Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(GameNet.ACTION_OPEN_GAME).putExtra(GameNet.EXTRA_GAME, if (game == "chess") id else "$game:$id")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val n = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.gamesave_deleted_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(ctx, nid, open, flags))
                .addAction(0, ctx.getString(R.string.gamesave_delete), action(ACTION_DELETE, 1))
                .addAction(0, ctx.getString(R.string.gamesave_keep), action(ACTION_KEEP, 2))
                .setAutoCancel(true)
                .build()
            nm.notify(nid, n)
        }
    }
}

/** Кнопки «Удалить» / «Оставить» в уведомлении «соперник удалил сохранение». */
class GameSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val game = intent.getStringExtra(GameSaveSync.EXTRA_KIND) ?: return
        val id = intent.getStringExtra(GameSaveSync.EXTRA_ID)?.takeIf { it.matches(Regex("[0-9a-f-]{6,40}")) } ?: return
        val ctx = context.applicationContext
        when (intent.action) {
            GameSaveSync.ACTION_DELETE -> GameSaveSync.deleteLocal(ctx, game, id)
            else -> GameSaveSync.cancel(ctx, game, id)
        }
    }
}
