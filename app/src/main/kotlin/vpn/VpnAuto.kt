// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context

/**
 * Режим «Авто» (обычный вид VPN): человек просто нажимает кнопку, а чем подключаться,
 * приложение решает само:
 *  1) есть рабочие серверы - самый быстрый по последней проверке;
 *  2) серверы есть, но не проверены - выбранный (проверка пойдёт в фоне);
 *  3) серверов нет - обход DPI без сервера; если стратегия ещё ни разу не подбиралась,
 *     подбор лучшей запускается в фоне (прогресс в шторке) и применится сам;
 *  4) нет движка DPI - WARP, если он настроен.
 */
object VpnAuto {
    fun prepare(ctx: Context, store: VpnStore) {
        val ps = store.profiles()
        val fastest = ps.filter { it.ping > 0 }.minByOrNull { it.ping }
        when {
            fastest != null -> {
                store.mode = VpnStore.MODE_SERVER
                store.selectedId = fastest.id
            }
            ps.isNotEmpty() -> {
                store.mode = VpnStore.MODE_SERVER
                // Проверка серверов в фоне: в следующий раз будет выбран самый быстрый.
                // Ответы сохраняются одной записью в конце (раньше - весь список на каждый ответ
                // в главном потоке, с сотнями серверов это подвешивало экран).
                val got = HashMap<String, Long>()
                var left = ps.size
                XrayCore.pingAsync(ctx, ps) { p, ms ->
                    got[p.id] = ms
                    if (--left == 0) store.saveProfiles(store.profiles().map { x -> got[x.id]?.let { x.withPing(it) } ?: x })
                }
            }
            DpiEngine.available(ctx) -> {
                store.mode = VpnStore.MODE_DPI
                if (!store.dpiPicked) DpiBenchmark.runInBackground(ctx)
            }
            store.warpConf.isNotBlank() -> store.mode = VpnStore.MODE_WARP
            else -> store.mode = VpnStore.MODE_DPI
        }
    }
}
