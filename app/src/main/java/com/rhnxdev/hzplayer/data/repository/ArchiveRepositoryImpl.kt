package com.rhnxdev.hzplayer.data.repository

import android.util.Log
import com.rhnxdev.hzplayer.data.datasource.archive.ArchiveNative
import com.rhnxdev.hzplayer.domain.repository.ArchiveEntry
import com.rhnxdev.hzplayer.domain.repository.ArchiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ArchiveRepositoryImpl @Inject constructor() : ArchiveRepository {

    override suspend fun listEntries(
        containerPath: String,
        password: String?,
    ): Result<List<ArchiveEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            ArchiveNative.nativeList(containerPath, password).map { line ->
                val p = line.split('\t')
                ArchiveEntry(
                    name = p[0],
                    size = p[1].toLong(),
                    isDirectory = p[2] == "1",
                )
            }
        }.onSuccess {
            Log.i(TAG, "listEntries: $containerPath -> ${it.size} entries")
        }.onFailure {
            Log.e(TAG, "listEntries failed: $containerPath", it)
        }
    }

    private companion object {
        const val TAG = "HzPlayer/Archive"
    }
}
