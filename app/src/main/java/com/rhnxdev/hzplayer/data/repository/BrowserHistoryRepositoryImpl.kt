package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.BrowserHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.BrowserHistoryEntity
import com.rhnxdev.hzplayer.domain.model.BrowserHistoryItem
import com.rhnxdev.hzplayer.domain.model.UrlSuggestion
import com.rhnxdev.hzplayer.domain.repository.BrowserHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserHistoryRepositoryImpl @Inject constructor(
    private val browserHistoryDao: BrowserHistoryDao,
) : BrowserHistoryRepository {

    override fun getAllHistory(): Flow<List<BrowserHistoryItem>> {
        return browserHistoryDao.getAllHistory().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun searchHistory(query: String): Flow<List<BrowserHistoryItem>> {
        return browserHistoryDao.searchHistory(query).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getUrlSuggestions(query: String, limit: Int): Flow<List<UrlSuggestion>> {
        return browserHistoryDao.getUrlSuggestions(query, limit).map { list ->
            list.map { row ->
                UrlSuggestion(
                    url = row.url,
                    title = row.title,
                    lastVisited = row.lastVisited,
                    visitCount = row.visitCount,
                )
            }
        }
    }

    override suspend fun addHistory(url: String, title: String, timestamp: Long): Long {
        if (url.isBlank() || url == "about:blank") return -1
        val entity = BrowserHistoryEntity(
            url = url,
            title = title.ifBlank { url },
            timestamp = timestamp,
        )
        return browserHistoryDao.insertHistory(entity)
    }

    override suspend fun deleteHistoryItem(id: Long) {
        browserHistoryDao.deleteHistoryItem(id)
    }

    override suspend fun clearAllHistory() {
        browserHistoryDao.clearAllHistory()
    }

    private fun BrowserHistoryEntity.toDomain() = BrowserHistoryItem(
        id = id,
        url = url,
        title = title,
        timestamp = timestamp,
    )
}
