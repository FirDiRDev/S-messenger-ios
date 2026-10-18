// SPDX-FileCopyrightText: 2019 aTox contributors
// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.util.Log
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.UserStatus

typealias FriendLosslessPacketHandler = (publicKey: String, data: ByteArray) -> Unit
typealias FileRecvControlHandler = (publicKey: String, fileNo: Int, control: ToxFileControl) -> Unit
typealias FriendStatusMessageHandler = (publicKey: String, message: String) -> Unit
typealias FriendReadReceiptHandler = (publicKey: String, messageId: Int) -> Unit
typealias FriendStatusHandler = (publicKey: String, status: UserStatus) -> Unit
typealias FriendConnectionStatusHandler = (publicKey: String, status: ConnectionStatus) -> Unit
typealias FriendRequestHandler = (publicKey: String, timeDelta: Int, message: String) -> Unit
typealias FriendMessageHandler = (
    publicKey: String,
    messageType: ToxMessageType,
    timeDelta: Int,
    message: String,
) -> Unit
typealias FriendNameHandler = (publicKey: String, newName: String) -> Unit
typealias FileRecvChunkHandler = (publicKey: String, fileNo: Int, position: Long, data: ByteArray) -> Unit
typealias FileRecvHandler = (publicKey: String, fileNo: Int, kind: Int, size: Long, name: String) -> Unit
typealias FriendLossyPacketHandler = (publicKey: String, data: ByteArray) -> Unit
typealias SelfConnectionStatusHandler = (status: ConnectionStatus) -> Unit
typealias FriendTypingHandler = (publicKey: String, isTyping: Boolean) -> Unit
typealias FileChunkRequestHandler = (publicKey: String, fileNo: Int, position: Long, length: Int) -> Unit

private const val TAG = "ToxEventListener"

/**
 * События ядра Tox. Вызывается из ToxWrapper (поток tox_iterate).
 * Номера друзей переводятся в публичные ключи через [contactMapping].
 */
class ToxEventListener @Inject constructor() {
    @Volatile
    var contactMapping: List<Pair<PublicKey, Int>> = listOf()

    var friendLosslessPacketHandler: FriendLosslessPacketHandler = { _, _ -> }
    var fileRecvControlHandler: FileRecvControlHandler = { _, _, _ -> }
    var friendStatusMessageHandler: FriendStatusMessageHandler = { _, _ -> }
    var friendReadReceiptHandler: FriendReadReceiptHandler = { _, _ -> }
    var friendStatusHandler: FriendStatusHandler = { _, _ -> }
    var friendConnectionStatusHandler: FriendConnectionStatusHandler = { _, _ -> }
    var friendRequestHandler: FriendRequestHandler = { _, _, _ -> }
    var friendMessageHandler: FriendMessageHandler = { _, _, _, _ -> }
    var friendNameHandler: FriendNameHandler = { _, _ -> }
    var fileRecvChunkHandler: FileRecvChunkHandler = { _, _, _, _ -> }
    var fileRecvHandler: FileRecvHandler = { _, _, _, _, _ -> }
    var friendLossyPacketHandler: FriendLossyPacketHandler = { _, _ -> }
    var selfConnectionStatusHandler: SelfConnectionStatusHandler = { _ -> }
    var friendTypingHandler: FriendTypingHandler = { _, _ -> }
    var fileChunkRequestHandler: FileChunkRequestHandler = { _, _, _, _ -> }

    private inline fun withKey(friendNo: Long, block: (String) -> Unit) {
        val pk = contactMapping.find { it.second == friendNo.toInt() }?.first?.string()
        if (pk == null) {
            Log.w(TAG, "Event for unknown friend #$friendNo")
            return
        }
        block(pk)
    }

    internal fun friendLosslessPacket(friendNo: Long, data: ByteArray) =
        withKey(friendNo) { friendLosslessPacketHandler(it, data) }

    internal fun fileRecvControl(friendNo: Long, fileNo: Long, control: Int) = withKey(friendNo) {
        fileRecvControlHandler(it, fileNo.toInt(), ToxFileControl.entries.getOrElse(control) { ToxFileControl.CANCEL })
    }

    internal fun friendStatusMessage(friendNo: Long, message: String) =
        withKey(friendNo) { friendStatusMessageHandler(it, message) }

    internal fun friendReadReceipt(friendNo: Long, messageId: Long) =
        withKey(friendNo) { friendReadReceiptHandler(it, messageId.toInt()) }

    internal fun friendStatus(friendNo: Long, status: Int) = withKey(friendNo) {
        friendStatusHandler(it, UserStatus.entries.getOrElse(status) { UserStatus.None })
    }

    internal fun friendConnectionStatus(friendNo: Long, status: Int) = withKey(friendNo) {
        friendConnectionStatusHandler(it, status.toEnumOr(ToxConnection.NONE).toConnectionStatus())
    }

    internal fun friendRequest(publicKeyHex: String, message: String) =
        friendRequestHandler(publicKeyHex.uppercase(), 0, message)

    internal fun friendMessage(friendNo: Long, type: Int, message: String) = withKey(friendNo) {
        friendMessageHandler(it, ToxMessageType.entries.getOrElse(type) { ToxMessageType.NORMAL }, 0, message)
    }

    internal fun friendName(friendNo: Long, newName: String) = withKey(friendNo) { friendNameHandler(it, newName) }

    internal fun fileRecvChunk(friendNo: Long, fileNo: Long, position: Long, data: ByteArray) =
        withKey(friendNo) { fileRecvChunkHandler(it, fileNo.toInt(), position, data) }

    internal fun fileRecv(friendNo: Long, fileNo: Long, kind: Int, fileSize: Long, filename: String) =
        withKey(friendNo) { fileRecvHandler(it, fileNo.toInt(), kind, fileSize, filename) }

    internal fun selfConnectionStatus(connectionStatus: Int) =
        selfConnectionStatusHandler(connectionStatus.toEnumOr(ToxConnection.NONE).toConnectionStatus())

    internal fun friendTyping(friendNo: Long, isTyping: Boolean) =
        withKey(friendNo) { friendTypingHandler(it, isTyping) }

    internal fun fileChunkRequest(friendNo: Long, fileNo: Long, position: Long, length: Long) =
        withKey(friendNo) { fileChunkRequestHandler(it, fileNo.toInt(), position, length.toInt()) }
}
