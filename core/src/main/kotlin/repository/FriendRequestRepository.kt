// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.db.FriendRequestDao
import com.stoyko.smessenger.core.vo.FriendRequest

@Singleton
class FriendRequestRepository @Inject internal constructor(private val friendRequestDao: FriendRequestDao) {
    fun add(friendRequest: FriendRequest) = friendRequestDao.save(friendRequest)

    fun delete(friendRequest: FriendRequest) = friendRequestDao.delete(friendRequest)

    fun getAll(): Flow<List<FriendRequest>> = friendRequestDao.loadAll()

    fun get(publicKey: String): Flow<FriendRequest?> = friendRequestDao.load(publicKey)

    fun count(): Int = friendRequestDao.count()
}
