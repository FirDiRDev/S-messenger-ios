// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.shared.audio.Season

/** Цвета сезонной темы (как сезонные темы Android-версии). */
data class SeasonPalette(val bgTop: Color, val bgBottom: Color, val accent: Color, val accent2: Color, val emoji: String)

fun paletteOf(s: Season): SeasonPalette = when (s) {
    Season.SPRING -> SeasonPalette(Color(0xFF13241C), Color(0xFF2A1E36), Color(0xFF5BD69A), Color(0xFFF59AC4), "🌸")
    Season.SUMMER -> SeasonPalette(Color(0xFF0F2430), Color(0xFF2B2A12), Color(0xFFFFC94A), Color(0xFF3ED6C8), "☀️")
    Season.AUTUMN -> SeasonPalette(Color(0xFF241510), Color(0xFF2B1A33), Color(0xFFFF8A3D), Color(0xFFE85D5D), "🍂")
    Season.WINTER -> SeasonPalette(Color(0xFF0E1830), Color(0xFF1E1A3E), Color(0xFF7FB7FF), Color(0xFFB9A6FF), "❄️")
}

object Ui {
    val Text = Color(0xFFF4F2FF)
    val Dim = Color(0xB3F4F2FF)
    val Card = Color(0x17FFFFFF)
    val CardStrong = Color(0x26FFFFFF)

    fun bg(p: SeasonPalette) = Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))

    val title = TextStyle(color = Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val h2 = TextStyle(color = Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(color = Text, fontSize = 15.sp)
    val dim = TextStyle(color = Dim, fontSize = 13.sp)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Кнопка с «пружинкой» при нажатии (как Buttons3d в Android-версии, только проще). */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = Color(0xFF8B7CFF),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f))
    BasicText(
        text,
        style = TextStyle(
            color = if (enabled) Color.White else Ui.Dim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) accent else Ui.CardStrong)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, right: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onBack != null) Chip("‹ Назад", onClick = onBack)
        BasicText(title, style = Ui.title, modifier = Modifier.weight(1f))
        right?.invoke()
    }
}
