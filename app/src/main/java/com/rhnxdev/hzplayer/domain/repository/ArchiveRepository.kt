package com.rhnxdev.hzplayer.domain.repository

data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
)

interface ArchiveRepository {
    suspend fun listEntries(containerPath: String, password: String? = null): Result<List<ArchiveEntry>>
}
