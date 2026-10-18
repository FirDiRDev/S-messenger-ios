// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

/**
 * Карточка запроса в друзья (ник и маленькая аватарка внутри текста запроса - у Tox в запросе
 * есть только ключ и текст). Модуль приложения подставляет сюда свои функции при запуске.
 */
object FriendRequestHooks {
    /** Текст запроса → текст с карточкой отправителя (ник + аватарка). */
    @Volatile var decorate: ((String) -> String)? = null

    /** Текст запроса без карточки (для первого сообщения в чате). */
    @Volatile var clean: ((String) -> String)? = null

    /** Запрос принят: сохранить ник и аватарку из карточки в новый контакт. */
    @Volatile var onAccepted: ((publicKey: String, rawMessage: String) -> Unit)? = null
}
