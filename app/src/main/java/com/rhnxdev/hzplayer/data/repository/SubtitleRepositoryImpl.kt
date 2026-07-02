package com.rhnxdev.hzplayer.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.rhnxdev.hzplayer.data.datasource.remote.OpenSubtitlesApi
import com.rhnxdev.hzplayer.domain.repository.SubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val openSubtitlesApi: OpenSubtitlesApi,
) : SubtitleRepository {

    override suspend fun search(query: String, apiKey: String): Result<List<SubtitleRepository.SearchResult>> {
        val result = openSubtitlesApi.searchSubtitles(query, apiKey)
        return result.map { list ->
            list.map { apiResult ->
                SubtitleRepository.SearchResult(
                    id = apiResult.id,
                    fileId = apiResult.fileId,
                    language = apiResult.language,
                    releaseName = apiResult.releaseName,
                    downloadCount = apiResult.downloadCount,
                )
            }
        }
    }

    override suspend fun download(fileId: Long, fileName: String, apiKey: String): Result<Uri> {
        return try {
            val downloadResult = openSubtitlesApi.getDownloadLink(fileId, apiKey)
                .getOrElse { return Result.failure(it) }

            val bytes = openSubtitlesApi.downloadSubtitleContent(downloadResult.link)
                ?: return Result.failure(Exception("Failed to download subtitle content"))

            val cacheDir = File(appContext.cacheDir, "subtitles").also { it.mkdirs() }
            val outputName = fileName.substringAfterLast('/').substringBefore('?').let { name ->
                if (name.isBlank()) "subtitle_${fileId}.srt"
                else if (!name.contains('.')) "$name.srt"
                else name
            }
            val outputFile = File(cacheDir, outputName)
            outputFile.writeBytes(bytes)

            Result.success(outputFile.toUri())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
