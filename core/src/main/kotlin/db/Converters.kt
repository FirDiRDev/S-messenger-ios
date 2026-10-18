// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.TypeConverter
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.core.vo.UserStatus

class Converters private constructor() {
    companion object {
        @TypeConverter
        @JvmStatic
        fun toStatus(status: Int): UserStatus = UserStatus.entries[status]

        @TypeConverter
        @JvmStatic
        fun fromStatus(status: UserStatus): Int = status.ordinal

        @TypeConverter
        @JvmStatic
        fun toConnection(connection: Int): ConnectionStatus = ConnectionStatus.entries[connection]

        @TypeConverter
        @JvmStatic
        fun fromConnection(connection: ConnectionStatus): Int = connection.ordinal

        @TypeConverter
        @JvmStatic
        fun toSender(sender: Int): Sender = Sender.entries[sender]

        @TypeConverter
        @JvmStatic
        fun fromSender(sender: Sender): Int = sender.ordinal

        @TypeConverter
        @JvmStatic
        fun toMessageType(type: Int): MessageType = MessageType.entries[type]

        @TypeConverter
        @JvmStatic
        fun fromMessageType(type: MessageType): Int = type.ordinal
    }
}
