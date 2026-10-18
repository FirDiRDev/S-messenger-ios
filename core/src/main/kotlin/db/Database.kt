// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.User

@Database(
    entities = [
        Contact::class, FileTransfer::class, FriendRequest::class, Message::class, User::class,
        GroupChat::class, GroupMessage::class,
    ],
    version = 7,
    // Схему не выгружаем в файл: debug- и release-сборки писали один и тот же JSON одновременно,
    // и сборка падала с «Expected start of the object ... EOF».
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class Database : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun friendRequestDao(): FriendRequestDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
}
