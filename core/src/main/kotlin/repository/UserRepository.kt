// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.db.UserDao
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.core.vo.UserStatus

@Singleton
class UserRepository @Inject constructor(private val userDao: UserDao) {
    fun exists(publicKey: String): Boolean = userDao.exists(publicKey)

    fun add(user: User) = userDao.save(user)

    fun update(user: User) = userDao.update(user)

    fun get(publicKey: String): Flow<User?> = userDao.load(publicKey)

    fun updateName(publicKey: String, name: String) = userDao.updateName(publicKey, name)

    fun updateStatusMessage(publicKey: String, statusMessage: String) =
        userDao.updateStatusMessage(publicKey, statusMessage)

    fun updateConnection(publicKey: String, connectionStatus: ConnectionStatus) =
        userDao.updateConnection(publicKey, connectionStatus)

    fun updateStatus(publicKey: String, status: UserStatus) = userDao.updateStatus(publicKey, status)
}
