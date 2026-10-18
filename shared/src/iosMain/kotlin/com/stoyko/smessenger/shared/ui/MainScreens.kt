// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stoyko.smessenger.domain.tox.TOX_ID_LENGTH
import com.stoyko.smessenger.domain.tox.ToxID
import com.stoyko.smessenger.domain.tox.ToxIdValidator
import com.stoyko.smessenger.shared.IosPlatform
import com.stoyko.smessenger.shared.audio.Season
import com.stoyko.smessenger.shared.audio.ThemeMusic
import com.stoyko.smessenger.shared.bubbles.BubbleCallbacks
import com.stoyko.smessenger.shared.bubbles.BubbleCodec
import com.stoyko.smessenger.shared.bubbles.HtmlBubbleView
import com.stoyko.smessenger.shared.platform.KeyValueStore
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

private const val DEMO_BUBBLE =
    "<div class=card><div class=pic>🎁</div><div class=t>HTML-пузырь</div>" +
        "<div class=m>Тот же набор стилей и кнопок, что в Android-версии</div>" +
        "<canvas id=c></canvas><script>chart('c',[3,5,4,8,6,9],['Пн','Вт','Ср','Чт','Пт','Сб'],{type:'line',live:1})</script>" +
        "<div class=row><button class=\"b g\" data-reply=\"Работает!\">Работает</button>" +
        "<button class=\"b s\" data-copy=\"S-messenger\">Копировать</button></div></div>"

// ------------------------------------------------------------------------------------- Чаты

@Composable
fun ChatsScreen(p: SeasonPalette) {
    val net by IosPlatform.network.state.collectAsState()
    val replies = remember { mutableStateListOf<String>() }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val bubbleHtml = remember { BubbleCodec.unpack(BubbleCodec.pack(DEMO_BUBBLE)) ?: DEMO_BUBBLE }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("S-messenger")
        BasicText(
            "${p.emoji} " + (if (!net.online) "нет сети" else if (net.expensive) "сотовая сеть" else "Wi-Fi") +
                (if (net.constrained) " · экономия данных" else ""),
            style = Ui.dim,
        )

        Panel {
            BasicText("Переписка на iOS - следующий этап", style = Ui.h2)
            BasicText(
                "Сообщения в S-messenger идут через Tox (P2P). Для iOS его нужно собрать из исходников " +
                    "c-toxcore - это следующий шаг переноса. Игры, сезонные темы, музыка и HTML-пузыри уже работают.",
                style = Ui.dim,
            )
        }

        AddContactCheck(p)

        Panel {
            BasicText("HTML-пузыри", style = Ui.h2)
            HtmlBubbleView(
                html = bubbleHtml,
                callbacks = BubbleCallbacks(
                    onReply = { replies += it },
                    onOpenUrl = { pendingUrl = it },
                    onToast = { toast = it },
                ),
            )
            toast?.let { BasicText(it, style = TextStyle(color = p.accent, fontSize = 13.sp)) }
            pendingUrl?.let { url ->
                BasicText("Открыть ссылку?\n$url", style = Ui.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Открыть", active = true, accent = p.accent) {
                        NSURL.URLWithString(url)?.let {
                            UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null)
                        }
                        pendingUrl = null
                    }
                    Chip("Отмена") { pendingUrl = null }
                }
            }
            replies.forEach { msg ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    BasicText(
                        msg,
                        style = TextStyle(color = Color.White, fontSize = 15.sp),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(p.accent, p.accent2)))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Проверка Tox ID - тот же ToxIdValidator, что в Android (перенесён в общий код). */
@Composable
private fun AddContactCheck(p: SeasonPalette) {
    var id by remember { mutableStateOf("") }
    val clean = id.filter { !it.isWhitespace() }
    val result = if (clean.isEmpty()) null else ToxIdValidator.validate(ToxID(clean))
    Panel {
        BasicText("Проверить Tox ID", style = Ui.h2)
        BasicTextField(
            value = id,
            onValueChange = { id = it.take(120) },
            textStyle = TextStyle(color = Ui.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22000000))
                .border(1.dp, p.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(10.dp),
            decorationBox = { inner ->
                if (id.isEmpty()) BasicText("Вставьте ID из $TOX_ID_LENGTH символов", style = Ui.dim)
                inner()
            },
        )
        val (text, color) = when (result) {
            null -> "" to Ui.Dim
            ToxIdValidator.Result.NO_ERROR -> "✓ ID правильный. Ключ: ${ToxID(clean).toPublicKey().fingerprint()}…" to Color(0xFF5BD69A)
            ToxIdValidator.Result.INCORRECT_LENGTH -> "Длина ${clean.length}, нужно $TOX_ID_LENGTH" to Color(0xFFFFB74D)
            ToxIdValidator.Result.INVALID_CHECKSUM -> "Неверная контрольная сумма - ID с опечаткой" to Color(0xFFFF6B6B)
            ToxIdValidator.Result.NOT_HEX -> "Допустимы только 0-9 и A-F" to Color(0xFFFF6B6B)
        }
        if (text.isNotEmpty()) BasicText(text, style = TextStyle(color = color, fontSize = 13.sp))
    }
}

// ------------------------------------------------------------------------------------- Игры

@Composable
fun GamesScreen(p: SeasonPalette, open: (Screen) -> Unit) {
    val prefs = remember { KeyValueStore("games") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Игры")
        GameCard("♞", "Шахматы", "Против бота: три уровня, тот же движок, что в Android", p) { open(Screen.Chess) }
        GameCard(
            "💎", "Три в ряд",
            "Уровень ${prefs.getInt("m3_level", 1)} · бонусы, лёд, листья, золото",
            p,
        ) { open(Screen.Match3) }
        GameCard("🃏", "Дурак", "Подкидной, переводной, простой · 2-4 игрока с ботами", p) { open(Screen.Durak) }
        GameCard("♠️", "Блэкджек", "Фишки: ${prefs.getInt("bj_chips", 1000)} · дилер стоит на 17", p) { open(Screen.Blackjack) }
        GameCard("🐍", "Змейка", "Рекорд: ${prefs.getInt("snake_best", 0)} · управление свайпом", p) { open(Screen.Snake) }
        Panel {
            BasicText("Скоро", style = Ui.h2)
            BasicText("Бег по рельсам (3D) - мир и модели уже общие, нужен рендер под iOS.", style = Ui.dim)
        }
    }
}

@Composable
private fun GameCard(icon: String, title: String, sub: String, p: SeasonPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.30f), p.accent2.copy(alpha = 0.18f))))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) { BasicText(icon, style = TextStyle(fontSize = 30.sp, color = Color.White)) }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Ui.h2)
            BasicText(sub, style = Ui.dim)
        }
        BasicText("›", style = TextStyle(color = Ui.Dim, fontSize = 28.sp))
    }
}

// ------------------------------------------------------------------------------------- Настройки

@Composable
fun SettingsScreen(p: SeasonPalette, onSeasonChanged: () -> Unit) {
    var musicOn by remember { mutableStateOf(ThemeMusic.enabled) }
    var volume by remember { mutableStateOf(ThemeMusic.volumePercent) }
    var seasonSel by remember { mutableStateOf(ThemeMusic.seasonOverride) }
    val info = NSBundle.mainBundle.infoDictionary
    val ver = info?.get("CFBundleShortVersionString") as? String ?: "?"
    val build = info?.get("CFBundleVersion") as? String ?: "?"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Настройки")
        Panel {
            BasicText("Оформление · сезонная тема", style = Ui.h2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Авто", active = seasonSel == null, accent = p.accent) {
                    seasonSel = null; ThemeMusic.seasonOverride = null; onSeasonChanged()
                }
                for (s in Season.entries) {
                    Chip(paletteOf(s).emoji, active = seasonSel == s, accent = p.accent) {
                        seasonSel = s; ThemeMusic.seasonOverride = s; onSeasonChanged()
                    }
                }
            }
            BasicText("Сейчас: ${ThemeMusic.season.titleRu}", style = Ui.dim)
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("Музыка темы", style = Ui.body, modifier = Modifier.weight(1f))
                Chip(if (musicOn) "Вкл" else "Выкл", active = musicOn, accent = p.accent) {
                    musicOn = !musicOn
                    ThemeMusic.enabled = musicOn
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("Громкость $volume %", style = Ui.dim, modifier = Modifier.weight(1f))
                Chip("−") { volume = (volume - 10).coerceIn(5, 100); ThemeMusic.volumePercent = volume }
                Chip("+") { volume = (volume + 10).coerceIn(5, 100); ThemeMusic.volumePercent = volume }
            }
            // Полоска громкости.
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
                Box(
                    Modifier.fillMaxWidth(volume / 100f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
                )
            }
            BasicText("Музыка сама замолкает в фоне, при звонке и когда играет другое приложение.", style = Ui.dim)
        }
        Panel {
            BasicText("О приложении", style = Ui.h2)
            BasicText("S-messenger для iOS $ver (сборка $build)", style = Ui.body)
            BasicText("Kotlin Multiplatform + Compose. Общий код с Android-версией.", style = Ui.dim)
        }
        Spacer(Modifier.height(8.dp))
    }
}
