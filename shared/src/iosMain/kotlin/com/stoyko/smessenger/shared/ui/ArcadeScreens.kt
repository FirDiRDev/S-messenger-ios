// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.shared.games.Blackjack
import com.stoyko.smessenger.shared.games.Snake
import com.stoyko.smessenger.shared.platform.KeyValueStore
import com.stoyko.smessenger.shared.platform.nowNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ------------------------------------------------------------------------------------- Блэкджек

@Composable
fun BlackjackScreen(p: SeasonPalette, onBack: () -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    val scope = rememberCoroutineScope()
    val game = remember { Blackjack(nowNanos()) }
    var chips by remember { mutableStateOf(prefs.getInt("bj_chips", 1000)) }
    var betSel by remember { mutableStateOf(50) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    fun setChips(v: Int) {
        chips = v
        prefs.putInt("bj_chips", v)
    }

    fun settle() {
        if (game.phase == Blackjack.Phase.DONE) setChips(chips + game.payout())
        tick++
    }

    fun dealerPlays() {
        busy = true
        scope.launch {
            tick++
            delay(450)
            while (game.dealerStep()) { tick++; delay(550) }
            settle()
            busy = false
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick
    val (ps, pSoft) = game.score(game.player)
    val hideHole = game.phase == Blackjack.Phase.PLAYER
    val ds = if (hideHole && game.dealer.isNotEmpty()) game.score(listOf(game.dealer[0])).first else game.score(game.dealer).first

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Блэкджек", onBack = onBack) {
            BasicText("🪙 $chips", style = TextStyle(color = Color(0xFFFFD54F), fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 300.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF1E6B47), Color(0xFF0B2E1F))))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("Дилер" + if (game.dealer.isNotEmpty()) " · $ds" else "", style = TextStyle(color = Color(0xCCFFFFFF), fontSize = 15.sp))
            Row(horizontalArrangement = Arrangement.spacedBy((-16).dp)) {
                game.dealer.forEachIndexed { i, c ->
                    PlayingCard(game.rankLabel(c), game.suit(c), faceDown = hideHole && i == 1, width = 56.dp)
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                val r = when (game.result) {
                    Blackjack.Result.BLACKJACK -> "Блэкджек! +${game.payout() - game.bet}"
                    Blackjack.Result.WIN -> "Победа! +${game.bet}"
                    Blackjack.Result.PUSH -> "Ничья - ставка возвращена"
                    Blackjack.Result.LOSE -> "Дилер выиграл"
                    Blackjack.Result.BUST -> "Перебор"
                    Blackjack.Result.NONE -> if (game.phase == Blackjack.Phase.BETTING) "Сделайте ставку" else "Ставка ${game.bet}"
                }
                BasicText(r, style = TextStyle(color = Color(0xFFFFE082), fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
            Row(horizontalArrangement = Arrangement.spacedBy((-16).dp)) {
                for (c in game.player) PlayingCard(game.rankLabel(c), game.suit(c), width = 56.dp)
            }
            if (game.player.isNotEmpty()) {
                BasicText("Вы · $ps" + if (pSoft && ps < 21) " (мягкие)" else "", style = TextStyle(color = Color.White, fontSize = 16.sp))
            }
        }

        when (game.phase) {
            Blackjack.Phase.PLAYER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Ещё", active = true, accent = p.accent, enabled = !busy) {
                    game.hit(); tick++
                    if (game.phase == Blackjack.Phase.DEALER) dealerPlays() else settle()
                }
                Chip("Хватит", enabled = !busy) { game.stand(); dealerPlays() }
                if (game.canDouble && chips >= game.bet) {
                    Chip("Удвоить", enabled = !busy) {
                        setChips(chips - game.bet)
                        game.double()
                        if (game.phase == Blackjack.Phase.DEALER) dealerPlays() else settle()
                    }
                }
            }
            Blackjack.Phase.DEALER -> BasicText("Дилер берёт карты…", style = Ui.dim)
            Blackjack.Phase.BETTING, Blackjack.Phase.DONE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in listOf(10, 50, 100, 250)) Chip("$b", active = betSel == b, accent = p.accent) { betSel = b }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Раздать ($betSel)", active = true, accent = p.accent, enabled = chips >= betSel && !busy) {
                        setChips(chips - betSel)
                        game.deal(betSel)
                        settle()
                    }
                    if (chips < 10) Chip("Взять 1000 фишек") { setChips(chips + 1000) }
                }
            }
        }
        BasicText("Фишки игровые и хранятся только на этом устройстве.", style = Ui.dim)
    }
}

// ------------------------------------------------------------------------------------- Змейка

@Composable
fun SnakeScreen(p: SeasonPalette, onBack: () -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    var round by remember { mutableStateOf(0) }
    val snake = remember(round) { Snake(seed = nowNanos()) }
    var tick by remember { mutableStateOf(0) }
    var running by remember(round) { mutableStateOf(false) }
    var best by remember { mutableStateOf(prefs.getInt("snake_best", 0)) }

    LaunchedEffect(snake, running) {
        while (running && !snake.dead) {
            delay(snake.delayMs())
            snake.step()
            tick++
        }
        if (snake.dead && snake.score > best) {
            best = snake.score
            prefs.putInt("snake_best", best)
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("Змейка", onBack = onBack) {
            BasicText("🍎 ${snake.score} · рекорд $best", style = Ui.body)
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(snake.w / snake.h.toFloat()).clip(RoundedCornerShape(18.dp))
                .background(Color(0x44000000))
                .pointerInput(snake) {
                    var acc = Offset.Zero
                    detectDragGestures(
                        onDragStart = { acc = Offset.Zero },
                        onDrag = { ch, d ->
                            ch.consume()
                            acc += d
                            if (acc.getDistance() > 24f) {
                                snake.turn(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) 1 else 3) else (if (acc.y > 0) 2 else 0))
                                if (!running && !snake.dead) running = true
                                acc = Offset.Zero
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cw = size.width / snake.w
                val chh = size.height / snake.h
                // Клетки-«шахматка».
                for (y in 0 until snake.h) for (x in 0 until snake.w) if ((x + y) % 2 == 0) {
                    drawRect(Color(0x0DFFFFFF), Offset(x * cw, y * chh), Size(cw, chh))
                }
                if (snake.apple >= 0) {
                    val ax = snake.apple % snake.w
                    val ay = snake.apple / snake.w
                    drawCircle(Color(0xFFFF4D5E), radius = cw * 0.42f, center = Offset((ax + 0.5f) * cw, (ay + 0.5f) * chh))
                    drawCircle(Color(0x88FFFFFF), radius = cw * 0.12f, center = Offset((ax + 0.38f) * cw, (ay + 0.36f) * chh))
                }
                val n = snake.body.size
                snake.body.forEachIndexed { i, c ->
                    val x = c % snake.w
                    val y = c / snake.w
                    val k = 1f - i / (n + 4f)
                    drawRoundRect(
                        color = lerpColor(p.accent2, p.accent, k),
                        topLeft = Offset(x * cw + 1f, y * chh + 1f),
                        size = Size(cw - 2f, chh - 2f),
                        cornerRadius = CornerRadius(cw * 0.35f),
                    )
                }
            }
            if (!running || snake.dead) {
                BasicText(
                    if (snake.dead) "Игра окончена\nСчёт: ${snake.score}" else "Проведите пальцем,\nчтобы начать",
                    style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snake.dead) Chip("Ещё раз", active = true, accent = p.accent) { round++ }
            if (running && !snake.dead) Chip("Пауза") { running = false }
        }
        BasicText("Свайп - поворот. С каждым яблоком змейка быстрее.", style = Ui.dim)
    }
}
