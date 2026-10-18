// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.ai.t

/**
 * Сторонний VPN на телефоне (v2rayNG, Hiddify, AmneziaVPN, Happ и т. п.) - 4.10.0.
 *
 * Android держит только один VPN. Раньше кнопка встроенного VPN молча забирала туннель у
 * стороннего клиента (тот отключался), а ядро мессенджера после включения/выключения
 * стороннего VPN ещё минуту держало мёртвые соединения.
 *
 * Теперь: перед включением встроенного VPN - вопрос, если работает сторонний; автозапуск
 * встроенного при работающем стороннем не делается; смена VPN - быстрый перезапуск ядра
 * (см. [com.stoyko.smessenger.transport.TransportManager.onVpnChanged]).
 */
object ForeignVpn {
    /** Есть ли в системе VPN (любой). */
    fun anyVpn(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 23) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    /** Работает VPN другого приложения (наш встроенный и Nova выключены). */
    fun active(ctx: Context): Boolean =
        (VpnState.status == VpnState.Status.Off || VpnState.status == VpnState.Status.Error) && !NovaLink.active && anyVpn(ctx)

    /**
     * Включить встроенный VPN: если работает сторонний - сначала спросить. [go] вызывается,
     * только если человек согласился (или стороннего нет).
     */
    fun confirmThen(ctx: Context, go: () -> Unit) {
        if (!active(ctx)) {
            go()
            return
        }
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle(t("Уже работает другой VPN", "Another VPN is running"))
                .setMessage(
                    t(
                        "На телефоне включён VPN другого приложения. Мессенджер уже работает через него - сообщения, звонки и файлы идут как обычно.\n\nВключить встроенный VPN? Android оставит только один VPN, сторонний отключится.",
                        "A VPN from another app is on. The messenger already works through it - messages, calls and files go as usual.\n\nTurn on the built-in VPN instead? Android keeps only one VPN, the other one will be switched off.",
                    ),
                )
                .setPositiveButton(t("Включить встроенный", "Use built-in")) { _, _ -> go() }
                .setNegativeButton(t("Оставить сторонний", "Keep the other one"), null)
                .show()
        }.onFailure { go() }
    }
}
