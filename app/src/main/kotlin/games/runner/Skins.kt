// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.content.Context
import com.stoyko.smessenger.R

/**
 * Облик бегуна. Цвета - ARGB. [hair] - причёска (см. [Rig.hair]), [hat] - головной убор
 * ([Rig.hats], 0 - нет), [extra] - аксессуар ([Rig.extras]). [shorts] - шорты (голени
 * открыты), [sleeves] - длинные рукава. [glow] - светящиеся детали (0 - нет), [trail] - след
 * за бегуном: 0 нет, 1 искры-звёзды, 2 пламя, 3 иней, 4 неон.
 */
internal class Skin(
    val name: Int,
    val desc: Int,
    basePrice: Int,
    val skin: Int,
    val hairColor: Int,
    val hair: Int,
    val top: Int,
    val accent: Int,
    val pants: Int,
    val shoes: Int,
    val sole: Int,
    val hat: Int = 0,
    val hatColor: Int = 0,
    val extra: Int = 0,
    val extraColor: Int = 0,
    val shorts: Boolean = false,
    val sleeves: Boolean = true,
    val glow: Int = 0,
    val trail: Int = 0,
    val board: Int = accent,
    /** Эксклюзив магазина (4.10.0): купить можно только в магазине монет мессенджера. */
    val shopOnly: Boolean = false,
    // ---- 4.12.0: персонажи ----
    /** Телосложение: ширина плеч/корпуса и рост (1 - обычный бегун). */
    val bulk: Float = 1f,
    val tall: Float = 1f,
    /** Размер головы (больше 1 - «мультяшная» большая голова). */
    val headK: Float = 1f,
    /** Части персонажа - набор флагов [Parts]. */
    val parts: Int = 0,
    val partColor: Int = 0,
    val part2Color: Int = 0,
    /** Способность в забеге (см. [Perks]). */
    val perk: Int = 0,
    /** Легендарный облик: аура, дорогой, способность сильнее. */
    val legendary: Boolean = false,
    /** Цвет ауры легендарного облика. */
    val aura: Int = 0,
) {
    /** Цена ×2 (с 4.9.8 - все цены в монетах удвоены). */
    val price: Int = basePrice * 2
    val special get() = glow != 0 || trail != 0 || legendary
    /** Персонаж (своё тело/части/способность), а не просто перекраска. */
    val hero get() = parts != 0 || perk != 0
}

/** Части персонажа (флаги [Skin.parts]). */
internal object Parts {
    const val EARS = 1
    const val HORNS = 2
    const val HALO = 4
    const val CROWN = 8
    const val ANTENNA = 16
    const val CAPE = 32
    const val WINGS = 64
    const val TAIL = 128
    const val BUSHY = 256
    const val PADS = 512
}

/** Способности персонажей в забеге. */
internal object Perks {
    const val NONE = 0
    const val JUMP = 1
    const val SHIELD = 2
    const val COINS = 3
    const val LUCK = 4
    const val MAGNET = 5
    const val X2START = 6
    const val CHASE = 7
    const val REVIVE = 8
    const val DRAGON = 9
    const val LORD = 10
    const val TIME = 11

    fun text(perk: Int): String = when (perk) {
        JUMP -> com.stoyko.smessenger.ai.t("🦘 Прыгает на 20% выше", "🦘 Jumps 20% higher")
        SHIELD -> com.stoyko.smessenger.ai.t("🛡 Начинает забег со щитом", "🛡 Starts every run with a shield")
        COINS -> com.stoyko.smessenger.ai.t("🪙 +25% монет", "🪙 +25% coins")
        LUCK -> com.stoyko.smessenger.ai.t("⏳ Бонусы действуют на 30% дольше", "⏳ Power-ups last 30% longer")
        MAGNET -> com.stoyko.smessenger.ai.t("🧲 Магнит дольше в 1,6 раза и тянет дальше", "🧲 Magnet lasts 1.6× longer and reaches further")
        X2START -> com.stoyko.smessenger.ai.t("✖2 Очки ×2 первые 20 секунд", "✖2 Double score for the first 20 s")
        CHASE -> com.stoyko.smessenger.ai.t("💨 Первая запинка не считается - обходчик не догонит", "💨 First stumble is forgiven")
        REVIVE -> com.stoyko.smessenger.ai.t("🔥 Возрождается из пепла: одно столкновение за забег не считается", "🔥 Rises from the ashes: one crash per run is forgiven")
        DRAGON -> com.stoyko.smessenger.ai.t("🐉 +50% монет и магнит дольше", "🐉 +50% coins and a longer magnet")
        LORD -> com.stoyko.smessenger.ai.t("👑 Щит и очки ×2 с самого старта", "👑 Shield and double score from the start")
        TIME -> com.stoyko.smessenger.ai.t("⏱ Бонусы действуют на 60% дольше, первая запинка прощается", "⏱ Power-ups last 60% longer, first stumble forgiven")
        else -> ""
    }
}

/** Набор своих (оригинальных) обликов: покупка за монеты, выбор, хранение в prefs «runner». */
internal object RunnerSkins {
    private fun c(v: Long) = v.toInt()

    val all: List<Skin> = listOf(
        Skin(
            R.string.runner3_skin0, R.string.runner3_skin0_d, 0,
            skin = c(0xFFF2C39B), hairColor = c(0xFF5B3A1E), hair = 0, top = c(0xFF2F7DE1), accent = c(0xFFFFFFFF),
            pants = c(0xFF27406E), shoes = c(0xFFE53935), sole = c(0xFFF8FAFC), hat = 1, hatColor = c(0xFFE53935),
            extra = 1, extraColor = c(0xFFFFB020),
        ),
        Skin(
            R.string.runner3_skin1, R.string.runner3_skin1_d, 150,
            skin = c(0xFFE0A87C), hairColor = c(0xFF2B1B10), hair = 3, top = c(0xFFFFD43B), accent = c(0xFFFF6B6B),
            pants = c(0xFFD9C8A0), shoes = c(0xFF14B8A6), sole = c(0xFFFFFFFF), hat = 5, hatColor = c(0xFF4CAF50),
            shorts = true, sleeves = false,
        ),
        Skin(
            R.string.runner3_skin2, R.string.runner3_skin2_d, 250,
            skin = c(0xFFF5D0B5), hairColor = c(0xFFE11D48), hair = 1, top = c(0xFF1F2937), accent = c(0xFFCBD5E1),
            pants = c(0xFF111827), shoes = c(0xFF0F0F10), sole = c(0xFFE11D48), extra = 6, extraColor = c(0xFFE5E7EB),
        ),
        Skin(
            R.string.runner3_skin3, R.string.runner3_skin3_d, 300,
            skin = c(0xFFC68A5E), hairColor = c(0xFF3B2414), hair = 0, top = c(0xFF5B7A3A), accent = c(0xFFF97316),
            pants = c(0xFF6B5B45), shoes = c(0xFF7C4A24), sole = c(0xFF3F2A18), hat = 2, hatColor = c(0xFFF97316),
            extra = 1, extraColor = c(0xFF8B5A2B),
        ),
        Skin(
            R.string.runner3_skin4, R.string.runner3_skin4_d, 400,
            skin = c(0xFFEBB894), hairColor = c(0xFF1F1A17), hair = 3, top = c(0xFFF97316), accent = c(0xFFE5E7EB),
            pants = c(0xFFEA6A0C), shoes = c(0xFF3F3F46), sole = c(0xFF18181B), hat = 3, hatColor = c(0xFFFACC15),
            extra = 3, extraColor = c(0xFF1F2937),
        ),
        Skin(
            R.string.runner3_skin5, R.string.runner3_skin5_d, 450,
            skin = c(0xFFD9A07A), hairColor = c(0xFF2A1A12), hair = 2, top = c(0xFFF8FAFC), accent = c(0xFF1E3A8A),
            pants = c(0xFF1E3A8A), shoes = c(0xFFF8FAFC), sole = c(0xFF1E3A8A), hat = 4, hatColor = c(0xFFDC2626),
            extra = 2, extraColor = c(0xFFDC2626), sleeves = false,
        ),
        Skin(
            R.string.runner3_skin6, R.string.runner3_skin6_d, 800,
            skin = c(0xFFE8B996), hairColor = c(0xFF0EA5E9), hair = 4, top = c(0xFF111827), accent = c(0xFF22D3EE),
            pants = c(0xFF1F2937), shoes = c(0xFF0B1220), sole = c(0xFF22D3EE), extra = 5, extraColor = c(0xFF22D3EE),
            glow = c(0xFF22D3EE), trail = 4,
        ),
        Skin(
            R.string.runner3_skin7, R.string.runner3_skin7_d, 900,
            skin = c(0xFFF1D6C8), hairColor = c(0xFFE0F2FE), hair = 2, top = c(0xFFE0F2FE), accent = c(0xFF60A5FA),
            pants = c(0xFFBFDBFE), shoes = c(0xFFFFFFFF), sole = c(0xFF93C5FD), hat = 6, hatColor = c(0xFFF8FAFC),
            extra = 4, extraColor = c(0xFF93C5FD), glow = c(0xFF93C5FD), trail = 3,
        ),
        Skin(
            R.string.runner3_skin8, R.string.runner3_skin8_d, 1100,
            skin = c(0xFFD4A27F), hairColor = c(0xFFF5D0FE), hair = 2, top = c(0xFF5B21B6), accent = c(0xFFFDE047),
            pants = c(0xFF3B0764), shoes = c(0xFFFDE047), sole = c(0xFF7E22CE), extra = 2, extraColor = c(0xFFFDE047),
            glow = c(0xFFFDE047), trail = 1,
        ),
        Skin(
            R.string.runner3_skin9, R.string.runner3_skin9_d, 1500,
            skin = c(0xFFE6B08C), hairColor = c(0xFFFF7A1A), hair = 4, top = c(0xFFB91C1C), accent = c(0xFFFFB020),
            pants = c(0xFF1C1917), shoes = c(0xFFFF7A1A), sole = c(0xFFFDE047), extra = 5, extraColor = c(0xFFFF7A1A),
            glow = c(0xFFFF8A1F), trail = 2,
        ),
        // ---- зимние облики (❄) ----
        Skin(
            R.string.runner3_skin10, R.string.runner3_skin10_d, 300,
            skin = c(0xFFF4D3BE), hairColor = c(0xFF3B2A20), hair = 0, top = c(0xFFF8FAFC), accent = c(0xFF94A3B8),
            pants = c(0xFFE2E8F0), shoes = c(0xFF1F2937), sole = c(0xFF475569), hat = 2, hatColor = c(0xFFDC2626),
            extra = 2, extraColor = c(0xFFDC2626), trail = 3, board = c(0xFF93C5FD),
        ),
        Skin(
            R.string.runner3_skin11, R.string.runner3_skin11_d, 450,
            skin = c(0xFFF2C39B), hairColor = c(0xFFB45309), hair = 3, top = c(0xFF15803D), accent = c(0xFFDC2626),
            pants = c(0xFF166534), shoes = c(0xFFDC2626), sole = c(0xFFFDE047), hat = 2, hatColor = c(0xFF16A34A),
            extra = 2, extraColor = c(0xFFFDE047), trail = 1, board = c(0xFFDC2626),
        ),
        Skin(
            R.string.runner3_skin12, R.string.runner3_skin12_d, 800,
            skin = c(0xFFF8DCC8), hairColor = c(0xFFFDE68A), hair = 2, top = c(0xFF7DD3FC), accent = c(0xFFF8FAFC),
            pants = c(0xFFBAE6FD), shoes = c(0xFFF8FAFC), sole = c(0xFF38BDF8), hat = 2, hatColor = c(0xFFE0F2FE),
            extra = 2, extraColor = c(0xFFF8FAFC), glow = c(0xFFBAE6FD), trail = 3,
        ),
        Skin(
            R.string.runner3_skin13, R.string.runner3_skin13_d, 1200,
            skin = c(0xFFDBEAFE), hairColor = c(0xFFE0F2FE), hair = 4, top = c(0xFF1E3A8A), accent = c(0xFF67E8F9),
            pants = c(0xFF172554), shoes = c(0xFF67E8F9), sole = c(0xFFE0F2FE), extra = 5, extraColor = c(0xFF67E8F9),
            glow = c(0xFF67E8F9), trail = 3,
        ),
        // ---- сезонные и новые облики (4.9.8) ----
        Skin(
            R.string.runner3_skin14, R.string.runner3_skin14_d, 350,
            skin = c(0xFFF8D7C4), hairColor = c(0xFFF9A8D4), hair = 2, top = c(0xFFFBCFE8), accent = c(0xFFDB2777),
            pants = c(0xFFF5F5F4), shoes = c(0xFFF472B6), sole = c(0xFFFFFFFF), extra = 2, extraColor = c(0xFFF9A8D4),
            trail = 1, board = c(0xFFF472B6),
        ),
        Skin(
            R.string.runner3_skin15, R.string.runner3_skin15_d, 400,
            skin = c(0xFFC98B5E), hairColor = c(0xFFFDE68A), hair = 3, top = c(0xFF22D3EE), accent = c(0xFFFDE047),
            pants = c(0xFFF97316), shoes = c(0xFFFDE047), sole = c(0xFF0EA5E9), hat = 5, hatColor = c(0xFFFDE047),
            shorts = true, sleeves = false, board = c(0xFF06B6D4),
        ),
        Skin(
            R.string.runner3_skin16, R.string.runner3_skin16_d, 450,
            skin = c(0xFFE7B48E), hairColor = c(0xFF7C2D12), hair = 0, top = c(0xFFEA580C), accent = c(0xFF1C1917),
            pants = c(0xFF44403C), shoes = c(0xFF78350F), sole = c(0xFFFBBF24), hat = 2, hatColor = c(0xFF7C2D12),
            extra = 2, extraColor = c(0xFFFBBF24), trail = 2, board = c(0xFFEA580C),
        ),
        Skin(
            R.string.runner3_skin17, R.string.runner3_skin17_d, 700,
            skin = c(0xFFE8B996), hairColor = c(0xFF0A0A0A), hair = 0, top = c(0xFF0A0A0A), accent = c(0xFFDC2626),
            pants = c(0xFF171717), shoes = c(0xFF0A0A0A), sole = c(0xFFDC2626), hat = 4, hatColor = c(0xFFDC2626),
            extra = 4, extraColor = c(0xFF171717), glow = c(0xFFEF4444),
        ),
        Skin(
            R.string.runner3_skin18, R.string.runner3_skin18_d, 1000,
            skin = c(0xFFF2C39B), hairColor = c(0xFF3B2414), hair = 0, top = c(0xFFF1F5F9), accent = c(0xFFF97316),
            pants = c(0xFFE2E8F0), shoes = c(0xFFCBD5E1), sole = c(0xFFF97316), hat = 3, hatColor = c(0xFFF8FAFC),
            extra = 5, extraColor = c(0xFF38BDF8), glow = c(0xFF38BDF8), trail = 1, board = c(0xFFE2E8F0),
        ),
        Skin(
            R.string.runner3_skin19, R.string.runner3_skin19_d, 650,
            skin = c(0xFFD9A07A), hairColor = c(0xFF1C1917), hair = 4, top = c(0xFFF8FAFC), accent = c(0xFFB91C1C),
            pants = c(0xFF1C1917), shoes = c(0xFF451A03), sole = c(0xFFB45309), hat = 4, hatColor = c(0xFFB91C1C),
            extra = 6, extraColor = c(0xFFFACC15), board = c(0xFF78350F),
        ),
        Skin(
            R.string.runner3_skin20, R.string.runner3_skin20_d, 1300,
            skin = c(0xFFEBB894), hairColor = c(0xFFE879F9), hair = 1, top = c(0xFF1E1B4B), accent = c(0xFFE879F9),
            pants = c(0xFF0F172A), shoes = c(0xFFE879F9), sole = c(0xFF22D3EE), extra = 3, extraColor = c(0xFFE879F9),
            glow = c(0xFFE879F9), trail = 4,
        ),
        Skin(
            R.string.runner3_skin21, R.string.runner3_skin21_d, 2500,
            skin = c(0xFFE6B08C), hairColor = c(0xFFFDE047), hair = 4, top = c(0xFFCA8A04), accent = c(0xFFFFFFFF),
            pants = c(0xFF854D0E), shoes = c(0xFFFDE047), sole = c(0xFFFFFFFF), hat = 1, hatColor = c(0xFFFDE047),
            extra = 6, extraColor = c(0xFFFFFFFF), glow = c(0xFFFDE047), trail = 1, board = c(0xFFFACC15),
        ),
        // ---- новогодние и новые облики (4.10.0) - в конце списка: купленные раньше не сдвигаются ----
        Skin(
            R.string.runner3_skin22, R.string.runner3_skin22_d, 500,
            skin = c(0xFFF8FAFC), hairColor = c(0xFFE2E8F0), hair = 0, top = c(0xFFF8FAFC), accent = c(0xFFDC2626),
            pants = c(0xFFE2E8F0), shoes = c(0xFF1F2937), sole = c(0xFF94A3B8), hat = 3, hatColor = c(0xFF475569),
            extra = 2, extraColor = c(0xFFDC2626), trail = 3, board = c(0xFFF97316),
        ),
        Skin(
            R.string.runner3_skin23, R.string.runner3_skin23_d, 600,
            skin = c(0xFFF5D0B5), hairColor = c(0xFFB45309), hair = 3, top = c(0xFF16A34A), accent = c(0xFFFDE047),
            pants = c(0xFFDC2626), shoes = c(0xFFDC2626), sole = c(0xFFFDE047), hat = 2, hatColor = c(0xFF15803D),
            extra = 2, extraColor = c(0xFFF8FAFC), trail = 1, board = c(0xFF16A34A),
        ),
        Skin(
            R.string.runner3_skin24, R.string.runner3_skin24_d, 1400,
            skin = c(0xFFE8C4A8), hairColor = c(0xFFA78BFA), hair = 1, top = c(0xFF0F172A), accent = c(0xFF4ADE80),
            pants = c(0xFF1E1B4B), shoes = c(0xFF4ADE80), sole = c(0xFFA78BFA), extra = 5, extraColor = c(0xFFA78BFA),
            glow = c(0xFF4ADE80), trail = 4,
        ),
        Skin(
            R.string.runner3_skin25, R.string.runner3_skin25_d, 700,
            skin = c(0xFFB7743F), hairColor = c(0xFF7C3F17), hair = 0, top = c(0xFFA0582A), accent = c(0xFFFFFFFF),
            pants = c(0xFF8B4A21), shoes = c(0xFFFFFFFF), sole = c(0xFFDC2626), extra = 2, extraColor = c(0xFFDC2626),
            trail = 2, board = c(0xFFF8FAFC),
        ),
        Skin(
            R.string.runner3_skin26, R.string.runner3_skin26_d, 900,
            skin = c(0xFFF2C39B), hairColor = c(0xFF3B2414), hair = 0, top = c(0xFFB91C1C), accent = c(0xFFF8FAFC),
            pants = c(0xFF1F2937), shoes = c(0xFF111827), sole = c(0xFFE0F2FE), hat = 3, hatColor = c(0xFF1E3A8A),
            extra = 4, extraColor = c(0xFF1E3A8A), trail = 3, board = c(0xFF93C5FD),
        ),
        Skin(
            R.string.runner3_skin27, R.string.runner3_skin27_d, 1800,
            skin = c(0xFFE6B08C), hairColor = c(0xFF22D3EE), hair = 4, top = c(0xFF0A0A0F), accent = c(0xFF4ADE80),
            pants = c(0xFF0A0A0F), shoes = c(0xFF0A0A0F), sole = c(0xFFF472B6), extra = 3, extraColor = c(0xFF22D3EE),
            glow = c(0xFF4ADE80), trail = 4, board = c(0xFF0A0A0F),
        ),
        // ---- ⚡ эксклюзивы магазина (4.10.0): только в «Магазине» мессенджера ----
        Skin(
            R.string.runner3_skin28, R.string.runner3_skin28_d, 1600,
            skin = c(0xFFE0F2FE), hairColor = c(0xFFF0F9FF), hair = 4, top = c(0xFF0C4A6E), accent = c(0xFF7DD3FC),
            pants = c(0xFF082F49), shoes = c(0xFFBAE6FD), sole = c(0xFFE0F2FE), hat = 6, hatColor = c(0xFFE0F2FE),
            extra = 5, extraColor = c(0xFF7DD3FC), glow = c(0xFF7DD3FC), trail = 3, board = c(0xFFBAE6FD), shopOnly = true,
        ),
        Skin(
            R.string.runner3_skin29, R.string.runner3_skin29_d, 1800,
            skin = c(0xFFF2C39B), hairColor = c(0xFFFF7A1A), hair = 4, top = c(0xFF7F1D1D), accent = c(0xFFFDE047),
            pants = c(0xFF450A0A), shoes = c(0xFFFF7A1A), sole = c(0xFFFDE047), extra = 5, extraColor = c(0xFFFDE047),
            glow = c(0xFFFF8A1F), trail = 2, board = c(0xFFDC2626), shopOnly = true,
        ),
        Skin(
            R.string.runner3_skin30, R.string.runner3_skin30_d, 2000,
            skin = c(0xFFE8B996), hairColor = c(0xFF1F2937), hair = 0, top = c(0xFFF8FAFC), accent = c(0xFF2563EB),
            pants = c(0xFFE2E8F0), shoes = c(0xFF94A3B8), sole = c(0xFF2563EB), hat = 3, hatColor = c(0xFFF8FAFC),
            extra = 3, extraColor = c(0xFF2563EB), glow = c(0xFF93C5FD), trail = 1, board = c(0xFFF8FAFC), shopOnly = true,
        ),
        Skin(
            R.string.runner3_skin31, R.string.runner3_skin31_d, 2500,
            skin = c(0xFFD9A07A), hairColor = c(0xFF0A0A0A), hair = 1, top = c(0xFF0A0A0A), accent = c(0xFFE879F9),
            pants = c(0xFF0A0A0A), shoes = c(0xFF0A0A0A), sole = c(0xFF22D3EE), extra = 6, extraColor = c(0xFFE879F9),
            glow = c(0xFFE879F9), trail = 4, board = c(0xFF1E1B4B), shopOnly = true,
        ),
        // ---- 🦸 персонажи (4.12.0): своё тело, свои части и своя способность ----
        Skin( // 32 Кошка Мия
            R.string.runner3_skin32, R.string.runner3_skin32_d, 1200,
            skin = c(0xFFF8D9C4), hairColor = c(0xFF1F2937), hair = 2, top = c(0xFFF472B6), accent = c(0xFFFDF2F8),
            pants = c(0xFF1F2937), shoes = c(0xFFFDF2F8), sole = c(0xFFF472B6), extra = 2, extraColor = c(0xFFFDE047),
            trail = 1, board = c(0xFFF472B6),
            tall = 0.9f, bulk = 0.92f, headK = 1.12f, parts = Parts.EARS or Parts.TAIL, partColor = c(0xFF1F2937), part2Color = c(0xFFF9A8D4),
            perk = Perks.JUMP,
        ),
        Skin( // 33 Робот Болт
            R.string.runner3_skin33, R.string.runner3_skin33_d, 1500,
            skin = c(0xFFCBD5E1), hairColor = c(0xFF94A3B8), hair = 3, top = c(0xFF64748B), accent = c(0xFFFACC15),
            pants = c(0xFF475569), shoes = c(0xFF334155), sole = c(0xFFFACC15), extra = 5, extraColor = c(0xFF38BDF8),
            glow = c(0xFF38BDF8), board = c(0xFF475569),
            tall = 1.04f, bulk = 1.2f, headK = 1.05f, parts = Parts.ANTENNA or Parts.PADS, partColor = c(0xFF94A3B8), part2Color = c(0xFF38BDF8),
            perk = Perks.MAGNET,
        ),
        Skin( // 34 Чертёнок Искра
            R.string.runner3_skin34, R.string.runner3_skin34_d, 1400,
            skin = c(0xFFF87171), hairColor = c(0xFF450A0A), hair = 1, top = c(0xFF1C1917), accent = c(0xFFF97316),
            pants = c(0xFF292524), shoes = c(0xFF7F1D1D), sole = c(0xFFF97316), extra = 6, extraColor = c(0xFFFDE047),
            trail = 2, board = c(0xFF7F1D1D),
            tall = 0.94f, headK = 1.08f, parts = Parts.HORNS or Parts.TAIL, partColor = c(0xFF1C1917), part2Color = c(0xFFF97316),
            perk = Perks.COINS,
        ),
        Skin( // 35 Ангел Лира
            R.string.runner3_skin35, R.string.runner3_skin35_d, 1600,
            skin = c(0xFFFDE7D6), hairColor = c(0xFFFDE68A), hair = 2, top = c(0xFFF8FAFC), accent = c(0xFFFDE047),
            pants = c(0xFFE0F2FE), shoes = c(0xFFF8FAFC), sole = c(0xFFFDE047), glow = c(0xFFFEF3C7), trail = 1, board = c(0xFFF8FAFC),
            tall = 1.06f, bulk = 0.9f, parts = Parts.WINGS or Parts.HALO, partColor = c(0xFFF8FAFC), part2Color = c(0xFFFDE047),
            perk = Perks.LUCK,
        ),
        Skin( // 36 Рыцарь Гром
            R.string.runner3_skin36, R.string.runner3_skin36_d, 1500,
            skin = c(0xFFE8B996), hairColor = c(0xFF3B2414), hair = 0, top = c(0xFF94A3B8), accent = c(0xFFDC2626),
            pants = c(0xFF475569), shoes = c(0xFF334155), sole = c(0xFF1F2937), board = c(0xFF94A3B8),
            tall = 1.06f, bulk = 1.18f, parts = Parts.PADS or Parts.CAPE, partColor = c(0xFFB91C1C), part2Color = c(0xFFCBD5E1),
            perk = Perks.SHIELD,
        ),
        Skin( // 37 Король Монетус
            R.string.runner3_skin37, R.string.runner3_skin37_d, 1800,
            skin = c(0xFFF2C39B), hairColor = c(0xFFF8FAFC), hair = 0, top = c(0xFF7E22CE), accent = c(0xFFFACC15),
            pants = c(0xFF581C87), shoes = c(0xFFFACC15), sole = c(0xFF7E22CE), extra = 6, extraColor = c(0xFFFACC15), trail = 1, board = c(0xFFFACC15),
            bulk = 1.12f, headK = 1.04f, parts = Parts.CROWN or Parts.CAPE, partColor = c(0xFFFACC15), part2Color = c(0xFFDC2626),
            perk = Perks.X2START,
        ),
        Skin( // 38 Лис Рыжик
            R.string.runner3_skin38, R.string.runner3_skin38_d, 1300,
            skin = c(0xFFF5D0B5), hairColor = c(0xFFEA580C), hair = 3, top = c(0xFF15803D), accent = c(0xFFFDE68A),
            pants = c(0xFF78350F), shoes = c(0xFF1C1917), sole = c(0xFFEA580C), extra = 2, extraColor = c(0xFFFDE68A), board = c(0xFFEA580C),
            tall = 0.96f, headK = 1.06f, parts = Parts.EARS or Parts.TAIL or Parts.BUSHY, partColor = c(0xFFEA580C), part2Color = c(0xFFFFF7ED),
            perk = Perks.CHASE,
        ),
        // ---- 👑 легендарные (4.12.0): аура, редкие части, сильная способность ----
        Skin( // 39 Феникс Солнца
            R.string.runner3_skin39, R.string.runner3_skin39_d, 7500,
            skin = c(0xFFF8D7C4), hairColor = c(0xFFFF7A1A), hair = 4, top = c(0xFFB91C1C), accent = c(0xFFFDE047),
            pants = c(0xFF7F1D1D), shoes = c(0xFFFF7A1A), sole = c(0xFFFDE047), glow = c(0xFFFF8A1F), trail = 2, board = c(0xFFFF7A1A),
            tall = 1.04f, parts = Parts.WINGS or Parts.HALO, partColor = c(0xFFFF7A1A), part2Color = c(0xFFFDE047),
            perk = Perks.REVIVE, legendary = true, aura = c(0xFFFF8A1F),
        ),
        Skin( // 40 Ледяной Дракон
            R.string.runner3_skin40, R.string.runner3_skin40_d, 8000,
            skin = c(0xFFBAE6FD), hairColor = c(0xFFE0F2FE), hair = 4, top = c(0xFF0C4A6E), accent = c(0xFF67E8F9),
            pants = c(0xFF082F49), shoes = c(0xFF67E8F9), sole = c(0xFFE0F2FE), glow = c(0xFF67E8F9), trail = 3, board = c(0xFF0EA5E9),
            tall = 1.08f, bulk = 1.1f, parts = Parts.HORNS or Parts.WINGS or Parts.TAIL, partColor = c(0xFF0EA5E9), part2Color = c(0xFFE0F2FE),
            perk = Perks.DRAGON, legendary = true, aura = c(0xFF67E8F9),
        ),
        Skin( // 41 Тёмный Властелин
            R.string.runner3_skin41, R.string.runner3_skin41_d, 9000,
            skin = c(0xFFD6D3D1), hairColor = c(0xFF0A0A0A), hair = 0, top = c(0xFF1E1B4B), accent = c(0xFFA855F7),
            pants = c(0xFF0F0A1E), shoes = c(0xFF0A0A0A), sole = c(0xFFA855F7), glow = c(0xFFA855F7), trail = 4, board = c(0xFF1E1B4B),
            tall = 1.1f, bulk = 1.15f, parts = Parts.CROWN or Parts.CAPE or Parts.HORNS or Parts.PADS, partColor = c(0xFF2E1065), part2Color = c(0xFFA855F7),
            perk = Perks.LORD, legendary = true, aura = c(0xFFA855F7),
        ),
        Skin( // 42 Кибер-самурай
            R.string.runner3_skin42, R.string.runner3_skin42_d, 8500,
            skin = c(0xFFE8B996), hairColor = c(0xFF0A0A0A), hair = 2, top = c(0xFF0A0A0F), accent = c(0xFFF43F5E),
            pants = c(0xFF111827), shoes = c(0xFF0A0A0F), sole = c(0xFF22D3EE), extra = 5, extraColor = c(0xFFF43F5E),
            glow = c(0xFFF43F5E), trail = 4, board = c(0xFF0A0A0F),
            bulk = 1.08f, parts = Parts.PADS or Parts.CAPE or Parts.ANTENNA, partColor = c(0xFFF43F5E), part2Color = c(0xFF22D3EE),
            perk = Perks.TIME, legendary = true, aura = c(0xFFF43F5E),
        ),
    )

    // ---------------- акция дня (4.10.0) ----------------

    /** Скидка акции, %. */
    const val DEAL_OFF = 40

    private fun today() = System.currentTimeMillis() / 86_400_000L

    /**
     * Облик дня со скидкой: каждый день свой (одинаковый у всех), из ещё не купленных платных.
     * -1 - все уже куплены.
     */
    fun dealToday(ctx: Context): Int {
        val pool = all.indices.filter { it != 0 && all[it].price > 0 && !all[it].legendary && !isOwned(ctx, it) }
        if (pool.isEmpty()) return -1
        val r = java.util.Random(today() * 4099L + 17L)
        return pool[r.nextInt(pool.size)]
    }

    /** Цена облика сейчас (с учётом акции дня). */
    fun priceNow(ctx: Context, i: Int): Int {
        val base = all.getOrNull(i)?.price ?: return 0
        return if (i == dealToday(ctx)) base * (100 - DEAL_OFF) / 100 else base
    }

    /** Время до смены акции, мс. */
    fun dealLeftMs(): Long = (today() + 1) * 86_400_000L - System.currentTimeMillis()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)

    fun owned(ctx: Context): Set<Int> {
        val s = prefs(ctx).getString("skins_owned", "0").orEmpty()
        return s.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in all.indices }.toMutableSet().apply { add(0) }
    }

    fun isOwned(ctx: Context, i: Int) = i == 0 || i in owned(ctx)

    fun equipped(ctx: Context): Int {
        val i = prefs(ctx).getInt("skin", 0)
        return if (i in all.indices && isOwned(ctx, i)) i else 0
    }

    fun equip(ctx: Context, i: Int) {
        if (i in all.indices && isOwned(ctx, i)) prefs(ctx).edit().putInt("skin", i).apply()
    }

    /** Купить облик [i] (списать монеты). false - не хватает монет. */
    /** Купить облик [i] по текущей цене (акция учитывается). [fromShop] - эксклюзивы только из магазина. */
    fun buy(ctx: Context, i: Int, fromShop: Boolean = false): Boolean {
        if (i !in all.indices) return false
        if (isOwned(ctx, i)) return true
        if (all[i].shopOnly && !fromShop) return false
        if (!com.stoyko.smessenger.chat.Coins.spend(ctx, priceNow(ctx, i))) return false
        val set = owned(ctx).toMutableSet().apply { add(i) }
        prefs(ctx).edit().putString("skins_owned", set.sorted().joinToString(",")).putInt("skin", i).apply()
        return true
    }
}
