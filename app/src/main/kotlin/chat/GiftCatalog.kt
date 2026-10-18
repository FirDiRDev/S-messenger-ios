// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import java.util.Locale

/**
 * Каталог подарков (assets/gifts.tsv), ни один не повторяется:
 *  - 1-3000: эмодзи Unicode (каждое по одному разу) и подарки, нарисованные приложением
 *    (самоцвет, кристалл, звезда, сердце, сфера, снежинка, цветок, планета, руна, щит, корона, полумесяц);
 *  - 3001-3026: новые эмодзи - 🐦‍🔥 Феникс, 🪽 Крыло ангела, 🪼 Медуза, ⛓️‍💥 Разорванная цепь, сердца 🩷🩵🩶…;
 *  - 3027-3226: новые нарисованные формы - молния, капля, клевер, соты, бабочка.
 *
 * Подарки, отправленные в прежних версиях, показываются так, как выглядели тогда: в сообщении
 * и в сохранённом подарке есть сам эмодзи, и если номер в каталоге с тех пор стал означать
 * другой подарок, побеждает эмодзи (см. [resolve]) - феникс остаётся птицей.
 * У каждой редкости свой анимированный эффект (см. [GiftArt]). Каталог лежит в assets/gifts.tsv
 * и одинаков на всех телефонах, поэтому по сети передаётся только номер подарка.
 */
object GiftCatalog {
    enum class Rarity(val ru: String, val en: String, val color: Int) {
        COMMON("Обычный", "Common", 0xFF64748B.toInt()),
        RARE("Редкий", "Rare", 0xFF3B82F6.toInt()),
        LEGENDARY("Легендарный", "Legendary", 0xFFF59E0B.toInt()),
    }

    class Gift(
        val id: Int,
        /** Эмодзи (для нарисованных - символ-заглушка для уведомлений). */
        val emoji: String,
        val ru: String,
        val en: String,
        val rarity: Rarity,
        val colors: IntArray,
        /** Нарисованный подарок: «форма:узор» (null - эмодзи). */
        val drawn: String? = null,
        /** Подарок-картинка (новый дизайн, assets/gifts/…); emoji - запасной вид для старых версий. */
        val image: String? = null,
    ) {
        val fx: Int get() = id % 4

        fun name(): String = if (Locale.getDefault().language == "ru") ru else en

        /** Короткая подпись для сетки (без палитры и узора). */
        fun shortName(): String = name().substringBefore(" «").substringBefore(" “").substringBefore(" · ")
    }

    @Volatile
    private var data: List<Gift>? = null

    @Volatile
    private var app: Context? = null

    /** Вызывается при запуске приложения (и на всякий случай - перед показом подарков). */
    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    private fun load(): List<Gift> {
        data?.let { return it }
        synchronized(this) {
            data?.let { return it }
            val ctx = app ?: return emptyList()
            val out = ArrayList<Gift>(3000)
            // Прежний каталог (эмодзи и нарисованные) - чтобы подарки из старых версий выглядели
            // как раньше, и новые подарки-картинки (их и выбирают теперь).
            for (file in arrayOf("gifts.tsv", "gifts_art.tsv", "gifts_excl.tsv")) {
                runCatching {
                    ctx.assets.open(file).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val p = line.split('\t')
                            if (p.size < 8) continue
                            val rarity = when (p[3]) {
                                "l" -> Rarity.LEGENDARY
                                "r" -> Rarity.RARE
                                else -> Rarity.COMMON
                            }
                            val colors = intArrayOf(
                                (0xFF000000.toInt() or p[4].toInt(16)),
                                (0xFF000000.toInt() or p[5].toInt(16)),
                            )
                            when (p[1]) {
                                "i" -> {
                                    val img = p[2].substringBefore('|')
                                    val emoji = p[2].substringAfter('|', "🎁")
                                    out += Gift(p[0].toInt(), emoji, p[6], p[7], rarity, colors, null, img)
                                }
                                "d" -> out += Gift(p[0].toInt(), symbolFor(p[2]), p[6], p[7], rarity, colors, p[2])
                                else -> out += Gift(p[0].toInt(), p[2], p[6], p[7], rarity, colors)
                            }
                        }
                    }
                }
            }
            byIdMap = out.associateBy { it.id }
            data = out
            return out
        }
    }

    private fun symbolFor(drawn: String) = when (drawn.substringBefore(':')) {
        "bolt" -> "⚡"
        "drop" -> "💧"
        "clover" -> "🍀"
        "hex" -> "🍯"
        "butterfly" -> "🦋"
        "gem", "crystal" -> "💎"
        "star" -> "⭐"
        "heart" -> "❤️"
        "snow" -> "❄️"
        "flower" -> "🌸"
        "planet" -> "🪐"
        "crown" -> "👑"
        "moon" -> "🌙"
        "shield" -> "🛡"
        else -> "✨"
    }

    val all: List<Gift> get() = load()

    @Volatile
    private var byIdMap: Map<Int, Gift> = emptyMap()

    fun byId(id: Int): Gift? {
        load()
        return byIdMap[id]
    }

    @Volatile
    private var byRarity: Map<Rarity, List<Gift>>? = null

    /**
     * Подарки для выбора: все, без повторов - сначала подарки-картинки, потом прежние
     * эмодзи-подарки (феникс, звёзды, сердца…) и нарисованные приложением (самоцветы, кристаллы,
     * планеты, руны, короны, бабочки…). Их снова можно дарить (как в 3.3.x).
     */
    /**
     * Эксклюзивные подарки (✦, номера 6001…): их нет в обычном выборе - только в акциях
     * магазина монет, на ограниченное время.
     */
    fun isExclusive(g: Gift) = g.id in 6001..6999

    val exclusives: List<Gift> get() = all.filter { isExclusive(it) }

    fun of(rarity: Rarity): List<Gift> {
        val cached = byRarity ?: all.filter { !isExclusive(it) }.sortedBy { if (it.image != null) 0 else if (it.drawn == null) 1 else 2 }
            .groupBy { it.rarity }
            .also { if (it.isNotEmpty()) byRarity = it }
        return cached[rarity].orEmpty()
    }

    /**
     * Тематические категории подарков (assets/gifts_cat.tsv): по ним фильтруется выбор, и у
     * каждой - своя «подпись» в анимации (сердечки, ноты, пузырьки, искры, снежинки…).
     */
    enum class Cat(val key: String, val emoji: String, val ru: String, val en: String) {
        LOVE("love", "❤️", "Любовь", "Love"),
        MAGIC("magic", "🔮", "Магия", "Magic"),
        TREASURE("treasure", "💎", "Сокровища", "Treasures"),
        CREATURES("creatures", "🐉", "Звери и существа", "Creatures"),
        NATURE("nature", "🌿", "Природа", "Nature"),
        SPACE("space", "🪐", "Космос", "Space"),
        SEA("sea", "🌊", "Море", "Sea"),
        WINTER("winter", "❄️", "Зима", "Winter"),
        TECH("tech", "⚙️", "Техника", "Tech"),
        ART("art", "🎵", "Искусство", "Art"),
        FOOD("food", "🍰", "Вкусное", "Food"),
        TRAVEL("travel", "✈️", "Путешествия", "Travel"),
        STYLE("style", "👗", "Стиль", "Style"),
        FUN("fun", "🎉", "Праздник и эмоции", "Fun & emotions"),
        SYMBOLS("symbols", "🔣", "Символы", "Symbols"),
        FLAGS("flags", "🏳️", "Флаги", "Flags"),
        OTHER("other", "✨", "Разное", "Misc");

        fun title(): String = if (Locale.getDefault().language == "ru") ru else en
    }

    @Volatile
    private var cats: Map<Int, Cat>? = null

    private fun loadCats(): Map<Int, Cat> {
        cats?.let { return it }
        val ctx = app ?: return emptyMap()
        val byKey = Cat.values().associateBy { it.key }
        val m = HashMap<Int, Cat>(4000)
        runCatching {
            ctx.assets.open("gifts_cat.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val id = line.substring(0, tab).toIntOrNull() ?: continue
                    m[id] = byKey[line.substring(tab + 1).trim()] ?: Cat.OTHER
                }
            }
        }
        cats = m
        return m
    }

    fun catOf(g: Gift): Cat = loadCats()[g.id] ?: when {
        g.drawn?.startsWith("heart") == true -> Cat.LOVE
        g.drawn?.startsWith("snow") == true -> Cat.WINTER
        else -> Cat.OTHER
    }

    /** Вид подарка для фильтра в выборе: картинка, эмодзи или нарисованный. */
    enum class Kind { ALL, IMAGE, EMOJI, DRAWN }

    fun kindOf(g: Gift): Kind = when {
        g.image != null -> Kind.IMAGE
        g.drawn != null -> Kind.DRAWN
        else -> Kind.EMOJI
    }

    /** Эмодзи без «вариантов начертания» (FE0F) - 👍 и 👍️ считаются одним. */
    private fun norm(e: String) = e.replace("\uFE0F", "").trim()

    /** Подарок старого формата (только эмодзи). */
    fun byEmoji(emoji: String): Gift? {
        if (emoji.isBlank()) return null
        val n = norm(emoji)
        // Сначала прежние эмодзи-подарки (у картинок эмодзи - лишь запасной вид).
        return all.firstOrNull { it.drawn == null && it.image == null && (it.emoji == emoji || norm(it.emoji) == n) }
    }

    /**
     * Подарок по номеру и эмодзи из сообщения/профиля. Если под этим номером в каталоге теперь
     * другой подарок (каталог менялся в 3.3.0), показываем тот, что был подарен: его эмодзи,
     * а редкость и цвет фона - как у номера (так подарок выглядел в своей версии).
     */
    fun resolve(id: Int, emoji: String?): Gift? {
        val slot = if (id > 0) byId(id) else null
        val e = emoji?.trim().orEmpty()
        if (e.isEmpty()) return slot ?: byEmoji(e)
        if (slot != null && (slot.emoji == e || norm(slot.emoji) == norm(e))) return slot
        // Подарок-картинка: номер новый (5001+), эмодзи - лишь подпись для старых версий.
        if (slot != null && slot.image != null) return slot
        val known = byEmoji(e)
        if (slot == null) return known ?: Gift(0, e, e, e, Rarity.COMMON, intArrayOf(0xFFB9AFFF.toInt(), 0xFF5B4BDB.toInt()))
        return Gift(slot.id, e, known?.ru ?: e, known?.en ?: e, slot.rarity, slot.colors)
    }

    fun rarityName(r: Rarity): String = if (Locale.getDefault().language == "ru") r.ru else r.en
}
