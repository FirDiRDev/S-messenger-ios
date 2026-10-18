// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Разрешения для каналов без интернета - спрашиваются только в момент, когда пользователь
 * включает Bluetooth-канал или Wi-Fi Direct (не при запуске приложения).
 */
object TransportPermissions {
    const val REQUEST_CODE = 0x5B7

    /** Bluetooth: Android 12+ - «Устройства поблизости», раньше - геолокация (для поиска устройств). */
    fun bluetooth(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Wi-Fi Direct: Android 13+ - «Устройства Wi-Fi поблизости», раньше - геолокация. */
    fun wifiDirect(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(ctx: Context, perms: Array<String>): Array<String> =
        perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun hasBluetooth(ctx: Context) = missing(ctx, bluetooth()).isEmpty()

    fun hasWifiDirect(ctx: Context) = missing(ctx, wifiDirect()).isEmpty()

    /** Запросить недостающие. true - всё уже есть. */
    fun ensure(ctx: Context, perms: Array<String>): Boolean {
        val need = missing(ctx, perms)
        if (need.isEmpty()) return true
        val a = ctx as? Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? Activity ?: return false
        runCatching { ActivityCompat.requestPermissions(a, need, REQUEST_CODE) }
        return false
    }
}
