// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Дурак» онлайн с контактами (2-4 игрока) - невидимыми пакетами ядра, как шахматы.
 *
 * Партию ведёт создатель (хозяин): он раздаёт карты, проверяет ходы и рассылает всем
 * состояние стола. Остальные отправляют хозяину свои ходы. У каждого игрока партия хранится
 * в `games/durak/<id>/SavePlayer.save` - вышли, пропала связь - продолжается с того же места.
 *
 * Ставки: все ставят одинаково (списывается при создании / принятии). Дурак теряет ставку,
 * остальные делят банк поровну. Ничья - каждому своя ставка. Кто-то отказался - партия
 * отменяется, ставки возвращаются.
 */
object DurakNet {
    private const val TAG = "DurakNet"
    private val main = Handler(Looper.getMainLooper())

    class Save(
        val id: String,
        /** Ключи игроков по местам (моё место - [mySeat]). Место 0 - хозяин. */
        val seats: List<String>,
        val names: MutableList<String>,
        val mySeat: Int,
        val stake: Int,
        /** waiting (ждём ответов), incoming (нас пригласили), playing, finished, declined, canceled. */
        var state: String,
        val accepted: MutableSet<Int> = HashSet(),
        var engine: JSONObject? = null,
        var version: Int = 0,
        val chat: MutableList<String> = ArrayList(),
        var paid: Boolean = false,
        var settled: Boolean = false,
        var updated: Long = System.currentTimeMillis(),
        /** Режим: подкидной / переводной / простой (DurakEngine.PODKIDNOY…). */
        val mode: Int = DurakEngine.PODKIDNOY,
        /** Места игроков, которые удалили у себя сохранение этой партии. */
        val gone: MutableSet<Int> = HashSet(),
    ) {
        val host get() = seats[0]
        val isHost get() = mySeat == 0
        val players get() = seats.size

        fun engineOrNull(): DurakEngine? = engine?.let { runCatching { DurakEngine.fromJson(it) }.getOrNull() }

        fun json(): JSONObject = JSONObject()
            .put("format", "SavePlayer.save/1").put("game", "durak")
            .put("id", id).put("seats", JSONArray(seats)).put("names", JSONArray(names)).put("me", mySeat)
            .put("stake", stake).put("state", state).put("acc", JSONArray(accepted.toList()))
            .put("engine", engine ?: JSONObject.NULL).put("v", version).put("chat", JSONArray(chat))
            .put("paid", paid).put("settled", settled).put("updated", updated).put("mode", mode)
            .put("gone", JSONArray(gone.toList()))

        companion object {
            fun from(o: JSONObject): Save {
                fun list(a: JSONArray?) = ArrayList<String>().apply { if (a != null) for (i in 0 until a.length()) add(a.getString(i)) }
                val acc = HashSet<Int>().apply { o.optJSONArray("acc")?.let { a -> for (i in 0 until a.length()) add(a.getInt(i)) } }
                val gone = HashSet<Int>().apply { o.optJSONArray("gone")?.let { a -> for (i in 0 until a.length()) add(a.getInt(i)) } }
                return Save(
                    o.getString("id"), list(o.getJSONArray("seats")), list(o.optJSONArray("names")), o.optInt("me"),
                    o.optInt("stake"), o.optString("state", "playing"), acc,
                    o.optJSONObject("engine"), o.optInt("v"), list(o.optJSONArray("chat")),
                    o.optBoolean("paid"), o.optBoolean("settled"), o.optLong("updated"),
                    o.optInt("mode", 0).coerceIn(0, DurakEngine.MODES - 1), gone,
                )
            }
        }
    }

    // ---------------- слушатели ----------------

    private val listeners = LinkedHashSet<(Save) -> Unit>()
    fun listen(l: (Save) -> Unit) = main.post { listeners += l }
    fun unlisten(l: (Save) -> Unit) = main.post { listeners -= l }
    private fun changed(s: Save) = main.post { listeners.toList().forEach { runCatching { it(s) } } }

    /** Приглашение пришло, пока приложение открыто. */
    @Volatile
    var onInvite: ((Save) -> Unit)? = null

    // ---------------- файлы ----------------

    private fun dir(ctx: Context, id: String) = File(File(ctx.filesDir, "games/durak"), id).apply { mkdirs() }

    private fun file(ctx: Context, id: String) = File(File(File(ctx.filesDir, "games/durak"), id), "SavePlayer.save")

    /** Удалённые в этом запуске партии: запоздалая запись не должна вернуть файл. */
    private val removed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Записать партию атомарно (`.tmp` -> основной файл, прежний - в `.bak`). */
    fun save(ctx: Context, s: Save) {
        if (s.id in removed) return
        s.updated = System.currentTimeMillis()
        // Отметки «удалил у себя» только добавляются - не теряем их при записи старой копии.
        load(ctx, s.id)?.gone?.let { s.gone += it }
        runCatching { dir(ctx, s.id); GameSaveSync.write(file(ctx, s.id), s.json().toString()) }.onFailure { Log.w(TAG, "save: $it") }
        changed(s)
    }

    /** Загрузить партию; основной файл испорчен - из `.tmp` / `.bak`. */
    fun load(ctx: Context, id: String): Save? = GameSaveSync.read(file(ctx, id)) { Save.from(it) }

    fun all(ctx: Context): List<Save> = (File(ctx.filesDir, "games/durak").listFiles() ?: emptyArray())
        .mapNotNull { d -> GameSaveSync.read(File(d, "SavePlayer.save")) { Save.from(it) } }
        .sortedByDescending { it.updated }

    fun delete(ctx: Context, id: String) {
        removed += id
        File(File(ctx.filesDir, "games/durak"), id).deleteRecursively()
    }

    private fun delPacket(id: String) = JSONObject().put("g", "durak").put("t", "del").put("id", id)

    /**
     * Игрок [seat] ушёл из незаконченной партии (удалил сохранение) - считается проигравшим:
     * он «дурак», остальные делят банк. Если он уже вышел без карт (выиграл), партия идёт
     * дальше без него; ушёл так хозяин - вести стол некому, остальным - ничья (ставки назад).
     * Одинаково считается на всех телефонах. true - партия закончилась.
     */
    private fun forfeit(s: Save, seat: Int): Boolean {
        val e = s.engineOrNull() ?: return false
        if (e.finished) return false
        val dk = when {
            seat !in e.out -> seat
            seat == 0 -> -2
            else -> return false
        }
        s.engine = e.toJson().put("dk", dk)
        s.state = "finished"
        return true
    }

    /**
     * Игрок удаляет сохранение из списка игр. До начала - это отказ (ставки возвращаются),
     * в незаконченной партии - сдача (см. [forfeit]). Остальным уходит пакет `del` (досылается,
     * пока они не в сети) - у них спросят, удалить ли партию и у себя.
     */
    fun deleteByUser(ctx: Context, s0: Save) {
        val s = load(ctx, s0.id) ?: s0
        val others = s.seats.filterIndexed { i, _ -> i != s.mySeat && i !in s.gone }
        val tell: List<String> = when (s.state) {
            "incoming", "waiting" -> if (s.isHost) {
                if (s.state == "waiting") {
                    s.state = "canceled"
                    refund(ctx, s)
                    save(ctx, s)
                    broadcast(ctx, s, JSONObject().put("t", "cancel").put("id", s.id).put("who", 0))
                }
                others
            } else {
                if (s.state == "incoming") {
                    decline(ctx, s)
                } else {
                    // Уже принял и заплатил - ставку возвращаем, хозяин отменит партию.
                    refund(ctx, s)
                    send(s.host, JSONObject().put("t", "dec").put("id", s.id))
                }
                listOf(s.host).filter { 0 !in s.gone }
            }
            "playing" -> {
                val e = s.engineOrNull()
                if (e != null && !e.finished && !s.settled) {
                    s.settled = true
                    // Уже вышел без карт - своя доля банка, иначе ставка проиграна.
                    if (s.mySeat in e.out) {
                        if (s.paid) Coins.add(ctx, s.stake * s.players / (s.players - 1))
                        Coins.addDurakWin(ctx)
                    }
                }
                if (s.isHost && forfeit(s, s.mySeat)) {
                    s.version++
                    broadcastState(ctx, s)
                }
                others
            }
            "finished" -> others
            else -> emptyList()
        }
        for (pk in tell) GameSaveSync.queue(ctx, "durak", s.id, pk, delPacket(s.id))
        delete(ctx, s.id)
        GameSaveSync.cancel(ctx, "durak", s.id)
        changed(s)
    }

    private fun myPk(ctx: Context): String = runCatching { (ctx.applicationContext as App).component.tox().publicKey.string() }.getOrDefault("")

    private fun send(pk: String, o: JSONObject) = GameNet.send(pk, o.put("g", "durak"))

    private fun broadcast(ctx: Context, s: Save, o: JSONObject) {
        val me = myPk(ctx)
        for (pk in s.seats) if (!pk.equals(me, true)) send(pk, JSONObject(o.toString()))
    }

    /** Имя игрока: как он записан у меня в контактах, иначе - имя от хозяина. */
    private fun contactName(ctx: Context, pk: String): String? = runCatching {
        kotlinx.coroutines.runBlocking { (ctx.applicationContext as App).component.contactRepository().getAll().first() }
            .firstOrNull { it.publicKey.equals(pk, true) }?.name?.ifBlank { null }
    }.getOrNull()

    // ---------------- действия ----------------

    /** Создать партию: я - хозяин, [opps] - (ключ, имя). null - не хватает монет. */
    fun create(ctx: Context, opps: List<Pair<String, String>>, stake: Int, mode: Int = DurakEngine.PODKIDNOY): Save? {
        if (opps.isEmpty() || opps.size > 3) return null
        if (stake > 0 && !Coins.spend(ctx, stake)) return null
        val me = myPk(ctx)
        if (me.isEmpty()) {
            if (stake > 0) Coins.add(ctx, stake)
            return null
        }
        val myName = runCatching { (ctx.applicationContext as App).component.tox().getName() }.getOrDefault("").ifBlank { "?" }
        val id = java.util.UUID.randomUUID().toString().take(12)
        val s = Save(id, listOf(me) + opps.map { it.first }, (listOf(myName) + opps.map { it.second }).toMutableList(), 0, stake, "waiting", mode = mode)
        s.accepted += 0
        s.paid = stake > 0
        save(ctx, s)
        val inv = JSONObject().put("t", "inv").put("id", id).put("seats", JSONArray(s.seats)).put("names", JSONArray(s.names)).put("stake", stake).put("mode", mode)
        for (pk in opps) send(pk.first, JSONObject(inv.toString()))
        for ((pk, _) in opps) GameInvite.post(ctx, pk, "durak", id, stake, outgoing = true)
        return s
    }

    /** Принять приглашение. false - не хватает монет. */
    fun accept(ctx: Context, s: Save): Boolean {
        if (s.state != "incoming") return true
        if (s.stake > 0 && !Coins.spend(ctx, s.stake)) return false
        s.paid = s.stake > 0
        s.state = "waiting"
        s.accepted += s.mySeat
        save(ctx, s)
        send(s.host, JSONObject().put("t", "acc").put("id", s.id))
        return true
    }

    /**
     * Отклонить приглашение: партия у меня удаляется (в лобби не попасть, уведомление убирается),
     * хозяину - «dec» сразу и «del» с повтором, пока он не подтвердит (был не в сети - узнает,
     * когда появится; «del» понимают и старые версии). У хозяина лобби отменяется с пометкой,
     * кто отказался, ставки возвращаются.
     */
    fun decline(ctx: Context, s: Save) {
        s.state = "declined"
        GameInvite.markDeclined(ctx, "durak", s.id)
        send(s.host, JSONObject().put("t", "dec").put("id", s.id))
        if (0 !in s.gone) GameSaveSync.queue(ctx, "durak", s.id, s.host, delPacket(s.id))
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(("d" + s.id).hashCode()) }
        delete(ctx, s.id)
        changed(s)
    }

    /** Мой ход: (a = atk|def|take|pass, c - карта, i - номер карты на столе). */
    fun act(ctx: Context, s: Save, a: String, c: Int = -1, i: Int = -1): Boolean {
        val e = s.engineOrNull() ?: return false
        val ok = apply(e, s.mySeat, a, c, i)
        if (!ok) return false
        s.engine = e.toJson()
        if (s.isHost) {
            s.version++
            afterChange(ctx, s, e)
            broadcastState(ctx, s)
        } else {
            // Сразу показываем у себя; хозяин проверит и пришлёт итоговое состояние.
            save(ctx, s)
            send(s.host, JSONObject().put("t", "act").put("id", s.id).put("a", a).put("c", c).put("i", i).put("v", s.version))
        }
        return true
    }

    private fun apply(e: DurakEngine, seat: Int, a: String, c: Int, i: Int): Boolean = when (a) {
        "atk" -> e.attack(seat, c)
        "def" -> e.defend(seat, i, c)
        "tr" -> e.transfer(seat, c)
        "take" -> e.take(seat)
        "pass" -> e.pass(seat)
        else -> false
    }

    fun chat(ctx: Context, s: Save, text: String) {
        val t = text.take(300)
        s.chat += "${s.mySeat}:$t"
        save(ctx, s)
        broadcast(ctx, s, JSONObject().put("t", "chat").put("id", s.id).put("x", t))
    }

    /** Попросить у хозяина свежее состояние стола (при открытии партии и когда он в сети). */
    /** Я открыл партию, чтобы продолжить: остальным - уведомление и карточка в чат. */
    fun here(ctx: Context, s: Save) {
        if (s.state != "playing") return
        broadcast(ctx, s, JSONObject().put("t", "here").put("id", s.id))
    }

    fun sync(ctx: Context, s: Save) {
        if (s.isHost) broadcastState(ctx, s) else send(s.host, JSONObject().put("t", "need").put("id", s.id).put("v", s.version))
    }

    private fun broadcastState(ctx: Context, s: Save) {
        save(ctx, s)
        val st = JSONObject().put("t", "st").put("id", s.id).put("v", s.version).put("state", s.state)
            .put("acc", JSONArray(s.accepted.toList())).put("s", s.engine ?: JSONObject.NULL)
        broadcast(ctx, s, st)
    }

    private fun afterChange(ctx: Context, s: Save, e: DurakEngine) {
        if (e.finished && s.state != "finished") s.state = "finished"
        if (s.state == "finished") settle(ctx, s, e)
    }

    /** Итог: раздать банк (один раз на каждом телефоне). */
    private fun settle(ctx: Context, s: Save, e: DurakEngine) {
        if (s.settled || !e.finished) return
        s.settled = true
        runCatching { com.stoyko.smessenger.games.stats.GameStats.durak(ctx, if (e.durak == -2) 0 else if (e.durak == s.mySeat) -1 else 1) }
        val pot = s.stake * s.players
        when {
            e.durak == -2 -> if (s.paid) Coins.add(ctx, s.stake)
            e.durak != s.mySeat -> {
                if (s.paid) Coins.add(ctx, pot / (s.players - 1))
                Coins.addDurakWin(ctx)
            }
        }
    }

    private fun refund(ctx: Context, s: Save) {
        if (s.paid && !s.settled) {
            s.settled = true
            Coins.add(ctx, s.stake)
        }
    }

    // ---------------- приём ----------------

    fun onPacket(ctx: Context, pk: String, o: JSONObject) {
        val id = o.optString("id").takeIf { it.matches(Regex("[0-9a-f-]{6,40}")) } ?: return
        val s = load(ctx, id)
        val seat = s?.seats?.indexOfFirst { it.equals(pk, true) } ?: -1
        when (o.optString("t")) {
            "here" -> {
                if (s != null && s.state == "playing" && seat >= 0) {
                    GameInvite.joined(ctx, pk, "durak", id, s.stake, s.names.getOrElse(seat) { "" })
                }
            }
            "inv" -> {
                if (s != null || GameInvite.wasDeclined(ctx, "durak", id)) return
                val seats = ArrayList<String>().apply { o.optJSONArray("seats")?.let { a -> for (i in 0 until a.length()) add(a.getString(i)) } }
                val names = ArrayList<String>().apply { o.optJSONArray("names")?.let { a -> for (i in 0 until a.length()) add(a.getString(i).take(40)) } }
                if (seats.size !in 2..4 || !seats[0].equals(pk, true)) return
                val me = myPk(ctx)
                val my = seats.indexOfFirst { it.equals(me, true) }
                if (my <= 0) return
                while (names.size < seats.size) names += "?"
                for (i in seats.indices) if (i != my) contactName(ctx, seats[i])?.let { names[i] = it }
                val ns = Save(id, seats, names, my, o.optInt("stake").coerceIn(0, if (com.stoyko.smessenger.chat.DevAccount.isDev(pk)) 100_000 else GameNet.MAX_STAKE), "incoming", mode = o.optInt("mode", 0).coerceIn(0, DurakEngine.MODES - 1))
                save(ctx, ns)
                GameInvite.post(ctx, pk, "durak", id, ns.stake, outgoing = false)
                onInvite?.invoke(ns)
                notifyInvite(ctx, ns)
            }
            "acc" -> {
                if (s == null || !s.isHost || seat <= 0 || s.state != "waiting") return
                s.accepted += seat
                if (s.accepted.size == s.players) {
                    val e = DurakEngine(s.players, s.mode)
                    e.deal(System.nanoTime() xor id.hashCode().toLong())
                    s.engine = e.toJson()
                    s.state = "playing"
                    s.version = 1
                    broadcastState(ctx, s)
                } else {
                    broadcastState(ctx, s)
                }
            }
            "dec" -> {
                if (s == null || !s.isHost || seat <= 0) return
                if (s.state == "waiting") {
                    s.state = "canceled"
                    refund(ctx, s)
                    s.chat += "sys:" + ctx.getString(R.string.durak_declined_by, s.names.getOrElse(seat) { "?" })
                    save(ctx, s)
                    broadcast(ctx, s, JSONObject().put("t", "cancel").put("id", id).put("who", seat))
                }
            }
            "cancel" -> {
                if (s == null || seat != 0) return
                if (s.state == "playing" || s.state == "finished") return
                s.state = "canceled"
                refund(ctx, s)
                s.chat += "sys:" + ctx.getString(R.string.durak_declined_by, s.names.getOrElse(o.optInt("who")) { "?" })
                save(ctx, s)
            }
            "act" -> {
                if (s == null || !s.isHost || seat <= 0 || s.state != "playing") return
                val e = s.engineOrNull() ?: return
                if (apply(e, seat, o.optString("a"), o.optInt("c", -1), o.optInt("i", -1))) {
                    s.engine = e.toJson()
                    s.version++
                    afterChange(ctx, s, e)
                }
                // Ход неверный (опоздал) - всё равно шлём состояние, чтобы у игрока стол исправился.
                broadcastState(ctx, s)
            }
            "need" -> {
                if (s == null || !s.isHost || seat <= 0) return
                send(pk, JSONObject().put("t", "st").put("id", id).put("v", s.version).put("state", s.state)
                    .put("acc", JSONArray(s.accepted.toList())).put("s", s.engine ?: JSONObject.NULL))
            }
            "st" -> {
                if (s == null || seat != 0 || s.isHost) return
                val v = o.optInt("v")
                // Своё «опережающее» состояние заменяем хозяйским, даже если номер тот же.
                if (v < s.version) return
                if (s.state == "declined" || s.state == "canceled") return
                s.version = v
                o.optJSONObject("s")?.let { s.engine = it }
                val st = o.optString("state")
                if (st == "playing" || st == "finished" || st == "waiting") s.state = if (s.state == "incoming" && st == "waiting") "incoming" else st
                s.accepted.clear()
                o.optJSONArray("acc")?.let { a -> for (i in 0 until a.length()) s.accepted += a.getInt(i) }
                s.engineOrNull()?.let { e -> if (e.finished) { s.state = "finished"; settle(ctx, s, e) } }
                save(ctx, s)
            }
            // Участник удалил сохранение партии.
            "del" -> {
                if (s != null && (seat < 0 || seat == s.mySeat)) return
                send(pk, JSONObject().put("t", "delok").put("id", id))
                if (s == null || seat in s.gone) return
                s.gone += seat
                val who = s.names.getOrElse(seat) { "?" }
                when (s.state) {
                    "incoming", "waiting" -> {
                        if (seat == 0) {
                            // Хозяин отменил партию до начала - ставка назад, хранить нечего.
                            refund(ctx, s)
                            s.state = "canceled"
                            delete(ctx, id)
                            changed(s)
                        } else if (s.isHost && s.state == "waiting") {
                            s.state = "canceled"
                            refund(ctx, s)
                            s.chat += "sys:" + ctx.getString(R.string.durak_declined_by, who)
                            save(ctx, s)
                            broadcast(ctx, s, JSONObject().put("t", "cancel").put("id", id).put("who", seat))
                        } else {
                            save(ctx, s)
                        }
                        return
                    }
                    "playing" -> {
                        s.chat += "sys:" + ctx.getString(R.string.gamesave_left, who)
                        if (!forfeit(s, seat)) {
                            save(ctx, s)
                            return
                        }
                        s.engineOrNull()?.let { settle(ctx, s, it) }
                        if (s.isHost) {
                            s.version++
                            broadcastState(ctx, s)
                        } else {
                            save(ctx, s)
                        }
                    }
                    "finished" -> save(ctx, s)
                    else -> {
                        save(ctx, s)
                        return
                    }
                }
                GameSaveSync.ask(ctx, "durak", id, who)
            }
            "delok" -> GameSaveSync.acked(ctx, "durak", id, pk)
            "chat" -> {
                if (s == null || seat < 0) return
                s.chat += "$seat:" + o.optString("x").take(300)
                if (s.chat.size > 200) s.chat.removeAt(0)
                save(ctx, s)
            }
        }
    }

    /** Друг снова в сети: сверяем незаконченные партии. */
    fun onPeerOnline(ctx: Context, pk: String) {
        for (s in all(ctx)) {
            if (s.state != "playing" && s.state != "waiting") continue
            if (s.isHost && s.seats.any { it.equals(pk, true) }) {
                send(pk, JSONObject().put("t", "st").put("id", s.id).put("v", s.version).put("state", s.state)
                    .put("acc", JSONArray(s.accepted.toList())).put("s", s.engine ?: JSONObject.NULL))
            } else if (!s.isHost && s.host.equals(pk, true)) {
                sync(ctx, s)
            }
        }
    }

    private fun notifyInvite(ctx: Context, s: Save) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.games_title), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val open = android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(GameNet.ACTION_OPEN_GAME).putExtra(GameNet.EXTRA_GAME, "durak:" + s.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(ctx, ("d" + s.id).hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.durak_invite_title))
                .setContentText(ctx.getString(R.string.durak_invite_text, s.names.getOrElse(0) { "?" }, s.players, s.stake))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(("d" + s.id).hashCode(), n)
        }
    }
}
