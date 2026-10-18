// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.vo.FT_REJECTED
import com.stoyko.smessenger.core.vo.FileTransfer

@Dao
interface FileTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(fileTransfer: FileTransfer): Long

    @Query("DELETE FROM file_transfers WHERE id == :id")
    fun delete(id: Int)

    @Query("SELECT * FROM file_transfers WHERE public_key == :publicKey")
    fun load(publicKey: String): Flow<List<FileTransfer>>

    @Query("SELECT * FROM file_transfers WHERE id == :id")
    fun load(id: Int): Flow<FileTransfer?>

    @Query("UPDATE file_transfers SET progress = :progress WHERE id == :id AND progress != :rejected")
    fun updateProgress(id: Int, progress: Long, rejected: Long = FT_REJECTED)

    /** Смена состояния без защиты от «отклонено» - при повторном предложении того же файла строка оживает. */
    @Query("UPDATE file_transfers SET progress = :progress WHERE id == :id")
    fun forceProgress(id: Int, progress: Long)

    /** Строка передачи сразу (не поток) - для проверок в колбэках ядра. */
    @Query("SELECT * FROM file_transfers WHERE id == :id")
    fun loadNow(id: Int): FileTransfer?

    /** Последний входящий файл от собеседника с тем же именем и размером (защита от дублей). */
    @Query(
        "SELECT * FROM file_transfers WHERE public_key == :publicKey AND outgoing = 0 AND file_kind = 0 " +
            "AND file_name == :name AND file_size == :size ORDER BY id DESC LIMIT 1",
    )
    fun lastIncoming(publicKey: String, name: String, size: Long): FileTransfer?

    @Query("UPDATE file_transfers SET destination = :destination WHERE id == :id")
    fun setDestination(id: Int, destination: String)

    /**
     * После перезапуска: исходящие незаконченные - на паузу (дошлются с места обрыва при подключении),
     * входящие начатые - остаются как есть (докачаются), не начатые входящие - отклонены.
     */
    @Query(
        "UPDATE file_transfers SET progress = CASE " +
            "WHEN outgoing = 1 AND progress != -2 THEN -1 " +
            "WHEN outgoing = 0 AND progress > 0 THEN progress " +
            "ELSE -2 END WHERE progress < file_size",
    )
    fun resetTransientData()

    /** Общий поиск файлов по имени и расширению (новые первыми). */
    @Query("SELECT * FROM file_transfers WHERE file_name LIKE '%' || :q || '%' ORDER BY id DESC LIMIT :limit")
    fun search(q: String, limit: Int): List<FileTransfer>

    /** Исходящие, которые ещё не дошли (их файлы-копии нельзя удалять при очистке). */
    @Query("SELECT destination FROM file_transfers WHERE outgoing = 1 AND progress != :rejected AND progress < file_size")
    fun pendingOutgoing(rejected: Long = FT_REJECTED): List<String>
}
