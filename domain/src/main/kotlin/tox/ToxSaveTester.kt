// SPDX-FileCopyrightText: 2020-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

enum class ToxSaveStatus {
    Ok,
    BadFormat,
    Encrypted,
    OutOfMemory,
    Null,
    PortAlloc,
    BadProxyHost,
    BadProxyPort,
    BadProxyType,
    ProxyNotFound,
    SaveNotFound,
}

/**
 * Проверка профиля и настроек без запуска второго ядра: нативная библиотека
 * допускает только один экземпляр Tox на процесс. Правильность пароля
 * окончательно проверяется при запуске (ToxWrapper).
 */
fun testToxSave(options: SaveOptions, password: String?): ToxSaveStatus {
    when (options.proxyType) {
        ProxyType.None -> Unit
        ProxyType.HTTP -> return ToxSaveStatus.BadProxyType // поддерживается только SOCKS5
        ProxyType.SOCKS5 -> {
            if (options.proxyAddress.isBlank()) return ToxSaveStatus.BadProxyHost
            if (options.proxyPort !in 1..65535) return ToxSaveStatus.BadProxyPort
        }
    }

    val save = options.saveData ?: return ToxSaveStatus.Ok
    return when {
        // Профиль всегда шифруется (без пароля - пустым), поэтому зашифрованный файл - это норма.
        save.isEncryptedToxSave() -> ToxSaveStatus.Ok
        save.isPlainToxSave() -> ToxSaveStatus.Ok
        else -> ToxSaveStatus.BadFormat
    }
}
