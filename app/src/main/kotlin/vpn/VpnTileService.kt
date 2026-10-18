// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.stoyko.smessenger.MainActivity
import com.stoyko.smessenger.R

/** Плитка «S-VPN» в шторке быстрых настроек: включить/выключить одним касанием. */
@RequiresApi(24)
class VpnTileService : TileService() {
    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        super.onStartListening()
        VpnState.listen(listener)
        refresh()
    }

    override fun onStopListening() {
        VpnState.unlisten(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (VpnState.status) {
            VpnState.Status.On, VpnState.Status.Connecting -> SmVpnService.stop(this)
            else -> {
                val store = VpnStore(this)
                if (!store.ready() || SmVpnService.prepareIntent(this) != null) {
                    openApp()
                } else {
                    SmVpnService.start(this)
                }
            }
        }
        refresh()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).setAction(SmVpnService.ACTION_OPEN_VPN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 33, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.icon = Icon.createWithResource(this, R.drawable.ic_vpn)
        t.label = getString(R.string.vpn_tile)
        t.state = when (VpnState.status) {
            VpnState.Status.On, VpnState.Status.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when (VpnState.status) {
                VpnState.Status.On -> VpnState.profileName
                VpnState.Status.Connecting -> getString(R.string.vpn_connecting)
                else -> getString(R.string.vpn_off)
            }
        }
        t.updateTile()
    }
}
