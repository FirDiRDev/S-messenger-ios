// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.content.Context
import com.stoyko.smessenger.chat.Coins

/**
 * Доска (ховерборд) из магазина: цвет деки, светящаяся полоса, колёса и эффект из-под колёс.
 * [fx] - эффект: 0 нет, 1 искры-звёзды, 2 пламя, 3 иней, 4 неон (как следы обликов).
 */
internal class BoardSkin(
    val ru: String,
    val en: String,
    basePrice: Int,
    val deck: Int,
    val stripe: Int,
    val wheel: Int,
    val wheelGlow: Boolean,
    val fx: Int,
) {
    /** Цена ×2 (с 4.9.8). */
    val price: Int = basePrice * 2
    val title: String get() = if (java.util.Locale.getDefault().language == "ru") ru else en
}

internal object RunnerBoards {
    private fun c(v: Long) = v.toInt()

    val all: List<BoardSkin> = listOf(
        BoardSkin("В цвет облика", "Matches the outfit", 0, 0, 0, c(0xFF3A3F47), false, 0),
        BoardSkin("Уличная классика", "Street classic", 120, c(0xFF1F2937), c(0xFFFDE047), c(0xFFF8FAFC), false, 0),
        BoardSkin("Неоновый дрифт", "Neon drift", 260, c(0xFF0F172A), c(0xFF22D3EE), c(0xFFA78BFA), true, 4),
        BoardSkin("Огненный вихрь", "Fire storm", 420, c(0xFF7F1D1D), c(0xFFFF7A1A), c(0xFFFDE047), true, 2),
        BoardSkin("Ледяной гребень", "Ice ridge", 420, c(0xFFE0F2FE), c(0xFF38BDF8), c(0xFFBFE9FF), true, 3),
        BoardSkin("Звёздная пыль", "Stardust", 600, c(0xFF312E81), c(0xFFF0ABFC), c(0xFFFDE047), true, 1),
        BoardSkin("Хром", "Chrome", 800, c(0xFF9CA3AF), c(0xFFF8FAFC), c(0xFFE5E7EB), false, 0),
        BoardSkin("Золотой рекорд", "Golden record", 1200, c(0xFFB45309), c(0xFFFDE047), c(0xFFFFD84D), true, 1),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)

    fun owned(ctx: Context): Set<Int> = prefs(ctx).getString("boards_owned", "0").orEmpty()
        .split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in all.indices }.toMutableSet().apply { add(0) }

    fun isOwned(ctx: Context, i: Int) = i == 0 || i in owned(ctx)

    fun equipped(ctx: Context): Int = prefs(ctx).getInt("board", 0).takeIf { it in all.indices && isOwned(ctx, it) } ?: 0

    fun equip(ctx: Context, i: Int) {
        if (isOwned(ctx, i)) prefs(ctx).edit().putInt("board", i).apply()
    }

    fun buy(ctx: Context, i: Int): Boolean {
        if (i !in all.indices) return false
        if (isOwned(ctx, i)) return true
        if (!Coins.spend(ctx, all[i].price)) return false
        val set = owned(ctx).toMutableSet().apply { add(i) }
        prefs(ctx).edit().putString("boards_owned", set.sorted().joinToString(",")).putInt("board", i).apply()
        return true
    }
}

/**
 * Улучшения бонусов: сколько секунд работают реактивный ранец, доска и магнит.
 * 5 уровней у каждого; цена растёт с уровнем (монеты мессенджера).
 */
internal object RunnerUpgrades {
    class Up(val power: Int, val emoji: String, val ru: String, val en: String, val step: Float) {
        val title: String get() = if (java.util.Locale.getDefault().language == "ru") ru else en
    }

    const val MAX = 5
    val PRICES = intArrayOf(120, 240, 440, 720, 1100)

    val all = listOf(
        Up(World.P_JET, "🚀", "Реактивный ранец", "Jetpack", 1.5f),
        Up(World.P_BOARD, "🛹", "Доска", "Hoverboard", 5f),
        Up(World.P_MAGNET, "🧲", "Магнит", "Magnet", 2.5f),
        Up(World.P_SNEAKERS, "👟", "Кеды-пружины", "Spring sneakers", 2.5f),
        Up(World.P_X2, "✖2", "Очки ×2", "Score ×2", 3f),
        Up(World.P_SHIELD, "🛡", "Щит", "Shield", 3f),
        Up(World.P_COINS, "💰", "Монеты ×2", "Coins ×2", 2.5f),
        Up(World.P_SLOW, "⏳", "Замедление", "Slow motion", 1.5f),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)

    fun level(ctx: Context, power: Int): Int = prefs(ctx).getInt("up_$power", 0).coerceIn(0, MAX)

    /** Длительность бонуса с улучшениями (секунды). */
    fun time(ctx: Context, power: Int): Float {
        val base = World.P_TIME.getOrElse(power) { 10f }
        val up = all.firstOrNull { it.power == power } ?: return base
        return base + up.step * level(ctx, power)
    }

    fun price(ctx: Context, power: Int): Int? = level(ctx, power).let { if (it >= MAX) null else PRICES[it] }

    fun buy(ctx: Context, power: Int): Boolean {
        val p = price(ctx, power) ?: return false
        if (!Coins.spend(ctx, p)) return false
        prefs(ctx).edit().putInt("up_$power", level(ctx, power) + 1).apply()
        return true
    }

    /** Все длительности (для мира перед забегом). */
    fun times(ctx: Context): FloatArray = FloatArray(World.P_TIME.size) { time(ctx, it) }
}

/** Покупка легендарных обликов: за монеты, а не хватает - докупить монеты за рубли. */
internal object RunnerShop {
    /** Не хватает [missing] монет на [what] - предложить пак монет за рубли (ЮMoney) в магазине. */
    fun offerCoins(ctx: Context, missing: Int, what: String) {
        com.stoyko.smessenger.ui.DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("👑 $what")
                .setMessage(
                    com.stoyko.smessenger.ai.t(
                        "Легендарный облик стоит дорого: не хватает 🪙 $missing.\n\nМонеты можно заработать в играх или купить за рубли в магазине - после оплаты они сразу придут на счёт, и облик можно будет взять.",
                        "Legendary outfits are expensive: you need 🪙 $missing more.\n\nEarn coins in games or buy them for rubles in the shop - they arrive right after payment.",
                    ),
                )
                .setPositiveButton(com.stoyko.smessenger.ai.t("💳 Купить монеты за ₽", "💳 Buy coins")) { _, _ ->
                    runCatching { com.stoyko.smessenger.chat.CoinShop.show(ctx) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }
}
