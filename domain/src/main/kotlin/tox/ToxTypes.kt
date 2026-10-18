// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import com.stoyko.smessenger.core.vo.PublicKey

@JvmInline
value class ToxID(private val value: String) {
    fun bytes() = value.hexToBytes()
    fun string() = value

    fun toPublicKey() = PublicKey(value.dropLast(12))

    companion object {
        fun fromBytes(toxId: ByteArray) = ToxID(toxId.bytesToHex())
    }
}

data class BootstrapNode(
    val address: String,
    val port: Int,
    val publicKey: PublicKey,
    // TCP-порты релея. 443 ставим первым: он чаще всего открыт в фильтрующих сетях.
    val tcpPorts: List<Int> = emptyList(),
    val udp: Boolean = true,
)
