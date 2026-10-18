// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import android.content.Context
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.chat.DevAccount

/**
 * Касса онлайн-блэкджека: зачисления по итогам раундов и лимиты ставок.
 *
 * Монеты за столом не «пересылаются» пакетами: каждый телефон по состоянию стола от дилера
 * сам зачисляет СЕБЕ своё - игрок возврат по своим рукам, дилер банк + проигранные ставки
 * минус выплаты. Поэтому это отдельный игровой путь расчёта, а не перевод монет: дневные
 * лимиты входящих переводов (500 в день от человека / 2000 всего) его не касаются. Вместо
 * них - свои границы:
 *  - игрок получает не больше, чем позволяют правила для его НАСТОЯЩЕЙ (списанной) ставки
 *    (блэкджек 6:5 → ставка × 2,2);
 *  - дилер получает только из банка, который сам зарезервировал, и ставок, которые принял;
 *  - оборот ставок с одним человеком - не больше [PER_PEER_DAY] в день, всего -
 *    [TOTAL_DAY] (защита от «накачки» монет самодельными сборками через стол).
 *
 * Каждое зачисление - ровно один раз: ключ «стол / раунд / место» записывается на диск
 * (commit) ДО того, как монеты добавляются. Сбой между шагами может только потерять
 * зачисление, но никогда - удвоить его; повторные пакеты ничего не начисляют.
 */
object BjLedger {
    private const val PREFS = "bj_ledger"
    private const val LIMITS = "bj_limits"
    private const val LOG = "bj_online_log"

    /** Оборот ставок с одним человеком в день. */
    const val PER_PEER_DAY = 10_000

    /** Оборот ставок со всеми в день. */
    const val TOTAL_DAY = 40_000

    /** Ключи зачислений храним полгода (столько не живёт ни один неподтверждённый раунд). */
    private const val KEEP_MS = 180L * 24 * 3600 * 1000

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playerKey(id: String, round: Int, seat: Int) = "p_${id}_${round}_$seat"
    fun dealerKey(id: String, round: Int) = "d_${id}_$round"
    fun forfeitKey(id: String, round: Int, seat: Int) = "f_${id}_${round}_$seat"

    fun credited(ctx: Context, key: String): Boolean = prefs(ctx).contains(key)

    /**
     * Зачислить [n] монет по ключу [key] ровно один раз. true - зачислено сейчас,
     * false - уже было (или n ≤ 0: тогда ключ всё равно отмечается).
     */
    @Synchronized
    fun credit(ctx: Context, key: String, n: Int, why: String): Boolean {
        val p = prefs(ctx)
        if (p.contains(key)) return false
        // Сначала запись «зачтено» на диск, потом монеты.
        val ok = p.edit().putLong(key, System.currentTimeMillis()).commit()
        if (!ok) return false
        if (n > 0) Coins.add(ctx, n)
        log(ctx, "$why +$n [$key]")
        prune(ctx)
        return n > 0
    }

    private var pruned = 0L

    private fun prune(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - pruned < 3600_000L) return
        pruned = now
        runCatching {
            val p = prefs(ctx)
            val old = p.all.filter { (_, v) -> (v as? Long)?.let { now - it > KEEP_MS } == true }.keys
            if (old.isNotEmpty()) p.edit().apply { old.forEach { remove(it) } }.apply()
        }
    }

    // ---------------- лимиты оборота ----------------

    private fun today(): Long {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun limits(ctx: Context): android.content.SharedPreferences {
        val p = ctx.applicationContext.getSharedPreferences(LIMITS, Context.MODE_PRIVATE)
        val day = today()
        if (p.getLong("day", -1L) != day) p.edit().clear().putLong("day", day).commit()
        return p
    }

    private fun exempt(ctx: Context, pk: String) = DevAccount.isDev(pk) || DevAccount.isMe(ctx)

    /** Можно ли сегодня поставить / принять ещё [n] монет с человеком [pk]. */
    @Synchronized
    fun canVolume(ctx: Context, pk: String, n: Int): Boolean {
        if (n <= 0 || exempt(ctx, pk)) return true
        val p = limits(ctx)
        return p.getInt("s_" + pk.take(64).uppercase(), 0) + n <= PER_PEER_DAY && p.getInt("total", 0) + n <= TOTAL_DAY
    }

    /** Учесть оборот [n] с человеком [pk]. */
    @Synchronized
    fun addVolume(ctx: Context, pk: String, n: Int) {
        if (n <= 0 || exempt(ctx, pk)) return
        val p = limits(ctx)
        val k = "s_" + pk.take(64).uppercase()
        p.edit().putInt(k, p.getInt(k, 0) + n).putInt("total", p.getInt("total", 0) + n).apply()
    }

    // ---------------- журнал ----------------

    /** Журнал расчётов (последние 150 строк) - чтобы можно было разобраться, если что. */
    fun log(ctx: Context, line: String) {
        runCatching {
            val p = ctx.applicationContext.getSharedPreferences(LOG, Context.MODE_PRIVATE)
            val old = p.getString("log", "").orEmpty().split('\n').filter { it.isNotBlank() }.takeLast(149)
            p.edit().putString("log", (old + "${System.currentTimeMillis()} $line").joinToString("\n")).apply()
        }
    }
}
