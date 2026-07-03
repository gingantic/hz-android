package com.rhnxdev.hzplayer.data.datasource.player

import androidx.media3.datasource.DataSource

/**
 * [DataSource.Factory] that produces [SmbDataSource] instances.
 *
 * Used by [MediaPlayerHolder] to wire a composite DataSource factory
 * that routes `smb://` URIs to [SmbDataSource] and everything else
 * to the default HTTP/file stack.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource()
}
