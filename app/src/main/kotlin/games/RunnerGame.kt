// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.content.Context
import com.stoyko.smessenger.games.runner.RunnerScreen

/**
 * «Бег по рельсам» - бесконечный забег по трём путям в настоящем 3D (OpenGL ES 2.0).
 *
 *  - Свайп влево/вправо - сменить путь, вверх - прыжок, вниз - кувырок (в воздухе - резкий
 *    спуск). По желанию - касание = прыжок.
 *  - Препятствия: низкий барьер и конусы (задел - споткнулся), высокий барьер (только
 *    кувырком), глухой барьер и поезда (только обойти). На стоящие поезда можно забежать
 *    по рампе и бежать по крышам. Встречные поезда едут навстречу - гудок, фары и «!» над путём.
 *  - Сзади бежит обходчик с фонарём и робопсом: первое спотыкание - он совсем рядом, второе
 *    вскоре после первого - поймал.
 *  - Усиления: магнит, ракетный ранец, пружинные кеды, ×2 очки, парящая доска (прощает удар).
 *  - Каждые 10 монет забега = 1 монета мессенджера (остаток копится). Облики бегуна - за монеты.
 *
 * Мир и физика - [com.stoyko.smessenger.games.runner.World], 3D - RunnerRenderer, окно -
 * [RunnerScreen]. Музыка - [RunnerMusic].
 */
object RunnerGame {
    fun best(ctx: Context): Int = RunnerScreen.best(ctx)

    fun show(ctx: Context) = RunnerScreen.show(ctx)

    fun showRules(ctx: Context) = RunnerScreen.showRules(ctx)
}
