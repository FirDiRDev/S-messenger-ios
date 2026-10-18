// SPDX-FileCopyrightText: 2019 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.vo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friend_requests")
data class FriendRequest(
    @PrimaryKey
    @ColumnInfo(name = "public_key")
    val publicKey: String,

    @ColumnInfo(name = "message")
    val message: String = "",
)
