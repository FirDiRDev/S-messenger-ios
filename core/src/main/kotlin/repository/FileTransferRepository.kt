// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.db.FileTransferDao
import com.stoyko.smessenger.core.vo.FileTransfer

@Singleton
class FileTransferRepository @Inject internal constructor(private val dao: FileTransferDao) {
    fun add(ft: FileTransfer): Long = dao.save(ft)

    fun delete(id: Int) = dao.delete(id)

    fun get(publicKey: String): Flow<List<FileTransfer>> = dao.load(publicKey)

    fun get(id: Int): Flow<FileTransfer?> = dao.load(id)

    fun setDestination(id: Int, destination: String) = dao.setDestination(id, destination)

    fun updateProgress(id: Int, progress: Long) = dao.updateProgress(id, progress)

    /** Сменить состояние, даже если передача была отклонена (повторное предложение того же файла). */
    fun forceProgress(id: Int, progress: Long) = dao.forceProgress(id, progress)

    /** Строка передачи сразу (не поток). */
    fun getNow(id: Int): FileTransfer? = dao.loadNow(id)

    /** Последний входящий файл с тем же именем и размером от этого собеседника. */
    fun lastIncoming(publicKey: String, name: String, size: Long): FileTransfer? = dao.lastIncoming(publicKey, name, size)

    fun resetTransientData() = dao.resetTransientData()

    /** Пути исходящих файлов, которые ещё отправляются. */
    fun pendingOutgoing(): List<String> = dao.pendingOutgoing()

    /** Файлы личных чатов по имени/расширению. */
    fun search(q: String, limit: Int = 400): List<FileTransfer> = dao.search(q, limit)
}
