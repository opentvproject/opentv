/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.DownloadDao
import app.opentv.data.model.Download
import app.opentv.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/** Reads and writes over the downloads table. Pure data access — the transfer runs in a worker. */
class DownloadRepository(private val dao: DownloadDao) {
    fun observeAll(): Flow<List<Download>> = dao.observeAll()
    fun observeByMediaKey(mediaKey: String): Flow<Download?> = dao.observeByMediaKey(mediaKey)

    suspend fun byId(id: Long): Download? = dao.byId(id)
    suspend fun byMediaKey(mediaKey: String): Download? = dao.byMediaKey(mediaKey)

    suspend fun insert(download: Download): Long = dao.insert(download)
    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.setStatus(id, status)
    suspend fun setProgress(id: Long, downloaded: Long, total: Long) = dao.setProgress(id, downloaded, total)
    suspend fun setFilePath(id: Long, path: String) = dao.setFilePath(id, path)
    suspend fun setPosterPath(id: Long, path: String) = dao.setPosterPath(id, path)
    suspend fun setFailed(id: Long, error: String?) = dao.setFailed(id, DownloadStatus.FAILED, error)
    suspend fun delete(id: Long) = dao.delete(id)
}
