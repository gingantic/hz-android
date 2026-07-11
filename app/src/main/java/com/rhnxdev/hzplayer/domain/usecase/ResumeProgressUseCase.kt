package com.rhnxdev.hzplayer.domain.usecase

import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import javax.inject.Inject

/**
 * Persists and reads playback-resume position ("continue watching").
 *
 * [ResumeRepository.saveProgress] already ignores blank/non-positive input, so
 * callers can pass raw position/duration without pre-checking.
 */
class ResumeProgressUseCase @Inject constructor(
    private val resumeRepository: ResumeRepository,
) {
    /** Save progress for [uri]. No-op for blank/invalid input (repository-guarded). */
    suspend fun save(uri: String, positionMs: Long, durationMs: Long) {
        resumeRepository.saveProgress(uri, positionMs, durationMs)
    }

    /** Resume position for [uri], or 0 when there is nothing worth resuming. */
    suspend fun getResumePosition(uri: String): Long =
        resumeRepository.getResumePosition(uri)
}
