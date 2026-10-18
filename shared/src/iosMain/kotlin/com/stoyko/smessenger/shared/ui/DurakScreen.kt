// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.games.DurakBot
import com.stoyko.smessenger.games.DurakEngine
import com.stoyko.smessenger.shared.platform.KeyValueStore
import com.stoyko.smessenger.shared.platform.nowNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DURAK_RANKS = listOf("6", "7", "8", "9", "10", "В", "Д", "К", "Т")
private val BOT_NAMES = listOf("Вы", "Бот Лиса", "Бот Ёж", "Бот Сова")

/** «Дурак» против ботов: DurakEngine и DurakBot перенесены из Android без изменений. */
@Composable
fun DurakScreen(p: SeasonPalette, onBack: () -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    val scope = rememberCoroutineScope()
    var players by remember { mutableStateOf(prefs.getInt("durak_players", 2).coerceIn(2, 4)) }
    var mode by remember { mutableStateOf(prefs.getInt("durak_mode", DurakEngine.PODKIDNOY).coerceIn(0, DurakEngine.MODES - 1)) }
    var game by remember { mutableStateOf(0) }
    val e = remember(game, players, mode) { DurakEngine(players, mode).apply { deal(nowNanos()) } }
    var tick by remember { mutableStateOf(0) }
    var botsBusy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun eventText(who: Int): String = when (e.lastEvent) {
        "take" -> "${BOT_NAMES[who]} берёт"
        "bito" -> "Бито!"
        "transfer" -> "${BOT_NAMES[who]} переводит"
        else -> ""
    }

    // Боты ходят, пока могут; человек (игрок 0) ходит касанием карты.
    fun runBots() {
        if (botsBusy) return
        botsBusy = true
        scope.launch {
            while (!e.finished) {
                var acted = false
                for (pl in 1 until e.players) {
                    val def = e.defender
                    if (DurakBot.act(e, pl)) {
                        acted = true
                        eventText(if (e.lastEvent == "take") def else pl).takeIf { it.isNotEmpty() }?.let { note = it }
                        tick++
                        delay(520)
                        break
                    }
                }
                if (!acted) break
            }
            botsBusy = false
            tick++
        }
    }

    LaunchedEffect(e) {
        note = if (e.attacker == 0) "Ваш заход" else "Заходит ${BOT_NAMES[e.attacker]}"
        delay(400)
        runBots()
    }

    fun playCard(card: Int) {
        if (botsBusy || e.finished) return
        val ok = when {
            e.defender == 0 -> {
                val idx = e.uncovered().firstOrNull { e.canDefend(0, it, card) }
                when {
                    idx != null -> e.defend(0, idx, card)
                    e.canTransfer(0, card) -> e.transfer(0, card).also { if (it) note = "Вы переводите" }
                    else -> false
                }
            }
            else -> e.attack(0, card)
        }
        if (!ok) {
            note = when {
                e.defender == 0 -> "Эта карта не бьёт"
                e.table.isEmpty() && e.attacker != 0 -> "Сейчас заходит ${BOT_NAMES[e.attacker]}"
                else -> "Подкинуть можно только достоинство со стола"
            }
            return
        }
        if (e.lastEvent == "bito") note = "Бито!"
        tick++
        runBots()
    }

    @Suppress("UNUSED_VARIABLE") val t = tick

    val myTurnText = when {
        e.finished -> when (e.durak) {
            -2 -> "Ничья!"
            0 -> "Вы - дурак 🙈"
            else -> "Вы победили! Дурак - ${BOT_NAMES[e.durak]} 🎉"
        }
        0 in e.out -> "Вы вышли из игры - доигрывают боты"
        botsBusy -> "Ходят боты…"
        e.defender == 0 && e.uncovered().isNotEmpty() -> "Отбивайтесь или берите"
        e.table.isEmpty() && e.attacker == 0 -> "Ваш заход - выберите карту"
        e.canPass(0) -> "Подкиньте или скажите «Бито»"
        else -> "Ждём соперников"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("Дурак", onBack = onBack)

        // Соперники.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pl in 1 until e.players) {
                val role = when (pl) { e.attacker -> "ходит"; e.defender -> "отбивается"; else -> "" }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (pl == e.attacker || pl == e.defender) p.accent.copy(alpha = 0.22f) else Ui.Card)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(BOT_NAMES[pl], style = Ui.body)
                    Row(horizontalArrangement = Arrangement.spacedBy((-30).dp)) {
                        repeat(minOf(e.hands[pl].size, 6)) { PlayingCard("", 0, faceDown = true, width = 34.dp) }
                    }
                    BasicText(
                        if (pl in e.out) "вышел" else "${e.hands[pl].size} карт" + if (role.isNotEmpty()) " · $role" else "",
                        style = Ui.dim,
                    )
                }
            }
        }

        // Колода и козырь.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                if (e.trumpCard >= 0 && e.deck.isNotEmpty()) {
                    PlayingCard(DURAK_RANKS[e.rank(e.trumpCard)], e.suit(e.trumpCard), width = 44.dp, modifier = Modifier.offset(x = 22.dp))
                }
                if (e.deck.size > 1) PlayingCard("", 0, faceDown = true, width = 44.dp)
            }
            Column(Modifier.padding(start = 20.dp)) {
                BasicText("Козырь ${suitGlyph(e.trump)}", style = TextStyle(color = if (suitRed(e.trump)) Color(0xFFFF6B7A) else Ui.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                BasicText("В колоде: ${e.deck.size} · Бито: ${e.discard.size}", style = Ui.dim)
            }
        }

        // Стол.
        Box(
            Modifier.fillMaxWidth().heightIn(min = 130.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF1E6B47), Color(0xFF0F3D28))))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (e.table.isEmpty()) {
                BasicText(note.ifEmpty { "Стол пуст" }, style = TextStyle(color = Color(0xCCFFFFFF), fontSize = 15.sp))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pair in e.table) {
                        Box {
                            PlayingCard(DURAK_RANKS[e.rank(pair[0])], e.suit(pair[0]), width = 48.dp)
                            if (pair[1] >= 0) {
                                PlayingCard(
                                    DURAK_RANKS[e.rank(pair[1])], e.suit(pair[1]), width = 48.dp,
                                    modifier = Modifier.offset(x = 10.dp, y = 22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        BasicText(myTurnText, style = Ui.h2)
        if (note.isNotEmpty() && e.table.isNotEmpty()) BasicText(note, style = TextStyle(color = p.accent, fontSize = 14.sp))

        // Рука.
        val hand = e.hands[0].sortedWith(compareBy({ if (e.suit(it) == e.trump) 1 else 0 }, { e.suit(it) }, { e.rank(it) }))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy((-14).dp),
        ) {
            for (c in hand) {
                val playable = !botsBusy && !e.finished && (
                    (e.defender == 0 && (e.uncovered().any { e.canDefend(0, it, c) } || e.canTransfer(0, c))) ||
                        (e.defender != 0 && e.canAttack(0, c))
                    )
                PlayingCard(
                    DURAK_RANKS[e.rank(c)], e.suit(c), width = 58.dp,
                    raised = playable,
                    dimmed = !playable && !botsBusy,
                    highlight = if (e.suit(c) == e.trump) p.accent else null,
                ) { playCard(c) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!e.finished && e.defender == 0 && e.table.isNotEmpty()) {
                Chip("Беру", active = true, accent = Color(0xFFE85D5D), enabled = !botsBusy) {
                    if (e.take(0)) { note = "Вы берёте"; tick++; runBots() }
                }
            }
            if (!e.finished && e.canPass(0)) {
                Chip(if (e.uncovered().isEmpty()) "Бито" else "Пас", active = true, accent = p.accent, enabled = !botsBusy) {
                    if (e.pass(0)) { tick++; runBots() }
                }
            }
            if (e.finished) Chip("Новая игра", active = true, accent = p.accent) { game++ }
        }

        Panel {
            BasicText("Игроков", style = Ui.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 2..4) Chip("$n", active = players == n, accent = p.accent) {
                    players = n; prefs.putInt("durak_players", n)
                }
            }
            BasicText("Режим", style = Ui.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((m, name) in listOf(DurakEngine.PODKIDNOY to "Подкидной", DurakEngine.PEREVODNOY to "Переводной", DurakEngine.SIMPLE to "Простой")) {
                    Chip(name, active = mode == m, accent = p.accent) { mode = m; prefs.putInt("durak_mode", m) }
                }
            }
            Chip("Пересдать", onClick = { game++ })
        }
    }
}
