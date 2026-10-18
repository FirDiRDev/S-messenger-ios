// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.stats

import android.content.Context
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins

/**
 * Статистика всех игр в одном месте: победы, поражения, ничьи, сыгранные партии и рекорды.
 *
 * Что уже хранилось раньше, берётся оттуда: рекорд и уровень Змейки, победы в шахматах и
 * «Дураке» - в [Coins]; раздачи, победы и лучший выигрыш блэкджека - в его настройках;
 * уровни, звёзды и рекорд «Три в ряд»; рекорд «Бега по рельсам». Недостающие счётчики
 * (поражения, ничьи, партии с ботом, сыгранные забеги, дальность…) копятся здесь - с 4.7.0.
 *
 * Друзьям статистика уходит в карточке профиля строкой рекордов ([line]) - формат обратно
 * совместим: прежние поля «s:…;c:…;d:…;m:…;r:…» остаются первыми и без изменений, новые
 * («gp:» - счётчики через запятую, «a:» - витрина) идут после них. Старые версии их не видят.
 * Ключи новых полей не оканчиваются на s/c/d/m/r - старые регулярки их не спутают.
 */
object GameStats {
    const val SNAKE = "snake"
    const val CHESS = "chess"
    const val CHESS_BOT = "chessbot"
    const val DURAK = "durak"
    const val BLACKJACK = "bj"
    const val MATCH3 = "m3"
    const val RUNNER = "runner"

    const val WIN = 1
    const val DRAW = 0
    const val LOSS = -1

    /** Партия сыграна, без победителя (Змейка, забег). */
    const val PLAYED = 2

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("game_stats", Context.MODE_PRIVATE)

    /** Итог партии: [outcome] - [WIN], [DRAW], [LOSS] или [PLAYED]. */
    @Synchronized
    fun result(ctx: Context, game: String, outcome: Int) {
        runCatching {
            val p = prefs(ctx)
            val e = p.edit().putInt("${game}_n", p.getInt("${game}_n", 0) + 1)
            when (outcome) {
                WIN -> e.putInt("${game}_w", p.getInt("${game}_w", 0) + 1)
                DRAW -> e.putInt("${game}_d", p.getInt("${game}_d", 0) + 1)
                LOSS -> e.putInt("${game}_l", p.getInt("${game}_l", 0) + 1)
            }
            e.apply()
            Coins.notifyChanged()
        }
    }

    /** Запомнить рекорд (только если больше прежнего). */
    @Synchronized
    private fun best(ctx: Context, key: String, v: Int) {
        val p = prefs(ctx)
        if (v > p.getInt(key, 0)) p.edit().putInt(key, v).apply()
    }

    // ---------------- точки вызова из игр (по одной строке в каждой) ----------------

    fun snakeOver(ctx: Context, score: Int, level: Int) {
        best(ctx, "snake_best_run", score)
        result(ctx, SNAKE, PLAYED)
    }

    /** Партия с ботом в шахматах закончилась: 1 - победа, 0 - ничья, -1 - поражение. */
    fun chessBotEnd(ctx: Context, outcome: Int) = result(ctx, CHESS_BOT, outcome.coerceIn(-1, 1))

    /** Сетевая партия в шахматы: 1 - победа, 0 - ничья, -1 - поражение. */
    fun chessNet(ctx: Context, outcome: Int) = result(ctx, CHESS, outcome.coerceIn(-1, 1))

    /** «Дурак» (онлайн и с ботами): победа (вышел), ничья или поражение (остался дураком). */
    fun durak(ctx: Context, outcome: Int) = result(ctx, DURAK, outcome.coerceIn(-1, 1))

    /** Блэкджек: итог раздачи - 1 победа, 0 ничья, -1 проигрыш (для онлайн-стола и стола с дилером). */
    fun blackjack(ctx: Context, outcome: Int) = result(ctx, BLACKJACK, outcome.coerceIn(-1, 1))

    /** «Три в ряд»: уровень пройден / ходы кончились. */
    fun match3(ctx: Context, won: Boolean) = result(ctx, MATCH3, if (won) WIN else LOSS)

    /** Забег окончен; [continued] - конец уже продолженного забега (второй раз не считаем). */
    fun runnerOver(ctx: Context, dist: Int, continued: Boolean) {
        best(ctx, "runner_dist", dist)
        if (!continued) result(ctx, RUNNER, PLAYED) else Coins.notifyChanged()
    }

    // ---------------- снимок ----------------

    /** Вся статистика (моя - из настроек, друга - из его строки рекордов). -1 - неизвестно. */
    class Snapshot(
        val coins: Int = -1,
        val snakeBest: Int = -1,
        val snakeLevel: Int = -1,
        val snakeGames: Int = -1,
        val chessW: Int = -1,
        val chessL: Int = -1,
        val chessD: Int = -1,
        val botW: Int = -1,
        val botL: Int = -1,
        val botD: Int = -1,
        val durakW: Int = -1,
        val durakL: Int = -1,
        val durakD: Int = -1,
        val bjPlayed: Int = -1,
        val bjWins: Int = -1,
        val bjLosses: Int = -1,
        val bjPushes: Int = -1,
        val bjBest: Int = -1,
        val m3Level: Int = -1,
        val m3Stars: Int = -1,
        val m3Best: Int = -1,
        val m3Played: Int = -1,
        val m3Wins: Int = -1,
        val runBest: Int = -1,
        val runDist: Int = -1,
        val runRuns: Int = -1,
        /** Витрина достижений (null - старая версия приложения у друга). */
        val showcase: List<String>? = null,
    ) {
        private fun pos(vararg v: Int) = v.filter { it > 0 }.sum()

        /** Победы во всех играх с соперником. */
        val totalWins get() = pos(chessW, botW, durakW, bjWins, m3Wins)

        /** Сыграно всего (партии, раздачи, уровни, забеги). */
        val totalGames get() = pos(snakeGames, chessW, chessL, chessD, botW, botL, botD, durakW, durakL, durakD, bjPlayed, m3Played, runRuns)

        /** Победы / ничьи / поражения по играм с соперником - для общего кольца. */
        val totalLosses get() = pos(chessL, botL, durakL, bjLosses) + (if (m3Played > 0 && m3Wins >= 0) (m3Played - m3Wins).coerceAtLeast(0) else 0)
        val totalDraws get() = pos(chessD, botD, durakD, bjPushes)
    }

    /** Моя статистика целиком. */
    fun mine(ctx: Context): Snapshot {
        val p = prefs(ctx)
        val app = ctx.applicationContext
        val bj = app.getSharedPreferences("blackjack", Context.MODE_PRIVATE)
        val unlocked = runCatching { com.stoyko.smessenger.games.Match3Game.unlocked(ctx) }.getOrDefault(1)
        val bjPlayed = maxOf(bj.getInt("played", 0), p.getInt("${BLACKJACK}_n", 0))
        val bjWins = maxOf(bj.getInt("wins", 0), p.getInt("${BLACKJACK}_w", 0))
        val bjPushes = p.getInt("${BLACKJACK}_d", 0)
        return Snapshot(
            coins = Coins.balance(ctx),
            snakeBest = Coins.best(ctx),
            snakeLevel = Coins.bestLevel(ctx),
            snakeGames = p.getInt("${SNAKE}_n", 0),
            chessW = Coins.chessWins(ctx),
            chessL = p.getInt("${CHESS}_l", 0),
            chessD = p.getInt("${CHESS}_d", 0),
            botW = p.getInt("${CHESS_BOT}_w", 0),
            botL = p.getInt("${CHESS_BOT}_l", 0),
            botD = p.getInt("${CHESS_BOT}_d", 0),
            durakW = maxOf(Coins.durakWins(ctx), p.getInt("${DURAK}_w", 0)),
            durakL = p.getInt("${DURAK}_l", 0),
            durakD = p.getInt("${DURAK}_d", 0),
            bjPlayed = bjPlayed,
            bjWins = bjWins,
            // Стол с дилером раньше считал только раздачи и победы - остальное считаем проигрышами.
            bjLosses = maxOf(p.getInt("${BLACKJACK}_l", 0), bjPlayed - bjWins - bjPushes).coerceAtLeast(0),
            bjPushes = bjPushes,
            bjBest = bj.getInt("best", 0),
            // Пройдено уровней: открытый минус один.
            m3Level = (unlocked - 1).coerceAtLeast(0),
            m3Stars = runCatching { com.stoyko.smessenger.games.Match3Game.totalStars(ctx) }.getOrDefault(0),
            m3Best = runCatching { com.stoyko.smessenger.games.Match3Game.bestScore(ctx) }.getOrDefault(0),
            m3Played = p.getInt("${MATCH3}_n", 0),
            m3Wins = p.getInt("${MATCH3}_w", 0),
            runBest = runCatching { com.stoyko.smessenger.games.RunnerGame.best(ctx) }.getOrDefault(0),
            runDist = p.getInt("runner_dist", 0),
            runRuns = p.getInt("${RUNNER}_n", 0),
            showcase = showcase(ctx),
        )
    }

    // ---------------- строка для друзей ----------------

    /** Новые поля строки рекордов (дописываются после прежних «s:…;c:…;d:…;m:…;r:…»). */
    fun line(ctx: Context): String {
        val s = mine(ctx)
        val gp = intArrayOf(
            s.snakeGames, s.chessL, s.chessD, s.botW, s.botL, s.botD, s.durakL, s.durakD,
            s.bjPlayed, s.bjWins, s.bjLosses, s.bjPushes, s.bjBest, s.m3Played, s.m3Wins, s.runRuns, s.runDist,
        ).joinToString(",") { it.coerceAtLeast(0).toString() }
        return ";gp:$gp;a:" + showcase(ctx).joinToString(",")
    }

    /** Разобрать строку рекордов друга (любой версии). [coins] - монеты из карточки (-1 - скрыты). */
    fun parse(line: String, coins: Int): Snapshot {
        val t = HashMap<String, List<Int>>()
        var ach: List<String>? = null
        for (part in line.split(';')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val key = part.substring(0, i).trim()
            val rest = part.substring(i + 1)
            if (key == "a") {
                ach = rest.split(',').map { it.trim() }.filter { it in IDS }.distinct().take(MAX_SHOWCASE)
                continue
            }
            if (key !in t) t[key] = rest.split(':', ',').map { it.trim().toIntOrNull()?.coerceAtLeast(-1) ?: -1 }
        }
        fun v(key: String, idx: Int) = t[key]?.getOrNull(idx) ?: -1
        val m3Unlocked = v("m", 1)
        return Snapshot(
            coins = coins,
            snakeBest = v("s", 0), snakeLevel = v("s", 1), snakeGames = v("gp", 0),
            chessW = v("c", 0), chessL = v("gp", 1), chessD = v("gp", 2),
            botW = v("gp", 3), botL = v("gp", 4), botD = v("gp", 5),
            durakW = v("d", 0), durakL = v("gp", 6), durakD = v("gp", 7),
            bjPlayed = v("gp", 8), bjWins = v("gp", 9), bjLosses = v("gp", 10), bjPushes = v("gp", 11), bjBest = v("gp", 12),
            m3Level = if (m3Unlocked > 0) m3Unlocked - 1 else -1, m3Stars = v("m", 2), m3Best = v("m", 0),
            m3Played = v("gp", 13), m3Wins = v("gp", 14),
            runBest = v("r", 0), runDist = v("gp", 16), runRuns = v("gp", 15),
            showcase = ach,
        )
    }

    // ---------------- достижения и витрина ----------------

    /** Достижение для витрины: значок, число и подпись (как плитки монет в профиле). */
    class Ach(val id: String, val icon: String, val label: Int, val value: (Snapshot) -> Int)

    val ALL = listOf(
        Ach("sn", "🐍", R.string.gamesb_ach_snake) { it.snakeBest },
        Ach("ch", "♞", R.string.stat_chess) { it.chessW },
        Ach("cb", "🤖", R.string.gamesb_ach_bot) { it.botW },
        Ach("du", "🃏", R.string.stat_durak) { it.durakW },
        Ach("bj", "🂡", R.string.gamesb_ach_bj) { it.bjWins },
        Ach("bjb", "💰", R.string.gamesb_ach_bjbest) { it.bjBest },
        Ach("m3l", "💎", R.string.gamesb_ach_m3level) { it.m3Level },
        Ach("m3s", "⭐", R.string.gamesb_ach_m3stars) { it.m3Stars },
        Ach("m3p", "🏆", R.string.gamesb_ach_m3best) { it.m3Best },
        Ach("rnb", "🚂", R.string.gamesb_ach_runbest) { it.runBest },
        Ach("rnd", "🛤", R.string.gamesb_ach_rundist) { it.runDist },
        Ach("tw", "🏅", R.string.gamesb_ach_total_wins) { it.totalWins },
        Ach("tg", "🎮", R.string.gamesb_ach_total_games) { it.totalGames },
    )
    private val IDS = ALL.map { it.id }.toSet()

    const val MAX_SHOWCASE = 6

    /** Витрина по умолчанию (пока человек не выбрал сам). */
    private val DEFAULT = listOf("sn", "ch", "du", "m3l", "rnb", "tw")

    fun showcase(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("showcase", null) ?: return DEFAULT
        return raw.split(',').filter { it in IDS }.distinct().take(MAX_SHOWCASE)
    }

    /** Сохранить витрину; друзья в сети получат свежую карточку (через слушателя монет в GameNet). */
    fun setShowcase(ctx: Context, ids: List<String>) {
        prefs(ctx).edit().putString("showcase", ids.filter { it in IDS }.distinct().take(MAX_SHOWCASE).joinToString(",")).apply()
        Coins.notifyChanged()
    }

    /** Число для плитки: до 100 000 - как есть, дальше - «123K», «1,2M». */
    fun compact(v: Int): String = when {
        v < 0 -> "—"
        v < 100_000 -> v.toString()
        v < 1_000_000 -> "${v / 1000}K"
        else -> "%.1fM".format(java.util.Locale.ROOT, v / 1_000_000.0).replace('.', ',')
    }

    /** Подпись достижения (у Змейки - с уровнем). */
    fun label(ctx: Context, a: Ach, s: Snapshot): String =
        if (a.id == "sn" && s.snakeLevel > 0) ctx.getString(R.string.stat_snake, s.snakeLevel) else ctx.getString(a.label)

    /** Плитки витрины: значок, число, подпись. */
    fun tiles(ctx: Context, s: Snapshot, ids: List<String>): List<com.stoyko.smessenger.ui.StatChips.Item> =
        ids.mapNotNull { id -> ALL.firstOrNull { it.id == id } }.map { a ->
            com.stoyko.smessenger.ui.StatChips.Item(a.icon, compact(a.value(s)), label(ctx, a, s))
        }
}
