// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Масти: 0 ♠, 1 ♣, 2 ♦, 3 ♥ (как в DurakEngine). ︎ - не эмодзи. */
fun suitGlyph(suit: Int) = when (suit) {
    0 -> "♠"
    1 -> "♣"
    2 -> "♦"
    else -> "♥"
} + "︎"

fun suitRed(suit: Int) = suit == 2 || suit == 3

/** Игральная карта: лицом (достоинство + масть) или рубашкой. */
@Composable
fun PlayingCard(
    rank: String,
    suit: Int,
    modifier: Modifier = Modifier,
    faceDown: Boolean = false,
    width: Dp = 52.dp,
    raised: Boolean = false,
    dimmed: Boolean = false,
    highlight: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val lift by animateFloatAsState(if (raised) -14f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f))
    val h = width * 1.42f
    val shape = RoundedCornerShape(width * 0.14f)
    Box(
        modifier
            .size(width, h)
            .graphicsLayer { translationY = lift * density; alpha = if (dimmed) 0.55f else 1f }
            .shadow(4.dp, shape)
            .clip(shape)
            .background(
                if (faceDown) Brush.linearGradient(listOf(Color(0xFF3A2C8C), Color(0xFF6A3FC7)))
                else Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1EDE4)))
            )
            .then(if (highlight != null) Modifier.border(2.dp, highlight, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (faceDown) {
            Box(Modifier.fillMaxSize().padding(4.dp).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(width * 0.1f)))
            BasicText("S", style = TextStyle(color = Color(0x99FFFFFF), fontSize = (width.value * 0.38f).sp, fontWeight = FontWeight.Bold))
        } else {
            val col = if (suitRed(suit)) Color(0xFFD9283C) else Color(0xFF1D1B26)
            Column(Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)) {
                BasicText(rank, style = TextStyle(color = col, fontSize = (width.value * 0.3f).sp, fontWeight = FontWeight.Bold))
                BasicText(suitGlyph(suit), style = TextStyle(color = col, fontSize = (width.value * 0.26f).sp))
            }
            BasicText(
                suitGlyph(suit),
                style = TextStyle(color = col, fontSize = (width.value * 0.55f).sp),
                modifier = Modifier.align(Alignment.Center).padding(top = width * 0.18f),
            )
        }
    }
}
