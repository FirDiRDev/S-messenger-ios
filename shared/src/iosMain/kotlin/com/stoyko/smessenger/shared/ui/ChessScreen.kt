// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.games.ChessEngine
import com.stoyko.smessenger.shared.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ︎ - текстовое начертание: иначе iOS рисует ♟ как цветной эмодзи.
private fun glyph(type: Int): String = when (type) {
    ChessEngine.KING -> "♚"
    ChessEngine.QUEEN -> "♛"
    ChessEngine.ROOK -> "♜"
    ChessEngine.BISHOP -> "♝"
    ChessEngine.KNIGHT -> "♞"
    else -> "♟"
} + "︎"

/** Шахматы против бота: ChessEngine перенесён из Android без изменений (см. syncPortedSources). */
@Composable
fun ChessScreen(p: SeasonPalette, onBack: () -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    val scope = rememberCoroutineScope()
    val engine = remember {
        ChessEngine().apply {
            val saved = prefs.getString("chess_moves")
            if (saved.isNotEmpty()) load(saved.split(' '))
        }
    }
    var version by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var thinking by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(prefs.getInt("chess_level", 2)) }
    var last by remember { mutableStateOf<ChessEngine.Move?>(null) }

    // Читаем состояние движка после каждого хода (version - ключ пересчёта).
    val legal = remember(version) { engine.legal() }
    val status = remember(version) { engine.status() }
    val check = remember(version) { engine.inCheck() }
    val board = remember(version) { engine.board.copyOf() }

    fun commit() {
        version++
        prefs.putString("chess_moves", engine.moves.joinToString(" "))
    }

    fun botMove() {
        thinking = true
        scope.launch {
            // Бот считает на копии партии в фоне - доска на экране не «дёргается».
            val history = engine.moves.toList()
            val lvl = level
            val m = withContext(Dispatchers.Default) {
                val copy = ChessEngine()
                copy.load(history)
                copy.bestMove(lvl)
            }
            if (m != null && engine.play(m.uci())) last = m
            thinking = false
            commit()
        }
    }

    fun tap(i: Int) {
        if (thinking || status != ChessEngine.Status.PLAYING || engine.side != 0) return
        val piece = board[i]
        if (selected >= 0) {
            val mv = legal.filter { it.from == selected && it.to == i }
            if (mv.isNotEmpty()) {
                val m = mv.firstOrNull { it.promo == ChessEngine.QUEEN } ?: mv.first()
                if (engine.play(m.uci())) {
                    last = m
                    selected = -1
                    commit()
                    if (engine.status() == ChessEngine.Status.PLAYING) botMove()
                }
                return
            }
        }
        selected = if (piece != 0 && engine.colorOf(piece) == 0 && i != selected) i else -1
    }

    val targets = if (selected >= 0) legal.filter { it.from == selected }.map { it.to }.toSet() else emptySet()
    val kingSq = if (check) board.indexOfFirst { it != 0 && engine.colorOf(it) == engine.side && engine.typeOf(it) == ChessEngine.KING } else -1

    val statusText = when (status) {
        ChessEngine.Status.PLAYING -> when {
            thinking -> "Бот думает…"
            check && engine.side == 0 -> "Шах! Ваш ход"
            engine.side == 0 -> "Ваш ход (белые)"
            else -> "Ход бота"
        }
        ChessEngine.Status.CHECKMATE -> if (engine.side == 0) "Мат. Победил бот" else "Мат! Вы победили 🎉"
        ChessEngine.Status.STALEMATE -> "Пат - ничья"
        ChessEngine.Status.DRAW_50 -> "Ничья (правило 50 ходов)"
        ChessEngine.Status.DRAW_REPEAT -> "Ничья (повторение)"
        ChessEngine.Status.DRAW_MATERIAL -> "Ничья (мало фигур)"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Шахматы", onBack = onBack)
        BasicText(statusText, style = Ui.h2)

        // Доска: 8-я горизонталь сверху, a1 = индекс 0.
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))
        ) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (file in 0 until 8) {
                        val i = rank * 8 + file
                        val light = (rank + file) % 2 == 1
                        val base = if (light) Color(0xFFEDE3CF) else Color(0xFF8C6B4F)
                        val bg = when {
                            i == selected -> p.accent
                            i == kingSq -> Color(0xFFE85D5D)
                            last != null && (i == last!!.from || i == last!!.to) -> lerpColor(base, p.accent, 0.45f)
                            else -> base
                        }
                        Square(
                            piece = board[i],
                            white = engine.colorOf(board[i]) == 0,
                            type = engine.typeOf(board[i]),
                            bg = bg,
                            target = i in targets,
                            capture = i in targets && board[i] != 0,
                            label = if (file == 0) "${rank + 1}" else if (rank == 0) "${'a' + file}" else null,
                            light = light,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        ) { tap(i) }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Бот:", style = Ui.dim)
            for ((lv, name) in listOf(1 to "Лёгкий", 2 to "Средний", 3 to "Сильный")) {
                Chip(name, active = level == lv, accent = p.accent) {
                    level = lv
                    prefs.putInt("chess_level", lv)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Новая партия", accent = p.accent, enabled = !thinking) {
                engine.reset(); selected = -1; last = null; commit()
            }
            Chip("Отменить ход", enabled = !thinking && engine.moves.isNotEmpty()) {
                val back = if (engine.side == 0) 2 else 1
                engine.load(engine.moves.toList().dropLast(back))
                selected = -1; last = null; commit()
            }
        }
        BasicText("Ходов сделано: ${engine.moves.size}", style = Ui.dim)
    }
}

@Composable
private fun Square(
    piece: Int,
    white: Boolean,
    type: Int,
    bg: Color,
    target: Boolean,
    capture: Boolean,
    label: String?,
    light: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val pop by animateFloatAsState(if (piece != 0) 1f else 0.6f, spring(dampingRatio = 0.5f, stiffness = 600f))
    Box(
        modifier.background(bg).clickable(remember { MutableInteractionSource() }, null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != 0) {
            BasicText(
                glyph(type),
                style = TextStyle(
                    color = if (white) Color(0xFFFFFDF6) else Color(0xFF1E1A24),
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(if (white) Color(0xCC000000) else Color(0x66FFFFFF), Offset(0f, 2f), 3f),
                ),
                modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
            )
        }
        if (target) {
            Box(
                Modifier.size(if (capture) 34.dp else 12.dp).clip(CircleShape)
                    .background(if (capture) Color(0x55E85D5D) else Color(0x66000000))
            )
        }
        if (label != null) {
            BasicText(
                label,
                style = TextStyle(color = if (light) Color(0xFF8C6B4F) else Color(0xFFEDE3CF), fontSize = 9.sp),
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
            )
        }
    }
}

internal fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
