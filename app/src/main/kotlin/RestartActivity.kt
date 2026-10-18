// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Надёжный перезапуск (смена темы, языка, «Перезапуск»): это окно работает в ОТДЕЛЬНОМ
 * процессе. Оно завершает основной процесс мессенджера, сразу открывает приложение заново и
 * закрывается само. Раньше приложение закрывалось само и на части телефонов (TECNO, Xiaomi,
 * Huawei) не открывалось снова - приходилось запускать вручную.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) runCatching { Process.killProcess(pid) }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { startActivity(launch) }
        }
        finish()
        overridePendingTransition(0, 0)
        // Процесс перезапуска больше не нужен.
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_PID = "pid"
    }
}
