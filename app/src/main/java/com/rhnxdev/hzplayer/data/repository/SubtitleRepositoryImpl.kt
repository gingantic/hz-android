package com.rhnxdev.hzplayer.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.rhnxdev.hzplayer.data.datasource.remote.SubdlApi
import com.rhnxdev.hzplayer.domain.repository.SubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val subdlApi: SubdlApi,
) : SubtitleRepository {

    override suspend fun search(
        query: String,
        apiKey: String,
        language: String?,
        type: String,
        season: Int?,
        episode: Int?,
    ): Result<List<SubtitleRepository.SearchResult>> {
        return subdlApi.searchSubtitles(query, apiKey, language, type, season, episode).map { list ->
            list.map { apiResult ->
                SubtitleRepository.SearchResult(
                    downloadUrl = apiResult.downloadUrl,
                    language = apiResult.language,
                    releaseName = apiResult.releaseName,
                    fps = apiResult.fps,
                )
            }
        }
    }

    override suspend fun download(downloadUrl: String, fileName: String, apiKey: String): Result<Uri> {
        return try {
            var bytes = subdlApi.downloadSubtitleContent(downloadUrl, apiKey)
                ?: return Result.failure(Exception("Failed to download subtitle content"))

            // Most SubDL results are packed ZIPs (only unpack_files gives raw). A
            // packed download is bytes that start with the ZIP magic — extract the
            // first real subtitle entry instead of writing the archive as .srt.
            var extractName = fileName
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
            ) {
                val (entryBytes, entryName) = extractFirstSubtitle(bytes)
                if (entryBytes != null) {
                    bytes = entryBytes
                    if (entryName != null) extractName = entryName
                }
            }

            val cacheDir = File(appContext.cacheDir, "subtitles").also { it.mkdirs() }
            // API-supplied fileName is hostile: strip any path segments so a
            // crafted "../../evil.srt" can't escape cacheDir.
            val outputName = extractName.substringAfterLast('/').substringBefore('?').let { name ->
                if (name.isBlank()) "subtitle.srt"
                else if (!name.contains('.')) "$name.srt"
                else name
            }
            val outputFile = File(cacheDir, outputName)
            // ponytail: guard against canonical-path escape; cheap and fails closed.
            require(outputFile.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) {
                "Refusing subtitle write outside cacheDir: ${outputFile.path}"
            }
            outputFile.writeBytes(bytes)

            Result.success(outputFile.toUri())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Pull the first subtitle-looking entry out of a ZIP archive. Returns the
     * entry's bytes and its name, or (null, null) if nothing usable is found.
     */
    private fun extractFirstSubtitle(zipBytes: ByteArray): Pair<ByteArray?, String?> {
        val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")
        return try {
            val buffer = ByteArray(8 * 1024)
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val ext = entry.name.substringAfterLast('.').lowercase()
                        if (ext in SUB_EXT) {
                            val out = java.io.ByteArrayOutputStream()
                            var n: Int
                            while (zis.read(buffer).also { n = it } >= 0) out.write(buffer, 0, n)
                            return out.toByteArray() to entry.name
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            null to null
        } catch (e: Exception) {
            null to null
        }
    }
}
