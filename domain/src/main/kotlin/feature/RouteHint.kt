// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

/**
 * Каким путём сейчас идёт ядро Tox (выставляют «Каналы связи» приложения).
 * [torOnly] - ядро работает через Tor: адреса узлов не ищем через обычный DNS и DNS-over-HTTPS
 * мимо Tor (иначе провайдер видел бы запросы адресов узлов Tox) - только известные IP.
 */
object RouteHint {
    @Volatile
    var torOnly = false
}
