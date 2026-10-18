// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

/**
 * Техническая группа из 2.4.0 убрана. Этот объект остался только для того, чтобы у всех, кто
 * успел в неё попасть, приложение один раз само вышло из неё и сняло закрепление.
 */
object TechGroup {
    const val CHAT_ID = "C8D8BCCDDA5162E95F6342888EB23EB156EB8D0449A679FD926A68EE307C5CBF"
    const val RETIRE_KEY = "tech_retired_v2"

    fun isTech(chatId: String) = chatId.equals(CHAT_ID, ignoreCase = true)
}
