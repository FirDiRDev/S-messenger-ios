// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

// Мультиплатформенные аналоги функций из domain/.../tox/ToxUtil.kt (там - через String.format,
// которого нет в Kotlin/Native). Нужны перенесённым ToxTypes.kt / ToxIdValidator.kt.

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.uppercase().toInt(radix = 16).toByte() }.toByteArray()

fun ByteArray.bytesToHex(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
