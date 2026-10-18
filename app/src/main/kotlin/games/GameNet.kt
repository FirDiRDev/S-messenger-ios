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
import com.stoyko.smessenger.core.vo.PublicKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Онлайн-игры с контактами (шахматы): приглашения, ходы, чат соперников, ставки монет.
 *
 * Всё идёт «невидимыми» пакетами ядра (как проверка шифрования) - напрямую между телефонами,
 * сквозным шифрованием сети, без серверов; в переписку ничего не попадает.
 *
 * Сохранение: у ОБОИХ игроков партия хранится в файле `SavePlayer.save` (папка игры в памяти
 * приложения): ходы, ставка, цвет, чат. Вышли, закрыли приложение, пропала связь - партия
 * продолжается с того же места; когда соперник снова в сети, телефоны сверяют ходы и
 * досылают недостающие.
 *
 * Ставки: оба ставят одинаковое число монет (списываются, когда приглашение принято).
 * Победитель забирает обе ставки, при ничьей каждому возвращается своя.
 */
object GameNet {
    private const val TAG = "GameNet"
    private const val PACKET: Byte = 0xAA.toByte()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        GameInvite.init()
        // Монеты, рекорды, победы поменялись - через несколько секунд (чтобы не слать на каждую
        // монетку) друзья в сети получают обновлённую карточку профиля.
        Coins.listen { schedulePush() }
    }

    private val push = Runnable {
        val c = app ?: return@Runnable
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { (c as App).component.profileCard().sendToOnline() } }
        }.start()
    }

    private fun schedulePush() {
        main.removeCallbacks(push)
        main.postDelayed(push, 4_000)
    }

    private val statsAsked = HashMap<String, Long>()

    /** Попросить друга прислать свежую карточку (монеты, рекорды) - при открытии его профиля. */
    fun requestStats(pk: String) {
        send(pk, JSONObject().put("g", "stats").put("t", "req"))
    }

    private fun onStats(ctx: Context, pk: String) {
        val now = System.currentTimeMillis()
        if (now - (statsAsked[pk.uppercase()] ?: 0L) < 8_000) return
        statsAsked[pk.uppercase()] = now
        Thread { runCatching { (ctx as App).component.profileCard().sendTo(PublicKey(pk)) } }.start()
    }

    /** Слушатели событий (экран партии, хаб игр). */
    private val listeners = LinkedHashSet<(ChessSave) -> Unit>()
    fun listen(l: (ChessSave) -> Unit) = main.post { listeners += l }
    fun unlisten(l: (ChessSave) -> Unit) = main.post { listeners -= l }
    private fun changed(s: ChessSave) = main.post { listeners.toList().forEach { runCatching { it(s) } } }

    /** Приглашение пришло, пока приложение открыто - показываем окно сразу. */
    @Volatile
    var onInvite: ((ChessSave) -> Unit)? = null

    // ---------------- сохранение (SavePlayer.save) ----------------

    class ChessSave(
        val id: String,
        val opp: String,
        var oppName: String,
        /** Мой цвет: 0 - белые, 1 - чёрные. */
        val color: Int,
        val stake: Int,
        /** invited (ждём ответа), incoming (нас пригласили), playing, finished, declined. */
        var state: String,
        val moves: MutableList<String> = ArrayList(),
        val chat: MutableList<String> = ArrayList(),
        /** Итог: 1 - я выиграл, -1 - проиграл, 0 - ничья, null - идёт. */
        var result: Int? = null,
        var reason: String = "",
        var settled: Boolean = false,
        var updated: Long = System.currentTimeMillis(),
        /** Соперник удалил у себя сохранение этой партии (его имя) - пусто, если нет. */
        var delBy: String = "",
        /** Я не смог внести ставку (не хватило монет, когда соперник принял) - выигрыш только его ставка. */
        var noStake: Boolean = false,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("format", "SavePlayer.save/1").put("game", "chess")
            .put("id", id).put("opp", opp).put("oppName", oppName).put("color", color).put("stake", stake)
            .put("state", state).put("moves", JSONArray(moves)).put("chat", JSONArray(chat))
            .put("result", result ?: JSONObject.NULL).put("reason", reason).put("settled", settled).put("updated", updated)
            .put("delBy", delBy).put("ns", noStake)

        companion object {
            fun from(o: JSONObject): ChessSave {
                fun list(a: JSONArray?) = ArrayList<String>().apply { if (a != null) for (i in 0 until a.length()) add(a.getString(i)) }
                return ChessSave(
                    o.getString("id"), o.getString("opp"), o.optString("oppName"), o.optInt("color"), o.optInt("stake"),
                    o.optString("state", "playing"), list(o.optJSONArray("moves")), list(o.optJSONArray("chat")),
                    if (o.isNull("result")) null else o.optInt("result"), o.optString("reason"), o.optBoolean("settled"),
                    o.optLong("updated"), o.optString("delBy"), o.optBoolean("ns"),
                )
            }
        }
    }

    private fun dir(ctx: Context, id: String) = File(File(ctx.filesDir, "games/chess"), id).apply { mkdirs() }

    private fun file(ctx: Context, id: String) = File(File(File(ctx.filesDir, "games/chess"), id), "SavePlayer.save")

    /** Удалённые в этом запуске партии: открытый экран партии не должен вернуть файл при закрытии. */
    private val removed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Записать партию атомарно (`.tmp` -> основной файл, прежний - в `.bak`). */
    fun save(ctx: Context, s: ChessSave) {
        if (s.id in removed) return
        s.updated = System.currentTimeMillis()
        val disk = load(ctx, s.id)
        if (disk != null) {
            // Отметку «соперник удалил» не теряем, если пишет экран партии со старой копией.
            if (s.delBy.isEmpty() && disk.delBy.isNotEmpty()) s.delBy = disk.delBy
            // Итог партии окончательный: старая копия (открытый экран, сохранение при сворачивании)
            // не должна его стереть - иначе повторный «сдаюсь» рассчитал бы ставки второй раз.
            if (s.result == null && disk.result != null) {
                s.result = disk.result
                s.reason = disk.reason
                s.state = disk.state
            }
            if (disk.settled) s.settled = true
            if (disk.noStake) s.noStake = true
        }
        runCatching { dir(ctx, s.id); GameSaveSync.write(file(ctx, s.id), s.json().toString()) }
            .onFailure { Log.w(TAG, "save: $it") }
        changed(s)
    }

    /** Загрузить партию; основной файл испорчен - из `.tmp` / `.bak`. */
    fun load(ctx: Context, id: String): ChessSave? {
        // Уведомления онлайн-блэкджека открывают «bj:<id>»: MainActivity открывает игры через этот
        // метод - показываем стол блэкджека (у шахмат таких номеров не бывает).
        if (id.startsWith(com.stoyko.smessenger.games.blackjack.BjNet.GAME + ":")) {
            runCatching { com.stoyko.smessenger.games.blackjack.BjNet.openFromIntent(ctx, id.substringAfter(':')) }
            return null
        }
        return GameSaveSync.read(file(ctx, id)) { ChessSave.from(it) }
    }

    fun all(ctx: Context): List<ChessSave> = (File(ctx.filesDir, "games/chess").listFiles() ?: emptyArray())
        .mapNotNull { d -> GameSaveSync.read(File(d, "SavePlayer.save")) { ChessSave.from(it) } }
        .sortedByDescending { it.updated }

    fun delete(ctx: Context, id: String) {
        removed += id
        File(File(ctx.filesDir, "games/chess"), id).deleteRecursively()
    }

    /**
     * Игрок удаляет сохранение из списка игр. Незаконченная партия = сдаться (ставка уходит
     * сопернику), входящее приглашение = отклонить. Сопернику уходит пакет `del` (досылается,
     * пока он не в сети) - у него спросят, удалить ли партию и у себя.
     */
    fun deleteByUser(ctx: Context, s0: ChessSave) {
        val s = load(ctx, s0.id) ?: s0
        val tell = s.delBy.isEmpty() && s.state != "declined"
        if (s.state == "playing" && s.result == null) runCatching { resign(ctx, s) }
        if (s.state == "incoming") runCatching { decline(ctx, s) }
        if (tell) GameSaveSync.queue(ctx, "chess", s.id, s.opp, JSONObject().put("g", "chess").put("t", "del").put("id", s.id))
        delete(ctx, s.id)
        GameSaveSync.cancel(ctx, "chess", s.id)
        changed(s)
    }

    // ---------------- отправка ----------------

    internal fun send(pk: String, o: JSONObject): Boolean {
        val ctx = app ?: return false
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1300) return false
        return runCatching {
            (ctx as App).component.tox().sendLosslessPacket(PublicKey(pk), byteArrayOf(PACKET) + bytes)
            true
        }.getOrDefault(false)
    }

    /** Пригласить контакт на партию со ставкой. */
    fun invite(ctx: Context, opp: String, oppName: String, stake: Int, myName: String): ChessSave? {
        if (stake > Coins.balance(ctx)) return null
        val id = java.util.UUID.randomUUID().toString().take(12)
        val color = if (Math.random() < 0.5) 0 else 1
        val s = ChessSave(id, opp, oppName, color, stake, "invited")
        save(ctx, s)
        send(opp, JSONObject().put("g", "chess").put("t", "inv").put("id", id).put("stake", stake).put("c", color).put("name", myName))
        GameInvite.post(ctx, opp, "chess", id, stake, outgoing = true)
        return s
    }

    /** Принять приглашение (списывает ставку). false - не хватает монет. */
    fun accept(ctx: Context, s: ChessSave): Boolean {
        if (!Coins.spend(ctx, s.stake)) return false
        s.state = "playing"
        save(ctx, s)
        send(s.opp, JSONObject().put("g", "chess").put("t", "acc").put("id", s.id))
        return true
    }

    /**
     * Отклонить приглашение: партия у меня удаляется (в лобби/на доску не попасть), карточка в чате
     * показывает «Вы отклонили». Приглашающему - «dec» сразу и «del» с повтором, пока он не
     * подтвердит (был не в сети - узнает, когда появится; «del» понимают и старые версии).
     */
    fun decline(ctx: Context, s: ChessSave) {
        s.state = "declined"
        GameInvite.markDeclined(ctx, "chess", s.id)
        send(s.opp, JSONObject().put("g", "chess").put("t", "dec").put("id", s.id))
        GameSaveSync.queue(ctx, "chess", s.id, s.opp, JSONObject().put("g", "chess").put("t", "del").put("id", s.id))
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(s.id.hashCode()) }
        delete(ctx, s.id)
        changed(s)
    }

    fun move(ctx: Context, s: ChessSave, uci: String) {
        s.moves += uci
        save(ctx, s)
        send(s.opp, JSONObject().put("g", "chess").put("t", "mv").put("id", s.id).put("n", s.moves.size).put("m", uci))
    }

    fun chat(ctx: Context, s: ChessSave, text: String) {
        s.chat += "me:" + text.take(300)
        save(ctx, s)
        send(s.opp, JSONObject().put("g", "chess").put("t", "chat").put("id", s.id).put("x", text.take(300)))
    }

    /** Очередь досылки «сдаюсь» (отдельно от удалений - у них свой ключ в [GameSaveSync]). */
    private const val RESIGN_Q = "chessres"

    private fun resignPacket(id: String) = JSONObject().put("g", "chess").put("t", "resign").put("id", id)

    /**
     * Сдаться. Итог у себя - сразу, сопернику - пакет «resign», который повторяется (при каждом
     * его появлении в сети, при открытии партии, на его «need»/ход), пока он не ответит «resok».
     * Раньше пакет уходил один раз: соперник не в сети - партия у него так и «шла».
     */
    fun resign(ctx: Context, s: ChessSave) {
        if (s.result != null || s.state != "playing") return
        finish(ctx, s, -1, "resign")
        GameSaveSync.queue(ctx, RESIGN_Q, s.id, s.opp, resignPacket(s.id))
    }

    /** Я сдался в этой партии - напомнить сопернику (он мог не получить «resign»). */
    private fun resendResign(s: ChessSave) {
        if (s.result == -1 && s.reason == "resign") send(s.opp, resignPacket(s.id))
    }

    /** Сверить ходы с соперником (при открытии партии и когда он снова в сети). */
    /** Я открыл партию, чтобы продолжить: соперник получит уведомление и карточку в чат. */
    fun here(ctx: Context, s: ChessSave) {
        if (s.state != "playing" || s.result != null) return
        val me = runCatching { (ctx.applicationContext as App).component.tox().getName() }.getOrNull().orEmpty()
        send(s.opp, JSONObject().put("g", "chess").put("t", "here").put("id", s.id).put("name", me.take(40)))
    }

    fun sync(s: ChessSave) {
        if (s.result != null) {
            resendResign(s)
            return
        }
        send(s.opp, JSONObject().put("g", "chess").put("t", "need").put("id", s.id).put("n", s.moves.size))
    }

    /** Итог партии + расчёт ставок (один раз). */
    fun finish(ctx: Context, s: ChessSave, result: Int, reason: String) {
        if (s.result != null) return
        s.result = result
        s.reason = reason
        s.state = "finished"
        if (!s.settled) {
            s.settled = true
            runCatching { com.stoyko.smessenger.games.stats.GameStats.chessNet(ctx, result) }
            when (result) {
                1 -> {
                    // Своей ставки не вносил - выигрыш только ставка соперника.
                    Coins.add(ctx, if (s.noStake) s.stake else s.stake * 2)
                    Coins.addChessWin(ctx)
                }
                0 -> if (!s.noStake) Coins.add(ctx, s.stake)
            }
        }
        save(ctx, s)
    }

    // ---------------- приём ----------------

    /** Друг снова в сети: досылаем переводы монет и сверяем ходы незаконченных партий. */
    fun onPeerOnline(ctx: Context, pk: String) {
        val p = ctx.getSharedPreferences("coin_tx", Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (!k.startsWith("out_")) continue
            val parts = (v as? String)?.split('|') ?: continue
            if (parts.size == 2 && parts[0].equals(pk, true)) {
                send(pk, JSONObject().put("g", "coins").put("t", "give").put("tx", k.removePrefix("out_")).put("n", parts[1].toIntOrNull() ?: continue))
            }
        }
        for (s in all(ctx)) if (s.opp.equals(pk, true) && s.result == null && s.state == "playing") sync(s)
        runCatching { GameSaveSync.resend(ctx, pk) }
        runCatching { DurakNet.onPeerOnline(ctx, pk) }
        runCatching { com.stoyko.smessenger.games.blackjack.BjNet.onPeerOnline(ctx, pk) }
        // Аккаунт разработчика: сообщаем другу свои ключи в группах (в группах у каждого
        // участника отдельный ключ) - тогда друг видит моё имя в группах золотым.
        if (com.stoyko.smessenger.chat.DevAccount.isMe(ctx) || com.stoyko.smessenger.chat.DevAccount.isMeDesigner(ctx)) {
            runCatching {
                val tox = (ctx.applicationContext as App).component.tox()
                val keys = JSONArray()
                for ((chatId, _) in tox.groups()) {
                    val k = tox.groupSelfKey(chatId)
                    if (k.isNotEmpty()) keys.put(k.uppercase())
                }
                if (keys.length() > 0) send(pk, JSONObject().put("g", "dev").put("t", "keys").put("k", keys))
            }
        }
    }

    // ---------------- все подарки друга ----------------

    private val giftWaiters = HashMap<String, (List<Pair<Int, Int>>) -> Unit>()

    /** Запросить у друга полный список его подарков (номер и сколько штук). */
    fun requestGifts(pk: String, done: (List<Pair<Int, Int>>) -> Unit) {
        giftWaiters[pk.uppercase()] = done
        send(pk, JSONObject().put("g", "gifts").put("t", "req"))
    }

    private fun onGifts(ctx: Context, pk: String, o: JSONObject) {
        when (o.optString("t")) {
            "req" -> {
                // Отдаём только если человек не скрыл свои подарки и монеты.
                if (!Coins.shareStats(ctx)) return
                val ids = com.stoyko.smessenger.chat.Gifts.received(ctx).groupingBy { it.id }.eachCount()
                    .entries.joinToString(",") { (id, c) -> if (c > 1) "$id*$c" else "$id" }
                send(pk, JSONObject().put("g", "gifts").put("t", "all").put("ids", ids.take(1200)))
            }
            "all" -> {
                val list = o.optString("ids").split(',').mapNotNull { t ->
                    val id = t.substringBefore('*').toIntOrNull() ?: return@mapNotNull null
                    id to (t.substringAfter('*', "1").toIntOrNull() ?: 1).coerceIn(1, 999)
                }
                giftWaiters.remove(pk.uppercase())?.invoke(list)
            }
        }
    }

    /** Подарить монеты другу (перевод). */
    fun sendCoins(ctx: Context, pk: String, n: Int) {
        val tid = java.util.UUID.randomUUID().toString().take(12)
        // Перевод повторяется, пока друг не подтвердит (без задвоения - по номеру перевода).
        val p = ctx.getSharedPreferences("coin_tx", Context.MODE_PRIVATE)
        p.edit().putString("out_$tid", "$pk|$n").apply()
        send(pk, JSONObject().put("g", "coins").put("t", "give").put("tx", tid).put("n", n))
    }

    /** Досылаем неподтверждённые переводы (вызывается, когда друг в сети и при открытии «Игр»). */
    fun resendCoins(ctx: Context) {
        val p = ctx.getSharedPreferences("coin_tx", Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (!k.startsWith("out_")) continue
            val (pk, n) = (v as? String)?.split('|')?.takeIf { it.size == 2 } ?: continue
            send(pk, JSONObject().put("g", "coins").put("t", "give").put("tx", k.removePrefix("out_")).put("n", n.toIntOrNull() ?: continue))
        }
    }

    private fun onCoins(ctx: Context, pk: String, o: JSONObject) {
        val p = ctx.getSharedPreferences("coin_tx", Context.MODE_PRIVATE)
        val tx = o.optString("tx").take(20)
        when (o.optString("t")) {
            "give" -> {
                val n = o.optInt("n").coerceIn(0, 1_000_000)
                if (p.getBoolean("in_$tx", false)) {
                    send(pk, JSONObject().put("g", "coins").put("t", "ok").put("tx", tx))
                } else if (n > 0 && com.stoyko.smessenger.chat.DevAccount.allowIncoming(ctx, pk, n)) {
                    p.edit().putBoolean("in_$tx", true).apply()
                    Coins.add(ctx, n)
                    notifyCoins(ctx, pk, n)
                    send(pk, JSONObject().put("g", "coins").put("t", "ok").put("tx", tx))
                } else if (n > 0) {
                    // Сверх дневного лимита - отказ, отправителю монеты вернутся.
                    send(pk, JSONObject().put("g", "coins").put("t", "no").put("tx", tx))
                }
            }
            "ok" -> p.edit().remove("out_$tx").apply()
            "no" -> {
                // Получатель отказался (лимит) - возвращаем свои монеты.
                val v = p.getString("out_$tx", null)
                if (v != null && v.substringBefore('|').equals(pk, ignoreCase = true)) {
                    p.edit().remove("out_$tx").apply()
                    v.substringAfter('|').toIntOrNull()?.takeIf { it > 0 }?.let { Coins.add(ctx, it) }
                }
            }
        }
    }

    private fun notifyCoins(ctx: Context, pk: String, n: Int) {
        runCatching {
            val name = kotlinx.coroutines.runBlocking { (ctx as App).component.contactRepository().getAll().first() }
                .firstOrNull { it.publicKey.equals(pk, true) }?.name.orEmpty().ifBlank { "?" }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.games_title), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val n2 = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.coins_received_title))
                .setContentText(ctx.getString(R.string.coins_received_text, name, n))
                .setAutoCancel(true)
                .build()
            nm.notify(("c$pk$n").hashCode(), n2)
        }
    }

    fun handle(pk: String, data: ByteArray): Boolean {
        if (data.isEmpty() || data[0] != PACKET) return false
        val ctx = app ?: return true
        val o = runCatching { JSONObject(String(data, 1, data.size - 1, Charsets.UTF_8)) }.getOrNull() ?: return true
        if (o.optString("g") == "stats") {
            main.post { runCatching { onStats(ctx, pk) } }
            return true
        }
        if (o.optString("g") == com.stoyko.smessenger.games.blackjack.BjNet.GAME) {
            main.post { runCatching { com.stoyko.smessenger.games.blackjack.BjNet.onPacket(ctx, pk, o) }.onFailure { Log.w(TAG, "bj: $it") } }
            return true
        }
        if (o.optString("g") == "durak") {
            main.post { runCatching { DurakNet.onPacket(ctx, pk, o) }.onFailure { Log.w(TAG, "durak: $it") } }
            return true
        }
        if (o.optString("g") == "gifts") {
            main.post { runCatching { onGifts(ctx, pk, o) } }
            return true
        }
        if (o.optString("g") == "dev") {
            // Принимаем ТОЛЬКО от настоящего разработчика (ключ отправителя подтверждает протокол).
            val dev = com.stoyko.smessenger.chat.DevAccount.isDev(pk)
            val designer = com.stoyko.smessenger.chat.DevAccount.isDesigner(pk)
            if (dev || designer) {
                val a = o.optJSONArray("k")
                if (a != null) com.stoyko.smessenger.chat.DevAccount.rememberGroupKeys(ctx, (0 until a.length()).map { a.optString(it) }, designer = !dev)
            }
            return true
        }
        if (o.optString("g") == "coins") {
            main.post { runCatching { onCoins(ctx, pk, o) } }
            return true
        }
        if (o.optString("g") != "chess") return true
        val id = o.optString("id").takeIf { it.matches(Regex("[0-9a-f-]{6,40}")) } ?: return true
        main.post { runCatching { onPacket(ctx, pk, id, o) }.onFailure { Log.w(TAG, "packet: $it") } }
        return true
    }

    private fun onPacket(ctx: Context, pk: String, id: String, o: JSONObject) {
        val existing = load(ctx, id)
        if (existing != null && !existing.opp.equals(pk, ignoreCase = true)) return
        when (o.optString("t")) {
            "inv" -> {
                // Уже отклонённое приглашение (повтор пакета) - не воскрешаем.
                if (existing != null || GameInvite.wasDeclined(ctx, "chess", id)) return
                // Имя - как этот человек записан у меня в контактах.
                val name = runCatching {
                    kotlinx.coroutines.runBlocking { (ctx as App).component.contactRepository().getAll().first() }
                        .firstOrNull { it.publicKey.equals(pk, true) }?.name
                }.getOrNull()?.ifBlank { null } ?: o.optString("name").take(40)
                // Цвет приглашающего - у меня противоположный.
                val s = ChessSave(id, pk, name, 1 - o.optInt("c"), o.optInt("stake").coerceIn(0, if (com.stoyko.smessenger.chat.DevAccount.isDev(pk)) 100_000 else MAX_STAKE), "incoming")
                save(ctx, s)
                // Приглашение - карточкой в чат с соперником («Принять» / «Отклонить»).
                GameInvite.post(ctx, pk, "chess", id, s.stake, outgoing = false)
                val ui = onInvite
                if (ui != null) ui(s)
                notifyInvite(ctx, s)
            }
            "acc" -> {
                val s = existing ?: return
                if (s.state != "invited") return
                acceptedByOpp(ctx, s)
                save(ctx, s)
            }
            "dec" -> existing?.let {
                it.state = "declined"
                save(ctx, it)
            }
            "mv" -> {
                val s = existing ?: return
                // Партия у меня уже закончена (я сдался, а соперник этого не получил) - напоминаем.
                if (s.result != null) {
                    resendResign(s)
                    return
                }
                val n = o.optInt("n")
                val m = o.optString("m")
                when {
                    n == s.moves.size + 1 && m.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) -> {
                        val e = ChessEngine()
                        if (e.load(s.moves) && e.play(m)) {
                            s.moves += m
                            save(ctx, s)
                            checkEnd(ctx, s, e)
                        }
                    }
                    n > s.moves.size + 1 -> sync(s)
                }
            }
            "need" -> {
                val s = existing ?: return
                val from = o.optInt("n").coerceAtLeast(0)
                resendResign(s)
                if (s.moves.size > from) {
                    send(pk, JSONObject().put("g", "chess").put("t", "sync").put("id", id).put("from", from).put("ms", s.moves.drop(from).joinToString(",")))
                }
            }
            "sync" -> {
                val s = existing ?: return
                val from = o.optInt("from")
                if (from != s.moves.size) return
                val add = o.optString("ms").split(',').filter { it.isNotBlank() }
                val e = ChessEngine()
                if (!e.load(s.moves)) return
                for (m in add) {
                    if (!e.play(m)) break
                    s.moves += m
                }
                save(ctx, s)
                checkEnd(ctx, s, e)
            }
            "chat" -> existing?.let {
                it.chat += "op:" + o.optString("x").take(300)
                save(ctx, it)
            }
            "resign" -> {
                // Подтверждаем всегда (даже если партии уже нет) - иначе соперник слал бы вечно.
                send(pk, JSONObject().put("g", "chess").put("t", "resok").put("id", id))
                val s = existing ?: return
                when (s.state) {
                    // «Принял» потерялся, а соперник уже сдался - сначала вносим ставку, как при «acc».
                    "invited" -> acceptedByOpp(ctx, s)
                    "playing", "finished" -> Unit
                    else -> return
                }
                // Итог и расчёт - один раз (finish не повторяется, сохранение не теряет итог).
                if (s.result == null) finish(ctx, s, 1, "resign")
            }
            "resok" -> GameSaveSync.acked(ctx, RESIGN_Q, id, pk)
            // Соперник удалил сохранение (у него это - сдача, если партия шла).
            "del" -> {
                send(pk, JSONObject().put("g", "chess").put("t", "delok").put("id", id))
                val s = existing ?: return
                if (s.delBy.isNotEmpty()) return
                when (s.state) {
                    // Приглашение отозвали - монеты не списывались, хранить нечего.
                    "incoming" -> {
                        delete(ctx, id)
                        changed(s)
                        return
                    }
                    "invited" -> {
                        s.state = "declined"
                        save(ctx, s)
                        return
                    }
                    "declined" -> return
                }
                if (s.result == null) finish(ctx, s, 1, "resign")
                s.delBy = s.oppName.ifBlank { "?" }
                save(ctx, s)
                GameSaveSync.ask(ctx, "chess", id, s.delBy)
            }
            "delok" -> GameSaveSync.acked(ctx, "chess", id, pk)
            "here" -> existing?.let {
                resendResign(it)
                if (it.state == "playing" && it.result == null) {
                    GameInvite.joined(ctx, pk, "chess", id, it.stake, it.oppName.ifBlank { o.optString("name").take(40) })
                }
            }
        }
    }

    /** Соперник принял моё приглашение: вношу ставку (не хватает - партия без моей ставки). */
    private fun acceptedByOpp(ctx: Context, s: ChessSave) {
        if (s.stake > 0 && !Coins.spend(ctx, s.stake)) {
            // Монеты успели потратить - партия без ставки.
            s.noStake = true
            s.chat += "sys:" + ctx.getString(R.string.chess_no_stake)
        }
        s.state = "playing"
    }

    /** Мат/пат/ничья - итог одинаково считается на обоих телефонах. */
    fun checkEnd(ctx: Context, s: ChessSave, e: ChessEngine) {
        when (e.status()) {
            ChessEngine.Status.CHECKMATE -> finish(ctx, s, if (e.side == s.color) -1 else 1, "mate")
            ChessEngine.Status.PLAYING -> Unit
            else -> finish(ctx, s, 0, e.status().name.lowercase())
        }
    }

    private fun notifyInvite(ctx: Context, s: ChessSave) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.games_title), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val open = android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(ACTION_OPEN_GAME).putExtra(EXTRA_GAME, s.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(ctx, s.id.hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.chess_invite_title))
                .setContentText(ctx.getString(R.string.chess_invite_text, s.oppName.ifBlank { "?" }, s.stake))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(s.id.hashCode(), n)
        }
    }

    /** Ставка в чужом приглашении - не больше, чем можно выбрать в меню (защита от самодельных сборок). */
    const val MAX_STAKE = 100
    const val ACTION_OPEN_GAME = "com.stoyko.smessenger.OPEN_GAME"
    const val EXTRA_GAME = "game_id"
}
