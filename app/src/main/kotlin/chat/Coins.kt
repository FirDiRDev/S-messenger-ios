// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Монеты 🪙: зарабатываются в мини-игре «Змейка», тратятся на подарки (другу или себе).
 * Цена подарка зависит от редкости: обычные 20-40, редкие 40-80, легендарные 80-120 монет.
 * У каждого подарка цена своя (случайная в пределах редкости), но постоянная - не скачет.
 */
object Coins {
    private const val PREFS = "coins"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    /**
     * Статистика игр или витрина достижений поменялась: профиль перерисует плитки, а друзья
     * в сети получат свежую карточку (через тех же слушателей, что и для монет).
     */
    fun notifyChanged() = changed()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Баланс. У аккаунта разработчика монеты не кончаются (см. [DevAccount]). */
    fun balance(ctx: Context): Int = if (DevAccount.isMe(ctx)) DevAccount.DEV_BALANCE else prefs(ctx).getInt("balance", 0)

    @Synchronized
    fun add(ctx: Context, n: Int) {
        if (n <= 0) return
        if (DevAccount.isMe(ctx)) {
            changed()
            return
        }
        prefs(ctx).edit().putInt("balance", balance(ctx) + n).putInt("earned", prefs(ctx).getInt("earned", 0) + n).apply()
        changed()
    }

    /** Списать [n] монет. false - не хватает. */
    @Synchronized
    fun spend(ctx: Context, n: Int): Boolean {
        // Разработчик: все покупки бесплатны, подарки и монеты - без ограничений.
        if (DevAccount.isMe(ctx)) {
            if (n < 0) return false
            changed()
            return true
        }
        val b = balance(ctx)
        if (n > b) return false
        prefs(ctx).edit().putInt("balance", b - n).apply()
        changed()
        return true
    }

    // ---------------- покупки за рубли (чеки сервера) ----------------

    private const val CREDITED = "purchase_receipts"
    private const val UNSEEN = "purchase_unseen"

    /** Чек [receiptId] уже зачислен (монеты за него уже в балансе). */
    fun receiptCredited(ctx: Context, receiptId: String): Boolean =
        prefs(ctx).getStringSet(CREDITED, null)?.contains(receiptId) == true

    /**
     * Зачислить [n] монет за чек [receiptId] ровно один раз. Номер чека, новый баланс и
     * «ещё не показанное зачисление» пишутся ОДНИМ commit() - файл настроек заменяется
     * целиком, поэтому после сбоя есть либо всё сразу, либо ничего: монеты не теряются
     * и не зачисляются дважды. true - зачислено сейчас, false - этот чек уже был зачислен.
     */
    @Synchronized
    fun creditReceipt(ctx: Context, receiptId: String, n: Int): Boolean {
        if (n <= 0 || receiptId.isBlank()) return false
        val p = prefs(ctx)
        val ids = p.getStringSet(CREDITED, null).orEmpty()
        if (receiptId in ids) return false
        val e = p.edit()
            .putStringSet(CREDITED, HashSet(ids).apply { add(receiptId) })
            .putInt(UNSEEN, p.getInt(UNSEEN, 0) + n)
        // У разработчика баланс бесконечный - только отмечаем чек.
        if (!DevAccount.isMe(ctx)) e.putInt("balance", p.getInt("balance", 0) + n).putInt("earned", p.getInt("earned", 0) + n)
        e.commit()
        changed()
        return true
    }

    /** Сколько куплено монет, о которых ещё не показали «+N» (зачислены в фоне); обнуляет счётчик. */
    @Synchronized
    fun takeUnseenPurchase(ctx: Context): Int {
        val p = prefs(ctx)
        val n = p.getInt(UNSEEN, 0)
        if (n != 0) p.edit().putInt(UNSEEN, 0).apply()
        return n.coerceAtLeast(0)
    }

    /** Цена подарка в монетах (постоянная для каждого подарка, случайная в пределах редкости). */
    fun price(g: GiftCatalog.Gift): Int {
        val (lo, hi) = when (g.rarity) {
            // Цены ещё ×2 (с 4.9.8): обычные 40-80, редкие 80-160, легендарные 160-240 монет.
            GiftCatalog.Rarity.COMMON -> 40 to 80
            GiftCatalog.Rarity.RARE -> 80 to 160
            GiftCatalog.Rarity.LEGENDARY -> 160 to 240
        }
        // Перемешиваем номер подарка - соседние подарки получают разные цены.
        var x = g.id * 2654435761L
        x = x xor (x ushr 15)
        return lo + ((x and 0x7FFFFFFF) % (hi - lo + 1)).toInt()
    }

    /** Рекорд «Змейки». */
    fun best(ctx: Context): Int = prefs(ctx).getInt("snake_best", 0)

    fun setBest(ctx: Context, v: Int) {
        if (v > best(ctx)) {
            prefs(ctx).edit().putInt("snake_best", v).apply()
            changed()
        }
    }

    // ---------------- ежедневная награда ----------------
    // День 1 - 1 монета, день 2 - 2, … день 7 - 7; потом цикл заново. Пропустили день - снова с 1.

    private fun today(): Long {
        val c = java.util.Calendar.getInstance()
        return (c.timeInMillis + c.get(java.util.Calendar.ZONE_OFFSET) + c.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
    }

    /** Какой день серии будет при следующем получении (1-7). */
    fun dailyNextDay(ctx: Context): Int {
        val p = prefs(ctx)
        val last = p.getLong("daily_last", -10L)
        val streak = p.getInt("daily_streak", 0)
        return when (today() - last) {
            0L -> streak.coerceIn(1, 7)
            1L -> if (streak >= 7) 1 else streak + 1
            else -> 1
        }
    }

    /** Награду сегодня ещё можно забрать. */
    fun dailyAvailable(ctx: Context): Boolean = prefs(ctx).getLong("daily_last", -10L) != today()

    /** Забрать ежедневную награду; возвращает сколько монет начислено (0 - уже получена сегодня). */
    fun claimDaily(ctx: Context): Int {
        if (!dailyAvailable(ctx)) return 0
        val day = dailyNextDay(ctx)
        prefs(ctx).edit().putLong("daily_last", today()).putInt("daily_streak", day).apply()
        add(ctx, day)
        return day
    }

    /** Текущая серия (сколько дней подряд), для подписи. */
    fun dailyStreak(ctx: Context): Int = prefs(ctx).getInt("daily_streak", 0)

    // ---------------- сохранение игры ----------------

    fun saveGame(ctx: Context, json: String?) {
        // commit: сохранение игры пишется на диск сразу (приложение могут выгрузить в фоне).
        prefs(ctx).edit().apply { if (json == null) remove("snake_save") else putString("snake_save", json) }.commit()
    }

    fun loadGame(ctx: Context): String? = prefs(ctx).getString("snake_save", null)

    fun bestLevel(ctx: Context): Int = prefs(ctx).getInt("snake_best_level", 1)

    fun setBestLevel(ctx: Context, v: Int) {
        if (v > bestLevel(ctx)) prefs(ctx).edit().putInt("snake_best_level", v).apply()
    }

    // ---------------- видимость для друзей ----------------

    /** Показывать друзьям монеты и рекорды (по умолчанию - да). */
    fun shareStats(ctx: Context): Boolean = prefs(ctx).getBoolean("share_stats", true)

    fun setShareStats(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("share_stats", v).apply()
        changed()
    }

    fun chessWins(ctx: Context): Int = prefs(ctx).getInt("chess_wins", 0)

    fun addChessWin(ctx: Context) {
        prefs(ctx).edit().putInt("chess_wins", chessWins(ctx) + 1).apply()
        changed()
    }

    /**
     * Строка рекордов для карточки профиля: «s:рекорд:уровень» (Змейка), «c:победы» (Шахматы),
     * «d:победы» (Дурак), «m:очки:уровней:звёзд» (Три в ряд), «r:рекорд» (Бег по рельсам).
     * Старые версии просто не видят новые поля.
     */
    fun statsLine(ctx: Context): String {
        val m3 = runCatching {
            ";m:${com.stoyko.smessenger.games.Match3Game.bestScore(ctx)}:${com.stoyko.smessenger.games.Match3Game.unlocked(ctx)}:${com.stoyko.smessenger.games.Match3Game.totalStars(ctx)}"
        }.getOrDefault("")
        val run = runCatching { ";r:${com.stoyko.smessenger.games.RunnerGame.best(ctx)}" }.getOrDefault("")
        // 4.7.0: дальше - все счётчики игр и витрина достижений («;gp:…;a:…», см. GameStats.line).
        val more = runCatching { com.stoyko.smessenger.games.stats.GameStats.line(ctx) }.getOrDefault("")
        return "s:${best(ctx)}:${bestLevel(ctx)};c:${chessWins(ctx)};d:${durakWins(ctx)}$m3$run$more"
    }

    fun durakWins(ctx: Context): Int = prefs(ctx).getInt("durak_wins", 0)

    fun addDurakWin(ctx: Context) {
        prefs(ctx).edit().putInt("durak_wins", durakWins(ctx) + 1).apply()
        changed()
    }

    /** Перевести [n] монет другу: списываются у меня; false - не хватает. */
    fun giveTo(ctx: Context, pk: String, n: Int, note: String = ""): Boolean = CoinGift.send(ctx, pk, n, note)
}
