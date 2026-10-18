// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.shared.audio.ThemeMusic

/** Экраны iOS-версии. */
sealed class Screen {
    data object Chats : Screen()
    data object Games : Screen()
    data object Settings : Screen()
    data object Chess : Screen()
    data object Match3 : Screen()
    data object Durak : Screen()
    data object Blackjack : Screen()
    data object Snake : Screen()
}

private val tabs = listOf(
    Triple(Screen.Chats, "💬", "Чаты"),
    Triple(Screen.Games, "🎮", "Игры"),
    Triple(Screen.Settings, "⚙️", "Настройки"),
)

@Composable
fun App() {
    var season by remember { mutableStateOf(ThemeMusic.season) }
    val palette = paletteOf(season)
    var tab by remember { mutableStateOf<Screen>(Screen.Chats) }
    var screen by remember { mutableStateOf<Screen>(Screen.Chats) }
    val isRoot = screen == tab

    Box(Modifier.fillMaxSize().background(Ui.bg(palette))) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val deeper = targetState !in listOf(Screen.Chats, Screen.Games, Screen.Settings)
                    if (deeper) {
                        (slideInHorizontally(tween(260)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut())
                    } else {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                    }
                },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.Chats -> ChatsScreen(palette)
                    Screen.Games -> GamesScreen(palette) { screen = it }
                    Screen.Settings -> SettingsScreen(palette) { season = ThemeMusic.season }
                    Screen.Chess -> ChessScreen(palette) { screen = Screen.Games }
                    Screen.Match3 -> Match3Screen(palette) { screen = Screen.Games }
                    Screen.Durak -> DurakScreen(palette) { screen = Screen.Games }
                    Screen.Blackjack -> BlackjackScreen(palette) { screen = Screen.Games }
                    Screen.Snake -> SnakeScreen(palette) { screen = Screen.Games }
                }
            }
            if (isRoot) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(22.dp)).background(Color(0x22FFFFFF)).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for ((t, icon, label) in tabs) {
                        val sel = tab == t
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .background(if (sel) palette.accent.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable(remember { MutableInteractionSource() }, null) { tab = t; screen = t }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BasicText(icon, style = TextStyle(fontSize = 20.sp))
                            BasicText(
                                label,
                                style = TextStyle(
                                    color = if (sel) palette.accent else Ui.Dim,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
