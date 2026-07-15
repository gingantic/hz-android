package com.rhnxdev.hzplayer.data.datasource.archive

object ArchiveNative {
    init {
        System.loadLibrary("archive-extractor")
    }

    @JvmStatic
    external fun nativeList(archivePath: String, password: String?): Array<String>

    @JvmStatic
    external fun nativeOpen(archivePath: String, entryName: String, password: String?): Long

    @JvmStatic
    external fun nativeLength(handle: Long): Long

    @JvmStatic
    external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic
    external fun nativeSeek(handle: Long, position: Long): Boolean

    @JvmStatic
    external fun nativeClose(handle: Long)
}
