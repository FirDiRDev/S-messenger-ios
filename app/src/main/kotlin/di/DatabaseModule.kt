// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import com.stoyko.smessenger.core.db.ALL_MIGRATIONS
import com.stoyko.smessenger.core.db.ContactDao
import com.stoyko.smessenger.core.db.Database
import com.stoyko.smessenger.core.db.FileTransferDao
import com.stoyko.smessenger.core.db.FriendRequestDao
import com.stoyko.smessenger.core.db.GroupDao
import com.stoyko.smessenger.core.db.MessageDao
import com.stoyko.smessenger.core.db.UserDao

@Module
class DatabaseModule {
    @Singleton
    @Provides
    fun provideDatabase(appContext: Context): Database =
        Room.databaseBuilder(appContext, Database::class.java, "core_db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
}

@Module
class DaoModule {
    @Singleton
    @Provides
    internal fun provideContactDao(db: Database): ContactDao = db.contactDao()

    @Singleton
    @Provides
    internal fun provideFileTransferDao(db: Database): FileTransferDao = db.fileTransferDao()

    @Singleton
    @Provides
    internal fun provideFriendRequestDao(db: Database): FriendRequestDao = db.friendRequestDao()

    @Singleton
    @Provides
    internal fun provideMessageDao(db: Database): MessageDao = db.messageDao()

    @Singleton
    @Provides
    internal fun provideUserDao(db: Database): UserDao = db.userDao()

    @Singleton
    @Provides
    internal fun provideGroupDao(db: Database): GroupDao = db.groupDao()
}
