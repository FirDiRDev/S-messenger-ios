// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.FileKind
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.UserStatus

fun String.hexToBytes(): ByteArray = chunked(2).map { it.uppercase().toInt(radix = 16).toByte() }.toByteArray()
fun ByteArray.bytesToHex(): String = this.joinToString("") { "%02X".format(it) }
fun ToxUserStatus.toUserStatus(): UserStatus = UserStatus.entries[this.ordinal]
fun ToxConnection.toConnectionStatus(): ConnectionStatus = ConnectionStatus.entries[this.ordinal]
fun ToxMessageType.toMessageType(): MessageType = MessageType.entries[this.ordinal]

fun UserStatus.toToxType(): ToxUserStatus = when (this) {
    UserStatus.None -> ToxUserStatus.NONE
    UserStatus.Away -> ToxUserStatus.AWAY
    UserStatus.Busy -> ToxUserStatus.BUSY
}

fun MessageType.toToxType(): ToxMessageType = when (this) {
    MessageType.Normal -> ToxMessageType.NORMAL
    MessageType.Action -> ToxMessageType.ACTION
    MessageType.FileTransfer -> throw Exception("File transfer message type doesn't exist in Tox")
}

fun FileKind.toToxtype(): Int = when (this) {
    FileKind.Avatar -> ToxFileKind.AVATAR
    FileKind.Data -> ToxFileKind.DATA
}

/** Сохранение Tox, зашифрованное паролем (tox_pass_encrypt), начинается с "toxEsave". */
private val ENCRYPTED_MAGIC = "toxEsave".toByteArray()

/** Незашифрованное сохранение: 4 нулевых байта + 0x15ED1B1F (little-endian). */
private val PLAIN_MAGIC = byteArrayOf(0, 0, 0, 0, 0x1F, 0x1B, 0xED.toByte(), 0x15)

fun ByteArray.isEncryptedToxSave(): Boolean =
    size >= ENCRYPTED_MAGIC.size && ENCRYPTED_MAGIC.indices.all { this[it] == ENCRYPTED_MAGIC[it] }

fun ByteArray.isPlainToxSave(): Boolean =
    size >= PLAIN_MAGIC.size && PLAIN_MAGIC.indices.all { this[it] == PLAIN_MAGIC[it] }
