package com.rhnxdev.hzplayer.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.rhnxdev.hzplayer.data.datasource.remote.SubdlApi
import com.rhnxdev.hzplayer.domain.repository.SubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val subdlApi: SubdlApi,
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
    }

    override suspend fun searchTitles(
        query: String,
        apiKey: String,
        type: String,
    ): Result<List<SubtitleRepository.SearchCandidate>> {
        return subdlApi.searchTitles(query, apiKey, type).map { list ->
            list.map { c ->
                SubtitleRepository.SearchCandidate(
                    name = c.name,
                    year = c.year,
                    type = c.type,
                    imdbId = c.imdbId,
                    tmdbId = c.tmdbId,
                    posterUrl = c.posterUrl,
                )
            }
        }
    }

    override suspend fun searchSubtitles(
        query: String,
        apiKey: String,
        language: String?,
        type: String,
        season: Int?,
        episode: Int?,
        imdbId: String?,
        tmdbId: Long?,
    ): Result<List<SubtitleRepository.SearchResult>> {
        return subdlApi.searchSubtitles(query, apiKey, language, type, season, episode, imdbId, tmdbId)
            .map { list ->
                list.map { apiResult ->
                    SubtitleRepository.SearchResult(
                        downloadUrl = apiResult.downloadUrl,
                        language = apiResult.language,
                        releaseName = apiResult.releaseName,
                        fps = apiResult.fps,
                        hearingImpaired = apiResult.hearingImpaired,
                    )
                }
            }
    }

    override suspend fun download(downloadUrl: String, fileName: String, apiKey: String): Result<Uri> {
        return try {
            Log.i(TAG, "download: fileName=$fileName")
            var bytes = subdlApi.downloadSubtitleContent(downloadUrl, apiKey)
                ?: return Result.failure(Exception("Failed to download subtitle content"))

            // Most SubDL results are packed ZIPs (only unpack_files gives raw). A
            // packed download is bytes that start with the ZIP magic — extract the
            // first real subtitle entry instead of writing the archive as .srt.
            var extractName = fileName
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
            ) {
                val (entryBytes, entryName) = extractFirstSubtitle(bytes, fileName)
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
            Log.i(TAG, "download OK: ${outputFile.absolutePath} (${bytes.size} bytes)")

            Result.success(outputFile.toUri())
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Pull a subtitle entry out of a ZIP archive. When [preferredName] is given,
     * the entry whose file name matches it (case-insensitive) is returned so the
     * user gets the exact file they picked from a multi-file pack; otherwise (or
     * when no match exists) the first subtitle-looking entry is used. Returns the
     * entry's bytes and name, or (null, null) if nothing usable is found.
     */
    private fun extractFirstSubtitle(
        zipBytes: ByteArray,
        preferredName: String? = null,
    ): Pair<ByteArray?, String?> {
        val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")
        return try {
            val buffer = ByteArray(8 * 1024)
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
                var fallback: Pair<ByteArray, String>? = null
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val ext = entry.name.substringAfterLast('.').lowercase()
                        if (ext in SUB_EXT) {
                            val out = java.io.ByteArrayOutputStream()
                            var n: Int
                            while (zis.read(buffer).also { n = it } >= 0) out.write(buffer, 0, n)
                            val entryBase = entry.name.substringAfterLast('/')
                            // Exact match on the user-picked file wins immediately.
                            if (!preferredName.isNullOrBlank() &&
                                entryBase.equals(preferredName, ignoreCase = true)
                            ) {
                                return out.toByteArray() to entry.name
                            }
                            if (fallback == null) fallback = out.toByteArray() to entry.name
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                fallback ?: (null to null)
            }
        } catch (e: Exception) {
            null to null
        }
    }
}
