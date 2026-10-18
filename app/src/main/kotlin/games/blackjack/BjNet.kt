// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.games.GameInvite
import com.stoyko.smessenger.games.GameNet
import com.stoyko.smessenger.games.GameSaveSync
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Блэкджек онлайн с контактами - невидимыми пакетами ядра, как «Дурак» и шахматы.
 *
 * Пригласивший - ДИЛЕР (место 0) и хозяин стола: у него башмак, он проверяет ходы и рассылает
 * всем состояние. Игроки (места 1..4) шлют дилеру ставки и ходы. У каждого стол хранится в
 * `games/blackjack/<id>/SavePlayer.save` - вышли, перезапустили, пропала связь - всё
 * продолжается с того же места.
 *
 * Монеты ходят между настоящими балансами бережно (см. [BjLedger]):
 *  - игрок ставит - ставка списывается у него сразу (запоминается по номеру раунда);
 *  - дилер принимает ставку, только если может её покрыть: покрытие (6/5 ставки, при удвоении
 *    и разделении - ещё) списывается у дилера в банк стола; непокрытая ставка отклоняется;
 *  - по итогу раунда каждый телефон зачисляет СЕБЕ своё ровно один раз (ключ «стол / раунд /
 *    место»): игрок - возврат по своим рукам, дилер - банк + проигранные ставки - выплаты;
 *  - не принятая / несыгранная ставка (раунд отменён, дилер закрыл стол) возвращается игроку
 *    тем же ключом - вернуть и выплатить одну ставку дважды нельзя.
 * Состояние стола игрок подтверждает пакетом `ack`; пока подтверждения нет, дилер повторяет
 * (и досылает, когда игрок снова в сети).
 */
object BjNet {
    private const val TAG = "BjNet"
    const val GAME = "bj"
    private val main = Handler(Looper.getMainLooper())
    private val ID_RE = Regex("[0-9a-f-]{6,40}")

    class Save(
        val id: String,
        /** Ключи по местам; место 0 - дилер (хозяин стола). */
        val seats: List<String>,
        val names: MutableList<String>,
        val mySeat: Int,
        /** incoming (нас пригласили), playing, declined, closed (дилер закрыл стол). */
        var state: String,
        val accepted: MutableSet<Int> = HashSet(),
        var engine: JSONObject? = null,
        var version: Int = 0,
        val chat: MutableList<String> = ArrayList(),
        /** Игрок: сколько монет списано за раунд (раунд -> монеты), ещё не рассчитано. */
        val paid: MutableMap<Int, Int> = HashMap(),
        /** Раунды, рассчитанные на этом телефоне (игрок - свой возврат, дилер - банк). */
        val settled: MutableSet<Int> = HashSet(),
        var updated: Long = System.currentTimeMillis(),
        /** Места, удалившие у себя стол (или отказавшиеся). */
        val gone: MutableSet<Int> = HashSet(),
        /** Итог за всё время за этим столом (для подписи). */
        var net: Int = 0,
    ) {
        val host get() = seats[0]
        val isDealer get() = mySeat == 0
        val players get() = seats.size

        fun engineOrNull(): BjEngine? = engine?.let { runCatching { BjEngine.fromJson(it) }.getOrNull() }

        fun json(): JSONObject = JSONObject()
            .put("format", "SavePlayer.save/1").put("game", "blackjack")
            .put("id", id).put("seats", JSONArray(seats)).put("names", JSONArray(names)).put("me", mySeat)
            .put("state", state).put("acc", JSONArray(accepted.toList()))
            .put("engine", engine ?: JSONObject.NULL).put("v", version).put("chat", JSONArray(chat))
            .put("paid", JSONObject().apply { for ((r, n) in paid) put(r.toString(), n) })
            .put("settled", JSONArray(settled.toList().sorted().takeLast(80)))
            .put("updated", updated).put("gone", JSONArray(gone.toList())).put("net", net)

        companion object {
            fun from(o: JSONObject): Save {
                fun list(a: JSONArray?) = ArrayList<String>().apply { if (a != null) for (i in 0 until a.length()) add(a.getString(i)) }
                fun ints(a: JSONArray?) = HashSet<Int>().apply { if (a != null) for (i in 0 until a.length()) add(a.getInt(i)) }
                val paid = HashMap<Int, Int>()
                o.optJSONObject("paid")?.let { p -> for (k in p.keys()) k.toIntOrNull()?.let { paid[it] = p.optInt(k).coerceAtLeast(0) } }
                val seats = list(o.getJSONArray("seats"))
                require(seats.size in 2..BjEngine.MAX_PLAYERS + 1)
                val names = list(o.optJSONArray("names"))
                while (names.size < seats.size) names += "?"
                return Save(
                    o.getString("id"), seats, names, o.optInt("me").coerceIn(0, seats.size - 1),
                    o.optString("state", "playing"), ints(o.optJSONArray("acc")), o.optJSONObject("engine"), o.optInt("v"),
                    list(o.optJSONArray("chat")), paid, ints(o.optJSONArray("settled")), o.optLong("updated"),
                    ints(o.optJSONArray("gone")), o.optInt("net"),
                )
            }
        }
    }

    // ---------------- слушатели ----------------

    private val listeners = LinkedHashSet<(Save) -> Unit>()
    fun listen(l: (Save) -> Unit) = main.post { listeners += l }
    fun unlisten(l: (Save) -> Unit) = main.post { listeners -= l }
    private fun changed(s: Save) = main.post { listeners.toList().forEach { runCatching { it(s) } } }

    /** Стол, открытый сейчас на экране (уведомления «ваш ход» для него не нужны). */
    @Volatile
    var openTable: String? = null

    // ---------------- файлы ----------------

    private fun root(ctx: Context) = File(ctx.filesDir, "games/blackjack")
    private fun file(ctx: Context, id: String) = File(File(root(ctx), id), "SavePlayer.save")
    private val removed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Записать стол атомарно (`.tmp` -> основной файл, прежний - в `.bak`). */
    fun save(ctx: Context, s: Save) {
        if (s.id in removed) return
        s.updated = System.currentTimeMillis()
        load(ctx, s.id)?.let { old ->
            // Отметки «ушёл» и рассчитанные раунды только добавляются (запись старой копии их не теряет).
            s.gone += old.gone
            s.settled += old.settled
            for (r in s.settled) s.paid.remove(r)
        }
        if (s.chat.size > 200) s.chat.subList(0, s.chat.size - 200).clear()
        runCatching { GameSaveSync.write(file(ctx, s.id), s.json().toString()) }.onFailure { Log.w(TAG, "save: $it") }
        changed(s)
    }

    fun load(ctx: Context, id: String): Save? = GameSaveSync.read(file(ctx, id)) { Save.from(it) }

    fun all(ctx: Context): List<Save> = (root(ctx).listFiles() ?: emptyArray())
        .mapNotNull { d -> GameSaveSync.read(File(d, "SavePlayer.save")) { Save.from(it) } }
        .sortedByDescending { it.updated }

    fun delete(ctx: Context, id: String) {
        removed += id
        File(root(ctx), id).deleteRecursively()
    }

    private fun myPk(ctx: Context): String = runCatching { (ctx.applicationContext as App).component.tox().publicKey.string() }.getOrDefault("")

    private fun send(pk: String, o: JSONObject): Boolean = GameNet.send(pk, o.put("g", GAME))

    private fun contactName(ctx: Context, pk: String): String? = runCatching {
        kotlinx.coroutines.runBlocking { (ctx.applicationContext as App).component.contactRepository().getAll().first() }
            .firstOrNull { it.publicKey.equals(pk, true) }?.name?.ifBlank { null }
    }.getOrNull()

    // ---------------- результаты действий ----------------

    const val OK = 0
    const val NO_COINS = 1
    const val OFFLINE = 2
    const val LIMIT = 3
    const val BAD = 4

    // ---------------- стол ----------------

    /** Создать стол: я - дилер, [opps] - (ключ, имя) игроков (1..4). */
    fun create(ctx: Context, opps: List<Pair<String, String>>): Save? {
        if (opps.isEmpty() || opps.size > BjEngine.MAX_PLAYERS) return null
        val me = myPk(ctx)
        if (me.isEmpty()) return null
        val myName = runCatching { (ctx.applicationContext as App).component.tox().getName() }.getOrDefault("").ifBlank { "?" }
        val id = java.util.UUID.randomUUID().toString().take(12)
        val seats = listOf(me) + opps.map { it.first }
        val s = Save(id, seats, (listOf(myName.take(40)) + opps.map { it.second.take(40) }).toMutableList(), 0, "playing")
        s.accepted += 0
        s.engine = BjEngine(seats.size).toJson()
        s.version = 1
        save(ctx, s)
        val inv = JSONObject().put("t", "inv").put("id", id).put("seats", JSONArray(seats)).put("names", JSONArray(s.names))
        for ((pk, _) in opps) send(pk, JSONObject(inv.toString()))
        for ((pk, _) in opps) GameInvite.post(ctx, pk, GAME, id, 0, outgoing = true)
        return s
    }

    fun accept(ctx: Context, s: Save): Boolean {
        if (s.state != "incoming") return true
        s.state = "playing"
        s.accepted += s.mySeat
        save(ctx, s)
        send(s.host, JSONObject().put("t", "acc").put("id", s.id))
        return true
    }

    fun decline(ctx: Context, s: Save) {
        s.state = "declined"
        save(ctx, s)
        send(s.host, JSONObject().put("t", "dec").put("id", s.id))
    }

    fun chat(ctx: Context, s: Save, text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        s.chat += "${s.mySeat}:$t"
        save(ctx, s)
        broadcast(ctx, s, JSONObject().put("t", "chat").put("id", s.id).put("x", t))
    }

    private fun broadcast(ctx: Context, s: Save, o: JSONObject) {
        val me = myPk(ctx)
        for ((i, pk) in s.seats.withIndex()) if (i != s.mySeat && i !in s.gone && !pk.equals(me, true)) send(pk, JSONObject(o.toString()))
    }

    private fun statePacket(s: Save, e: BjEngine, seat: Int): JSONObject =
        JSONObject().put("t", "st").put("id", s.id).put("v", s.version).put("state", s.state)
            .put("acc", JSONArray(s.accepted.toList())).put("s", e.publicJson(seat))

    /** Подтверждённые игроками версии стола: «стол|место» -> версия. */
    private val acked = HashMap<String, Int>()

    /** Дилер: сохранить и разослать каждому игроку его вид стола. */
    private fun publish(ctx: Context, s: Save, e: BjEngine) {
        s.engine = e.toJson()
        s.version++
        save(ctx, s)
        for (i in 1 until s.players) if (i !in s.gone) send(s.seats[i], statePacket(s, e, i))
    }

    /** Дилер: повторить состояние тем, кто его ещё не подтвердил (раз в несколько секунд, пока стол открыт). */
    fun resendUnacked(ctx: Context, id: String) {
        val s = load(ctx, id) ?: return
        if (!s.isDealer || s.state != "playing") return
        val e = s.engineOrNull() ?: return
        for (i in 1 until s.players) {
            // Не севшим за стол (могли и не обновить приложение) - не повторяем.
            if (i in s.gone || i !in s.accepted) continue
            if ((acked["${s.id}|$i"] ?: 0) < s.version) send(s.seats[i], statePacket(s, e, i))
        }
    }

    /** Игрок: подтвердил ли дилеру последнюю версию (для подписи «нет связи»). */
    fun isAcked(s: Save, seat: Int): Boolean = (acked["${s.id}|$seat"] ?: 0) >= s.version

    // ---------------- ходы игрока ----------------

    /** Игрок ставит [amount]: монеты списываются сразу. */
    fun bet(ctx: Context, s: Save, amount: Int): Int {
        if (s.isDealer || s.state != "playing") return BAD
        val e = s.engineOrNull() ?: return BAD
        val me = s.mySeat
        if (e.phase != BjEngine.BETS || e.betSt[me] != BjEngine.B_NONE || amount !in BjEngine.MIN_BET..BjEngine.MAX_BET) return BAD
        if (s.paid.containsKey(e.round) || e.round in s.settled) return BAD
        if (!BjLedger.canVolume(ctx, s.host, amount)) return LIMIT
        if (!Coins.spend(ctx, amount)) return NO_COINS
        val sent = send(s.host, JSONObject().put("t", "bet").put("id", s.id).put("r", e.round).put("n", amount))
        if (!sent) {
            // Дилер не в сети - ставка не ушла, монеты сразу назад.
            Coins.add(ctx, amount)
            return OFFLINE
        }
        BjLedger.addVolume(ctx, s.host, amount)
        s.paid[e.round] = amount
        BjLedger.log(ctx, "bet ${s.id} r=${e.round} -$amount")
        // Сразу показываем у себя «ставка ждёт дилера»; дилер пришлёт итоговое состояние.
        e.placeBet(me, amount)
        s.engine = e.publicJson(me)
        save(ctx, s)
        return OK
    }

    /** Ход игрока: hit / stand / dbl / split. Для «Удвоить» и «Разделить» доплата списывается сразу. */
    fun act(ctx: Context, s: Save, a: String): Int {
        if (s.isDealer || s.state != "playing") return BAD
        val e = s.engineOrNull() ?: return BAD
        val h = e.current() ?: return BAD
        if (e.active != s.mySeat || h.done) return BAD
        val extra = when (a) {
            "dbl" -> if (e.canDouble(s.mySeat)) h.bet else return BAD
            "split" -> if (e.canSplit(s.mySeat)) h.bet else return BAD
            "hit", "stand" -> 0
            "sur" -> if (e.canSurrender(s.mySeat)) 0 else return BAD
            else -> return BAD
        }
        if (extra > 0 && !BjLedger.canVolume(ctx, s.host, extra)) return LIMIT
        if (extra > 0 && !Coins.spend(ctx, extra)) return NO_COINS
        val sent = send(
            s.host,
            JSONObject().put("t", "act").put("id", s.id).put("r", e.round).put("a", a)
                .put("h", e.activeHand).put("k", h.cards.size).put("v", s.version),
        )
        if (!sent) {
            if (extra > 0) Coins.add(ctx, extra)
            return OFFLINE
        }
        if (extra > 0) {
            BjLedger.addVolume(ctx, s.host, extra)
            s.paid[e.round] = (s.paid[e.round] ?: 0) + extra
            BjLedger.log(ctx, "$a ${s.id} r=${e.round} -$extra")
            save(ctx, s)
        }
        return OK
    }

    // ---------------- действия дилера ----------------

    private fun dealerEngine(s: Save): BjEngine? = if (s.isDealer && s.state == "playing") s.engineOrNull() else null

    /** Почему дилер отказал в последний раз: "bank" (не покрыть) / "limit" (дневной лимит). */
    private fun reserveFn(ctx: Context, s: Save, e: BjEngine, betOf: (Int) -> Int): (Int, Int) -> Boolean = { seat, need ->
        val pk = s.seats[seat]
        val b = betOf(seat)
        when {
            seat in s.gone -> false
            !BjLedger.canVolume(ctx, pk, b) -> {
                e.note = "limit"
                false
            }
            need > 0 && !Coins.spend(ctx, need) -> {
                if (e.note != "limit") e.note = "bank"
                false
            }
            else -> {
                BjLedger.addVolume(ctx, pk, b)
                true
            }
        }
    }

    /** Принять ставки, которые дилер может покрыть. Возвращает (принято, отклонено). */
    fun acceptBets(ctx: Context, s: Save): Pair<Int, Int> {
        val e = dealerEngine(s) ?: return 0 to 0
        val pending = e.pendingCount()
        e.note = ""
        val n = e.acceptBets(reserveFn(ctx, s, e) { e.bet[it] })
        if (n < 0) return 0 to 0
        BjLedger.log(ctx, "accept ${s.id} r=${e.round} n=$n bank=${e.bank()}")
        publish(ctx, s, e)
        return n to (pending - n)
    }

    fun deal(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (e.phase != BjEngine.READY) return false
        e.deal()
        publish(ctx, s, e)
        return true
    }

    /** Пропустить ход игрока, который не отвечает (как «Хватит»). */
    fun skip(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.skip()) return false
        publish(ctx, s, e)
        return true
    }

    /** Время на ход вышло: игрок - «Хватит», дилер - доигрывает сам. true - стол изменился. */
    fun timeout(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.expired()) return false
        return when (e.phase) {
            BjEngine.PLAY -> {
                val who = s.names.getOrElse(e.active) { "?" }
                if (!e.timeout()) return false
                s.chat += "sys:" + ctx.getString(R.string.bjack_timeout, who)
                publish(ctx, s, e)
                true
            }
            BjEngine.DEALER -> reveal(ctx, s)
            else -> false
        }
    }

    /** «Поторопить» игрока: на решение остаётся 5 секунд. */
    fun nudge(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.nudge()) return false
        s.chat += "sys:" + ctx.getString(R.string.bjack_nudged, s.names.getOrElse(e.active) { "?" })
        publish(ctx, s, e)
        return true
    }

    /** Перетасовать башмак (между раундами). */
    fun shuffle(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.shuffleShoe()) return false
        s.chat += "sys:" + ctx.getString(R.string.bjack_shuffled)
        publish(ctx, s, e)
        return true
    }

    /** Лимит ставки за столом. */
    fun setLimit(ctx: Context, s: Save, v: Int): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.setLimit(v)) return false
        s.chat += "sys:" + ctx.getString(R.string.bjack_limit_set, e.maxBet)
        publish(ctx, s, e)
        return true
    }

    /** Дилер по шагам: "open" - вскрыть карту, "hit" - взять, "stand" - хватит и расчёт. */
    fun dealerStep(ctx: Context, s: Save, a: String): Boolean {
        val e = dealerEngine(s) ?: return false
        val ok = when (a) {
            "open" -> e.dealerReveal()
            "hit" -> e.dealerHit()
            "stand" -> e.dealerFinish()
            else -> false
        }
        if (!ok) return false
        if (e.phase == BjEngine.DONE) settleDealer(ctx, s, e)
        publish(ctx, s, e)
        return true
    }

    /** Вскрыться, добрать по правилам и рассчитаться. */
    fun reveal(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (!e.dealerPlay()) return false
        settleDealer(ctx, s, e)
        publish(ctx, s, e)
        return true
    }

    fun newRound(ctx: Context, s: Save): Boolean {
        val e = dealerEngine(s) ?: return false
        if (e.phase == BjEngine.DONE) settleDealer(ctx, s, e)
        if (!e.newRound()) return false
        publish(ctx, s, e)
        return true
    }

    /** Дилер получает своё по итогу раунда - один раз (ключ «стол / раунд / дилер»). */
    private fun settleDealer(ctx: Context, s: Save, e: BjEngine) {
        if (e.phase != BjEngine.DONE || e.round in s.settled) return
        val credit = e.dealerCredit()
        BjLedger.credit(ctx, BjLedger.dealerKey(s.id, e.round), credit, "dealer ${s.id} r=${e.round} net=${e.dealerNet()}")
        s.settled += e.round
        s.net += e.dealerNet()
    }

    // ---------------- расчёт у игрока ----------------

    /**
     * Сверить свои оплаченные раунды с состоянием от дилера и зачислить возвраты ровно один раз.
     * [hostGone] - дилер закрыл стол: несыгранные раунды возвращаются полностью.
     */
    private fun reconcile(ctx: Context, s: Save, e: BjEngine, hostGone: Boolean = false) {
        if (s.isDealer) return
        val me = s.mySeat
        for ((r, amt) in s.paid.toList()) {
            if (r in s.settled) {
                s.paid.remove(r)
                continue
            }
            val h = e.histFor(me, r)
            val credit: Int? = when {
                h != null -> {
                    // Возврат по рукам (не больше, чем позволяют правила для настоящей ставки)
                    // + доплаты, которые дилер не принял.
                    val stake = minOf(h[1], amt)
                    minOf(h[2], stake * 11 / 5) + (amt - stake).coerceAtLeast(0)
                }
                hostGone -> amt
                e.round > r -> amt
                e.round == r && e.betSt[me] == BjEngine.B_REJECT -> amt
                e.round == r && e.phase > BjEngine.BETS && e.betSt[me] != BjEngine.B_OK -> amt
                else -> null
            }
            if (credit == null) {
                // Ставка ещё не дошла до дилера - повторяем (дилер принимает её один раз).
                val now = System.currentTimeMillis()
                val k = "${s.id}|$r"
                if (e.round == r && e.phase == BjEngine.BETS && e.betSt[me] == BjEngine.B_NONE && now - (betResent[k] ?: 0L) > 3_000) {
                    betResent[k] = now
                    send(s.host, JSONObject().put("t", "bet").put("id", s.id).put("r", r).put("n", amt))
                }
                continue
            }
            BjLedger.credit(ctx, BjLedger.playerKey(s.id, r, me), credit, "player ${s.id} r=$r paid=$amt")
            // Статистика игр: только сыгранные руки (не возвраты отклонённых ставок).
            if (h != null) runCatching { com.stoyko.smessenger.games.stats.GameStats.blackjack(ctx, (credit - amt).coerceIn(-1, 1)) }
            s.paid.remove(r)
            s.settled += r
            s.net += credit - amt
        }
    }

    /** Когда последний раз повторяли ставку («стол|раунд») - не чаще раза в 3 секунды. */
    private val betResent = HashMap<String, Long>()

    /** Монеты игрока, которые сейчас на столе и ещё не рассчитаны (для предупреждения при удалении). */
    fun atStake(s: Save): Int = if (s.isDealer) (s.engineOrNull()?.takeIf { it.phase != BjEngine.DONE }?.bank() ?: 0) else s.paid.values.sum()

    // ---------------- удаление ----------------

    /**
     * Игрок удаляет стол: его ставка в идущем раунде проиграна. Дилер закрывает стол: идущий
     * раунд отменяется - дилеру возвращается банк, игрокам - их ставки.
     */
    fun deleteByUser(ctx: Context, s0: Save) {
        val s = load(ctx, s0.id) ?: s0
        when {
            s.state == "incoming" -> decline(ctx, s)
            s.isDealer && s.state == "playing" -> {
                val e = s.engineOrNull()
                if (e != null && e.phase == BjEngine.DONE) settleDealer(ctx, s, e)
                if (e != null && e.phase != BjEngine.DONE && e.round !in s.settled && e.bank() > 0) {
                    BjLedger.credit(ctx, BjLedger.dealerKey(s.id, e.round), e.bank(), "dealer closed ${s.id} r=${e.round} refund")
                    s.settled += e.round
                }
                s.state = "closed"
                // Каждому - его вид стола в пакете удаления: по нему он рассчитается.
                for (i in 1 until s.players) if (i !in s.gone) {
                    val p = JSONObject().put("g", GAME).put("t", "del").put("id", s.id)
                    if (e != null) p.put("s", e.publicJson(i))
                    GameSaveSync.queue(ctx, GAME, s.id, s.seats[i], p)
                }
            }
            !s.isDealer && (s.state == "playing" || s.state == "closed") && 0 !in s.gone -> {
                GameSaveSync.queue(ctx, GAME, s.id, s.host, JSONObject().put("g", GAME).put("t", "del").put("id", s.id))
            }
        }
        delete(ctx, s.id)
        GameSaveSync.cancel(ctx, GAME, s.id)
        changed(s)
    }

    // ---------------- приём ----------------

    fun onPacket(ctx: Context, pk: String, o: JSONObject) {
        val id = o.optString("id").takeIf { it.matches(ID_RE) } ?: return
        val s = load(ctx, id)
        val seat = s?.seats?.indexOfFirst { it.equals(pk, true) } ?: -1
        when (o.optString("t")) {
            "inv" -> {
                if (s != null) return
                val seats = ArrayList<String>().apply { o.optJSONArray("seats")?.let { a -> for (i in 0 until a.length()) add(a.optString(i).take(76)) } }
                val names = ArrayList<String>().apply { o.optJSONArray("names")?.let { a -> for (i in 0 until a.length()) add(a.optString(i).take(40)) } }
                if (seats.size !in 2..BjEngine.MAX_PLAYERS + 1 || !seats[0].equals(pk, true)) return
                if (seats.map { it.uppercase() }.toSet().size != seats.size) return
                val me = myPk(ctx)
                val my = seats.indexOfFirst { it.equals(me, true) }
                if (my <= 0) return
                while (names.size < seats.size) names += "?"
                for (i in seats.indices) if (i != my) contactName(ctx, seats[i])?.let { names[i] = it }
                val ns = Save(id, seats, names, my, "incoming")
                ns.accepted += 0
                ns.engine = BjEngine(seats.size).publicJson(my)
                save(ctx, ns)
                GameInvite.post(ctx, pk, GAME, id, 0, outgoing = false)
                notify(ctx, ns, "i", ctx.getString(R.string.bjack_invite_title), ctx.getString(R.string.bjack_invite_text, ns.names[0]))
            }
            "acc" -> {
                if (s == null || !s.isDealer || seat <= 0 || s.state != "playing") return
                if (seat in s.accepted && seat !in s.gone) {
                    sync(ctx, s)
                    return
                }
                s.accepted += seat
                s.chat += "sys:" + ctx.getString(R.string.bjack_joined, s.names.getOrElse(seat) { "?" })
                val e = s.engineOrNull() ?: return
                publish(ctx, s, e)
            }
            "dec" -> {
                if (s == null || !s.isDealer || seat <= 0 || seat in s.gone) return
                s.chat += "sys:" + ctx.getString(R.string.bjack_declined, s.names.getOrElse(seat) { "?" })
                s.accepted -= seat
                s.gone += seat
                save(ctx, s)
            }
            "bet" -> {
                if (s == null || !s.isDealer || seat <= 0 || seat !in s.accepted || seat in s.gone || s.state != "playing") return
                val e = s.engineOrNull() ?: return
                if (o.optInt("r") == e.round && e.placeBet(seat, o.optInt("n"))) {
                    publish(ctx, s, e)
                    if (openTable != s.id) {
                        notify(ctx, s, "b", ctx.getString(R.string.bjack_title_online), ctx.getString(R.string.bjack_n_bets, e.pendingCount()))
                    }
                } else {
                    // Повтор или опоздала - игроку его вид стола (он сам вернёт себе ставку, если нужно).
                    send(pk, statePacket(s, e, seat))
                }
            }
            "act" -> {
                if (s == null || !s.isDealer || seat <= 0 || seat in s.gone || s.state != "playing") return
                val e = s.engineOrNull() ?: return
                val ok = o.optInt("r") == e.round && e.matches(seat, o.optInt("h", -1), o.optInt("k", -1)) && when (o.optString("a")) {
                    "hit" -> e.hit(seat)
                    "stand" -> e.stand(seat)
                    "dbl" -> e.double(seat, reserveFn(ctx, s, e) { e.current()?.bet ?: 0 })
                    "split" -> e.split(seat, reserveFn(ctx, s, e) { e.current()?.bet ?: 0 })
                    "sur" -> e.surrender(seat).also { if (it) s.chat += "sys:" + ctx.getString(R.string.bjack_surrendered, s.names.getOrElse(seat) { "?" }) }
                    else -> false
                }
                if (!ok && (e.note == "bank" || e.note == "limit")) {
                    s.chat += "sys:" + ctx.getString(if (e.note == "bank") R.string.bjack_bank_short else R.string.bjack_limit_short, s.names.getOrElse(seat) { "?" })
                }
                // И при отказе - свежий стол, чтобы у игрока всё исправилось (доплата вернётся по истории).
                publish(ctx, s, e)
            }
            "need" -> {
                if (s == null || !s.isDealer || seat <= 0 || seat in s.gone) return
                val e = s.engineOrNull() ?: return
                send(pk, statePacket(s, e, seat))
            }
            "ack" -> {
                if (s == null || !s.isDealer || seat <= 0) return
                val k = "$id|$seat"
                acked[k] = maxOf(acked[k] ?: 0, o.optInt("v"))
            }
            "st" -> {
                if (s == null || seat != 0 || s.isDealer) return
                val v = o.optInt("v")
                if (s.state == "declined" || s.state == "closed") return
                if (v < s.version) {
                    send(pk, JSONObject().put("t", "ack").put("id", id).put("v", s.version))
                    return
                }
                val pub = o.optJSONObject("s") ?: return
                // Таймер хода считаем от момента приёма (часы у всех разные).
                runCatching { pub.put("rx", System.currentTimeMillis()) }
                val e = runCatching { BjEngine.fromJson(pub) }.getOrNull() ?: return
                if (e.seats != s.players) return
                val before = s.engineOrNull()
                val wasMyTurn = before != null && before.phase == BjEngine.PLAY && before.active == s.mySeat
                s.version = v
                s.engine = pub
                val acc = ints(o.optJSONArray("acc"))
                if (s.state == "incoming" && o.optString("state") == "playing" && s.mySeat in acc) s.state = "playing"
                s.accepted.clear()
                s.accepted += acc
                reconcile(ctx, s, e)
                save(ctx, s)
                send(pk, JSONObject().put("t", "ack").put("id", id).put("v", v))
                if (s.state == "playing" && openTable != s.id && !wasMyTurn && e.phase == BjEngine.PLAY && e.active == s.mySeat) {
                    notify(ctx, s, "t", ctx.getString(R.string.bjack_title_online), ctx.getString(R.string.bjack_your_turn_notif))
                }
            }
            "here" -> {
                if (s != null && s.state == "playing" && seat >= 0 && seat != s.mySeat) {
                    GameInvite.joined(ctx, pk, GAME, id, 0, s.names.getOrElse(seat) { "" })
                }
            }
            "chat" -> {
                if (s == null || seat < 0 || seat == s.mySeat || seat in s.gone) return
                val x = o.optString("x").replace('\n', ' ').take(300)
                if (x.isBlank()) return
                s.chat += "$seat:$x"
                save(ctx, s)
            }
            "del" -> {
                if (s != null && (seat < 0 || seat == s.mySeat)) return
                send(pk, JSONObject().put("t", "delok").put("id", id))
                if (s == null || seat in s.gone) return
                val who = s.names.getOrElse(seat) { "?" }
                if (seat == 0) {
                    // Дилер закрыл стол: рассчитываемся по его последнему состоянию, остальное - назад.
                    s.gone += 0
                    val pub = o.optJSONObject("s")
                    val e = pub?.let { runCatching { BjEngine.fromJson(it) }.getOrNull() }?.takeIf { it.seats == s.players } ?: s.engineOrNull()
                    if (e != null) {
                        if (pub != null && e.seats == s.players) s.engine = pub
                        reconcile(ctx, s, e, hostGone = true)
                    }
                    if (s.state == "incoming") {
                        delete(ctx, id)
                        changed(s)
                        return
                    }
                    s.state = "closed"
                    s.chat += "sys:" + ctx.getString(R.string.bjack_closed_by, who)
                    save(ctx, s)
                    GameSaveSync.ask(ctx, GAME, id, who)
                } else if (s.isDealer) {
                    // Игрок ушёл из-за стола: его ставка в идущем раунде проиграна, игра идёт дальше.
                    s.gone += seat
                    s.accepted -= seat
                    s.chat += "sys:" + ctx.getString(R.string.gamesave_left, who)
                    val e = s.engineOrNull()
                    if (e != null && s.state == "playing") {
                        val r = e.round
                        val left = e.forfeit(seat)
                        if (left > 0) BjLedger.credit(ctx, BjLedger.forfeitKey(id, r, seat), left, "forfeit ${s.id} r=$r seat=$seat")
                        publish(ctx, s, e)
                    } else {
                        save(ctx, s)
                    }
                }
            }
            "delok" -> GameSaveSync.acked(ctx, GAME, id, pk)
        }
    }

    private fun ints(a: JSONArray?): Set<Int> = HashSet<Int>().apply { if (a != null) for (i in 0 until a.length()) add(a.optInt(i)) }

    /** Открыл стол: остальным - «вернулся за стол». */
    fun here(ctx: Context, s: Save) {
        if (s.state != "playing") return
        broadcast(ctx, s, JSONObject().put("t", "here").put("id", s.id))
    }

    /** Сверить стол: дилер рассылает состояние, игрок просит свежее. */
    fun sync(ctx: Context, s: Save) {
        if (s.state != "playing" && s.state != "incoming") return
        if (s.isDealer) {
            val e = s.engineOrNull() ?: return
            for (i in 1 until s.players) if (i !in s.gone) send(s.seats[i], statePacket(s, e, i))
        } else {
            send(s.host, JSONObject().put("t", "need").put("id", s.id).put("v", s.version))
        }
    }

    /** Друг снова в сети: сверяем столы. */
    fun onPeerOnline(ctx: Context, pk: String) {
        for (s in all(ctx)) {
            if (s.state != "playing" && s.state != "incoming") continue
            if (s.isDealer) {
                val i = s.seats.indexOfFirst { it.equals(pk, true) }
                val e = s.engineOrNull()
                if (i > 0 && i !in s.gone && e != null) send(pk, statePacket(s, e, i))
            } else if (s.host.equals(pk, true)) {
                sync(ctx, s)
            }
        }
    }

    /** Открыть стол из уведомления («bj:<id>»). */
    fun openFromIntent(ctx: Context, id: String) {
        if (!id.matches(ID_RE)) return
        val s = load(ctx, id) ?: return
        main.post { runCatching { BjOnline.open(ctx, s) } }
    }

    private fun notify(ctx: Context, s: Save, tag: String, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "games"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.games_title), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val open = android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(GameNet.ACTION_OPEN_GAME).putExtra(GameNet.EXTRA_GAME, "$GAME:" + s.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val nid = ("bj$tag" + s.id).hashCode()
            val pi = android.app.PendingIntent.getActivity(ctx, nid, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(nid, n)
        }
    }
}
