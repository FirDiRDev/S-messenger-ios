// SPDX-FileCopyrightText: 2019 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.vo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey
    @ColumnInfo(name = "public_key")
    val publicKey: String,

    @ColumnInfo(name = "name")
    var name: String = "S-messenger",

    @ColumnInfo(name = "status_message")
    var statusMessage: String = "StoykoMessenger",

    @ColumnInfo(name = "status")
    var status: UserStatus = UserStatus.None,

    @ColumnInfo(name = "connection_status")
    var connectionStatus: ConnectionStatus = ConnectionStatus.None,

    @ColumnInfo(name = "password")
    var password: String = "",
)
