// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.vo.FriendRequest

@Dao
interface FriendRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(friendRequest: FriendRequest)

    @Delete
    fun delete(friendRequest: FriendRequest)

    @Query("SELECT * FROM friend_requests")
    fun loadAll(): Flow<List<FriendRequest>>

    @Query("SELECT * FROM friend_requests WHERE public_key == :publicKey")
    // null - запрос уже удалён (принят/отклонён). Раньше здесь был не-null тип, и приложение падало.
    fun load(publicKey: String): Flow<FriendRequest?>

    @Query("SELECT COUNT(public_key) FROM friend_requests")
    fun count(): Int
}
