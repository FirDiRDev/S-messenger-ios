// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.games.Match3Engine
import com.stoyko.smessenger.shared.platform.KeyValueStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val GEM = listOf(
    Color(0xFFFF5A6E), Color(0xFFFFB23E), Color(0xFF3DDC84),
    Color(0xFF4DA3FF), Color(0xFFB57BFF), Color(0xFF3EE0D0),
)

private enum class Outcome { NONE, WIN, LOSE }

/** «Три в ряд»: Match3Engine и уровни перенесены из Android без изменений (см. syncPortedSources). */
@Composable
fun Match3Screen(p: SeasonPalette, onBack: () -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(prefs.getInt("m3_level", 1).coerceAtLeast(1)) }
    var attempt by remember { mutableStateOf(0) }
    val engine = remember(level, attempt) { Match3Engine(level) }
    val n = engine.rows * engine.cols

    val offX = remember(engine) { List(n) { Animatable(0f) } }
    val offY = remember(engine) { List(n) { Animatable(0f) } }
    val scale = remember(engine) { List(n) { Animatable(1f) } }
    var shownGem by remember(engine) { mutableStateOf(engine.gem.copyOf()) }
    var shownSp by remember(engine) { mutableStateOf(engine.special.copyOf()) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember(engine) { mutableStateOf(-1) }
    var hint by remember(engine) { mutableStateOf<IntArray?>(null) }
    var combo by remember { mutableStateOf("") }
    var outcome by remember(engine) { mutableStateOf(Outcome.NONE) }

    fun sync() {
        shownGem = engine.gem.copyOf()
        shownSp = engine.special.copyOf()
        tick++
    }

    suspend fun cascade() {
        var step = 0
        while (true) {
            val clear = engine.resolveBooster() ?: engine.resolve() ?: break
            step++
            combo = if (step > 1) "Каскад ×$step  +${clear.gained}" else "+${clear.gained}"
            // Исчезновение (на экране ещё старое поле).
            coroutineScope { for (i in clear.cells) launch { scale[i].animateTo(0f, tween(170)) } }
            val (moved, spawned) = engine.gravity()
            for (m in moved) offY[m[1]].snapTo((engine.row(m[0]) - engine.row(m[1])).toFloat())
            for (s in spawned) offY[s[0]].snapTo(-s[1].toFloat())
            for (a in scale) a.snapTo(1f)
            sync()
            // Падение с пружинкой.
            coroutineScope {
                for (a in offY) if (a.value != 0f) launch { a.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 420f)) }
            }
        }
        if (!engine.goalsDone && engine.moves > 0 && engine.findMove() == null) {
            engine.shuffle()
            combo = "Перемешали"
            sync()
        }
        if (engine.goalsDone && !engine.finished) {
            val bonus = engine.finish()
            combo = "Победа! +$bonus за ходы"
            outcome = Outcome.WIN
            prefs.putInt("m3_level", level + 1)
        } else if (engine.moves <= 0 && !engine.goalsDone) {
            outcome = Outcome.LOSE
        }
        sync()
    }

    fun trySwap(a: Int, b: Int) {
        if (busy || outcome != Outcome.NONE || a !in 0 until n || b !in 0 until n) return
        busy = true
        selected = -1
        hint = null
        scope.launch {
            val dx = (engine.col(b) - engine.col(a)).toFloat()
            val dy = (engine.row(b) - engine.row(a)).toFloat()
            if (engine.swap(a, b)) {
                // Кристаллы уже поменялись в движке - на экране плавно «доезжают».
                sync()
                offX[a].snapTo(dx); offY[a].snapTo(dy)
                offX[b].snapTo(-dx); offY[b].snapTo(-dy)
                coroutineScope {
                    for (i in intArrayOf(a, b)) {
                        launch { offX[i].animateTo(0f, tween(150)) }
                        launch { offY[i].animateTo(0f, tween(150)) }
                    }
                }
                cascade()
            } else {
                // Ход не дал фигуры - качнуть туда-обратно.
                coroutineScope {
                    launch { offX[a].animateTo(dx * 0.35f, tween(90)); offX[a].animateTo(0f, spring(0.4f, 600f)) }
                    launch { offY[a].animateTo(dy * 0.35f, tween(90)); offY[a].animateTo(0f, spring(0.4f, 600f)) }
                    launch { offX[b].animateTo(-dx * 0.35f, tween(90)); offX[b].animateTo(0f, spring(0.4f, 600f)) }
                    launch { offY[b].animateTo(-dy * 0.35f, tween(90)); offY[b].animateTo(0f, spring(0.4f, 600f)) }
                }
            }
            busy = false
        }
    }

    fun tap(i: Int) {
        if (busy || outcome != Outcome.NONE || i !in 0 until n) return
        when {
            selected == i && engine.special[i] != 0 -> {
                // Второе касание по бонусу - взорвать.
                if (engine.activate(i)) {
                    busy = true; selected = -1
                    scope.launch { cascade(); busy = false }
                } else selected = -1
            }
            selected >= 0 && engine.adjacent(selected, i) -> trySwap(selected, i)
            engine.movable(i) -> selected = if (selected == i) -1 else i
            else -> selected = -1
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick // чтение tick - перерисовка после изменений движка

    val pulse = rememberInfiniteTransition(label = "hint")
    val hintScale by pulse.animateFloat(1f, 1.15f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "hs")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("Уровень $level", onBack = onBack)

        // Цели и ходы.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("${engine.moves}", style = TextStyle(color = p.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                BasicText("ходов", style = Ui.dim)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (g in engine.goalKind.indices) {
                    val k = engine.goalKind[g]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            when (k) {
                                in 0 until 6 -> Canvas(Modifier.fillMaxSize()) { drawGem(k, 0) }
                                Match3Engine.LEAF_GOAL -> BasicText("🍃", style = TextStyle(fontSize = 18.sp))
                                Match3Engine.ICE_GOAL -> BasicText("🧊", style = TextStyle(fontSize = 18.sp))
                                Match3Engine.GOLD_GOAL -> BasicText("🪙", style = TextStyle(fontSize = 18.sp))
                                else -> BasicText("⭐", style = TextStyle(fontSize = 18.sp))
                            }
                        }
                        val left = engine.goalLeft(g)
                        BasicText(
                            if (left == 0) "✓" else "$left",
                            style = TextStyle(color = if (left == 0) Color(0xFF5BD69A) else Ui.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                BasicText("${engine.score}", style = Ui.h2)
                BasicText("очков", style = Ui.dim)
            }
        }

        // Поле.
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .background(Color(0x33000000))
                .pointerInput(engine) {
                    detectTapGestures { off ->
                        val c = (off.x / (size.width / engine.cols.toFloat())).toInt().coerceIn(0, engine.cols - 1)
                        val r = (off.y / (size.height / engine.rows.toFloat())).toInt().coerceIn(0, engine.rows - 1)
                        tap(engine.idx(r, c))
                    }
                }
                .pointerInput(engine) {
                    var start = -1
                    var acc = Offset.Zero
                    var fired = false
                    detectDragGestures(
                        onDragStart = { off ->
                            val c = (off.x / (size.width / engine.cols.toFloat())).toInt().coerceIn(0, engine.cols - 1)
                            val r = (off.y / (size.height / engine.rows.toFloat())).toInt().coerceIn(0, engine.rows - 1)
                            start = engine.idx(r, c)
                            acc = Offset.Zero
                            fired = false
                        },
                        onDrag = { change, d ->
                            change.consume()
                            acc += d
                            val cell = size.width / engine.cols.toFloat()
                            if (!fired && start >= 0 && acc.getDistance() > cell * 0.35f) {
                                fired = true
                                val horiz = abs(acc.x) > abs(acc.y)
                                val r = engine.row(start)
                                val c = engine.col(start)
                                val (r2, c2) = if (horiz) r to (c + if (acc.x > 0) 1 else -1) else (r + if (acc.y > 0) 1 else -1) to c
                                if (r2 in 0 until engine.rows && c2 in 0 until engine.cols) trySwap(start, engine.idx(r2, c2))
                            }
                        },
                    )
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                for (r in 0 until engine.rows) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (c in 0 until engine.cols) {
                            val i = engine.idx(r, c)
                            val isHint = hint?.contains(i) == true
                            Cell(
                                kind = shownGem[i],
                                sp = shownSp[i],
                                hole = engine.hole[i],
                                ice = engine.ice[i],
                                leaf = engine.leaf[i],
                                gold = engine.gold[i],
                                selected = i == selected,
                                accent = p.accent,
                                modifier = Modifier.weight(1f).fillMaxSize().graphicsLayer {
                                    translationX = offX[i].value * size.width
                                    translationY = offY[i].value * size.height
                                    val s = scale[i].value * (if (isHint) hintScale else 1f) * (if (i == selected) 1.08f else 1f)
                                    scaleX = s
                                    scaleY = s
                                },
                            )
                        }
                    }
                }
            }
        }

        BasicText(combo, style = TextStyle(color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))

        when (outcome) {
            Outcome.WIN -> Panel {
                BasicText("Уровень пройден! " + "★".repeat(engine.stars) + "☆".repeat(3 - engine.stars), style = Ui.h2)
                BasicText("Очки: ${engine.score}", style = Ui.dim)
                Chip("Дальше: уровень ${level + 1}", active = true, accent = p.accent) { level += 1; combo = "" }
            }
            Outcome.LOSE -> Panel {
                BasicText("Ходы закончились", style = Ui.h2)
                BasicText("Не хватило совсем чуть-чуть - попробуйте ещё раз.", style = Ui.dim)
                Chip("Ещё раз", active = true, accent = p.accent) { attempt++; combo = "" }
            }
            Outcome.NONE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Подсказка", enabled = !busy) {
                    hint = engine.findMove()
                    scope.launch { delay(2500); hint = null }
                }
                Chip("Заново", enabled = !busy) { attempt++; combo = "" }
            }
        }
        BasicText("Меняйте соседние кристаллы касанием или свайпом. Бонус - коснитесь дважды.", style = Ui.dim)
    }
}

@Composable
private fun Cell(
    kind: Int,
    sp: Int,
    hole: Boolean,
    ice: Int,
    leaf: Int,
    gold: Int,
    selected: Boolean,
    accent: Color,
    modifier: Modifier,
) {
    if (hole) {
        Box(modifier)
        return
    }
    val bg = when {
        leaf >= 2 -> Color(0x8832C46A)
        leaf == 1 -> Color(0x5532C46A)
        else -> Color(0x14FFFFFF)
    }
    Box(
        modifier.padding(1.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(8.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (gold > 0) {
            Box(
                Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFE082), Color(0xFFD4A017), Color(0xFF9C6F00)))),
                contentAlignment = Alignment.Center,
            ) { BasicText("$gold", style = TextStyle(color = Color(0xFF5A3D00), fontSize = 14.sp, fontWeight = FontWeight.Bold)) }
        } else if (kind >= 0) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) { drawGem(kind, sp) }
        }
        if (ice > 0) {
            Box(
                Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(7.dp))
                    .background(Color(if (ice >= 2) 0x99BFE6FF else 0x66BFE6FF))
                    .border(1.dp, Color(0xCCFFFFFF), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.BottomEnd,
            ) {
                if (ice >= 2) BasicText("$ice", style = TextStyle(color = Color(0xFF0E3A5C), fontSize = 10.sp), modifier = Modifier.padding(2.dp))
            }
        }
    }
}

private fun polygon(c: Offset, r: Float, n: Int, startDeg: Float = -90f): Path = Path().apply {
    for (k in 0 until n) {
        val a = (startDeg + 360f * k / n) * PI.toFloat() / 180f
        val x = c.x + r * cos(a)
        val y = c.y + r * sin(a)
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun star(c: Offset, r1: Float, r2: Float, n: Int): Path = Path().apply {
    for (k in 0 until n * 2) {
        val r = if (k % 2 == 0) r1 else r2
        val a = (-90f + 180f * k / n) * PI.toFloat() / 180f
        val x = c.x + r * cos(a)
        val y = c.y + r * sin(a)
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Кристалл: у каждого вида своя форма и цвет (видно и при дальтонизме), бонусы - поверх. */
private fun DrawScope.drawGem(kind: Int, sp: Int) {
    val w = size.width
    val h = size.height
    val c = center
    if (sp == Match3Engine.RAINBOW) {
        drawCircle(Brush.sweepGradient(GEM + GEM.first(), c), radius = w * 0.46f)
        drawCircle(Color.White, radius = w * 0.14f)
        return
    }
    val col = GEM[kind.mod(GEM.size)]
    val brush = Brush.radialGradient(
        listOf(lerpColor(col, Color.White, 0.55f), col, lerpColor(col, Color.Black, 0.35f)),
        center = Offset(w * 0.35f, h * 0.3f),
        radius = w * 0.85f,
    )
    when (kind.mod(6)) {
        0 -> drawCircle(brush, radius = w * 0.44f)
        1 -> drawPath(polygon(c, w * 0.5f, 4), brush)
        2 -> drawRoundRect(brush, topLeft = Offset(w * 0.08f, h * 0.08f), size = Size(w * 0.84f, h * 0.84f), cornerRadius = CornerRadius(w * 0.22f))
        3 -> drawPath(polygon(Offset(c.x, c.y + h * 0.06f), w * 0.52f, 3), brush)
        4 -> drawPath(polygon(c, w * 0.48f, 6, 0f), brush)
        else -> drawPath(star(c, w * 0.52f, w * 0.25f, 5), brush)
    }
    // Блик.
    drawCircle(Color.White.copy(alpha = 0.45f), radius = w * 0.09f, center = Offset(w * 0.36f, h * 0.32f))
    when (sp) {
        Match3Engine.H -> for (dy in listOf(-0.12f, 0.12f)) drawLine(Color.White, Offset(w * 0.1f, h * (0.5f + dy)), Offset(w * 0.9f, h * (0.5f + dy)), strokeWidth = w * 0.07f)
        Match3Engine.V -> for (dx in listOf(-0.12f, 0.12f)) drawLine(Color.White, Offset(w * (0.5f + dx), h * 0.1f), Offset(w * (0.5f + dx), h * 0.9f), strokeWidth = w * 0.07f)
        Match3Engine.BOMB -> {
            drawCircle(Color(0xFF2A1A3A), radius = w * 0.2f)
            drawCircle(Color.White, radius = w * 0.2f, style = Stroke(width = w * 0.06f))
        }
    }
}
