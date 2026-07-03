package com.rhnxdev.hzplayer.presentation.preview

import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.MediaItem as DomainMediaItem

object PreviewMedia {

    // ── Videos ──────────────────────────────────────────────────────

    val videoMovies: List<Map<String, Any>> = listOf(
        mapOf(
            "title" to "Blade Runner 2049",
            "subtitle" to "2017 • Sci-Fi",
            "year" to "2017",
            "durationMs" to 9_123_000,
            "resolution" to "4K",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        ),
        mapOf(
            "title" to "Arrival",
            "subtitle" to "2016 • Sci-Fi",
            "year" to "2016",
            "durationMs" to 7_080_000,
            "resolution" to "HD",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
        ),
        mapOf(
            "title" to "Interstellar",
            "subtitle" to "2014 • Sci-Fi",
            "year" to "2014",
            "durationMs" to 10_260_000,
            "resolution" to "4K",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        ),
        mapOf(
            "title" to "The Dark Knight",
            "subtitle" to "2008 • Action",
            "year" to "2008",
            "durationMs" to 8_940_000,
            "resolution" to "HD",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
        ),
        mapOf(
            "title" to "Inception",
            "subtitle" to "2010 • Thriller",
            "year" to "2010",
            "durationMs" to 8_280_000,
            "resolution" to "HD",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"
        ),
        mapOf(
            "title" to "Dune: Part Two",
            "subtitle" to "2024 • Sci-Fi",
            "year" to "2024",
            "durationMs" to 10_020_000,
            "resolution" to "4K",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"
        ),
        mapOf(
            "title" to "Parasite",
            "subtitle" to "2019 • Drama",
            "year" to "2019",
            "durationMs" to 7_920_000,
            "resolution" to "HD",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4"
        ),
        mapOf(
            "title" to "Everything Everywhere All at Once",
            "subtitle" to "2022 • Adventure",
            "year" to "2022",
            "durationMs" to 8_340_000,
            "resolution" to "HD",
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4"
        ),
    )

    // ── Recent Videos (partially watched) ──────────────────────────

    val recentVideos: List<Map<String, Any>> = listOf(
        mapOf(
            "title" to "Blade Runner 2049",
            "subtitle" to "45% watched",
            "durationMs" to 9_123_000,
            "progress" to 0.45f,
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        ),
        mapOf(
            "title" to "Dune: Part Two",
            "subtitle" to "72% watched",
            "durationMs" to 10_020_000,
            "progress" to 0.72f,
            "uri" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"
        ),
    )

    // ── Albums ──────────────────────────────────────────────────────

    val albums: List<Album> = listOf(
        Album(id = 1, title = "Random Access Memories", artist = "Daft Punk", trackCount = 13, year = 2013),
        Album(id = 2, title = "After Hours", artist = "The Weeknd", trackCount = 14, year = 2020),
        Album(id = 3, title = "Inception (OST)", artist = "Hans Zimmer", trackCount = 12, year = 2010),
        Album(id = 4, title = "? (Question Mark)", artist = "XXXTENTACION", trackCount = 18, year = 2018),
        Album(id = 5, title = "Blonde", artist = "Frank Ocean", trackCount = 17, year = 2016),
        Album(id = 6, title = "To Pimp a Butterfly", artist = "Kendrick Lamar", trackCount = 16, year = 2015),
        Album(id = 7, title = "Currents", artist = "Tame Impala", trackCount = 13, year = 2015),
        Album(id = 8, title = "Dawn FM", artist = "The Weeknd", trackCount = 16, year = 2022),
    )

    // ── Artists ────────────────────────────────────────────────────

    val artists: List<Artist> = listOf(
        Artist(id = 1, name = "Daft Punk", albumCount = 4, trackCount = 52),
        Artist(id = 2, name = "Hans Zimmer", albumCount = 30, trackCount = 150),
        Artist(id = 3, name = "The Weeknd", albumCount = 5, trackCount = 72),
        Artist(id = 4, name = "Kendrick Lamar", albumCount = 6, trackCount = 85),
        Artist(id = 5, name = "Frank Ocean", albumCount = 2, trackCount = 30),
        Artist(id = 6, name = "Tame Impala", albumCount = 4, trackCount = 40),
        Artist(id = 7, name = "Radiohead", albumCount = 9, trackCount = 88),
        Artist(id = 8, name = "Miles Davis", albumCount = 50, trackCount = 200),
    )

    // ── Songs ──────────────────────────────────────────────────────

    val songs: List<AudioItem> = listOf(
        AudioItem(id = 1, title = "Get Lucky", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3", artist = "Daft Punk", album = "Random Access Memories", durationMs = 369_000, trackNumber = 1),
        AudioItem(id = 2, title = "Blinding Lights", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3", artist = "The Weeknd", album = "After Hours", durationMs = 200_000, trackNumber = 3),
        AudioItem(id = 3, title = "Time", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3", artist = "Hans Zimmer", album = "Inception (OST)", durationMs = 290_000, trackNumber = 1),
        AudioItem(id = 4, title = "HUMBLE.", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3", artist = "Kendrick Lamar", album = "DAMN.", durationMs = 177_000, trackNumber = 8),
        AudioItem(id = 5, title = "Pink + White", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3", artist = "Frank Ocean", album = "Blonde", durationMs = 185_000, trackNumber = 4),
        AudioItem(id = 6, title = "The Less I Know the Better", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3", artist = "Tame Impala", album = "Currents", durationMs = 215_000, trackNumber = 7),
        AudioItem(id = 7, title = "Starboy", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3", artist = "The Weeknd", album = "Starboy", durationMs = 230_000, trackNumber = 1),
        AudioItem(id = 8, title = "Around the World", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3", artist = "Daft Punk", album = "Homework", durationMs = 428_000, trackNumber = 2),
        AudioItem(id = 9, title = "Money Trees", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3", artist = "Kendrick Lamar", album = "good kid, m.A.A.d city", durationMs = 386_000, trackNumber = 5),
        AudioItem(id = 10, title = "Let It Happen", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3", artist = "Tame Impala", album = "Currents", durationMs = 498_000, trackNumber = 1),
        AudioItem(id = 11, title = "Ivy", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-11.mp3", artist = "Frank Ocean", album = "Blonde", durationMs = 249_000, trackNumber = 2),
        AudioItem(id = 12, title = "Interstellar (Main Theme)", uri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-12.mp3", artist = "Hans Zimmer", album = "Interstellar (OST)", durationMs = 475_000, trackNumber = 1),
    )

    // ── Files/Folders ─────────────────────────────────────────────

    val folders: List<FolderItem> = listOf(
        FolderItem(id = 1, name = "Movies", path = "/storage/emulated/0/Movies", isDirectory = true, childCount = 15, dateModified = 1_700_000_000_000),
        FolderItem(id = 2, name = "TV Shows", path = "/storage/emulated/0/TV", isDirectory = true, childCount = 8, dateModified = 1_690_000_000_000),
        FolderItem(id = 3, name = "Music", path = "/storage/emulated/0/Music", isDirectory = true, childCount = 42, dateModified = 1_710_000_000_000),
        FolderItem(id = 4, name = "Downloads", path = "/storage/emulated/0/Download", isDirectory = true, childCount = 23, dateModified = 1_715_000_000_000),
        FolderItem(id = 5, name = "Recordings", path = "/storage/emulated/0/Recordings", isDirectory = true, childCount = 5, dateModified = 1_695_000_000_000),
        FolderItem(id = 10, name = "blade_runner_2049.mkv", path = "/storage/emulated/0/Movies/blade_runner_2049.mkv", isDirectory = false, fileSize = 12_500_000_000, dateModified = 1_700_000_000_000, mimeType = "video/x-matroska"),
        FolderItem(id = 11, name = "interstellar.mkv", path = "/storage/emulated/0/Movies/interstellar.mkv", isDirectory = false, fileSize = 15_800_000_000, dateModified = 1_700_000_000_000, mimeType = "video/x-matroska"),
        FolderItem(id = 12, name = "lecture_notes.pdf", path = "/storage/emulated/0/Download/lecture_notes.pdf", isDirectory = false, fileSize = 2_400_000, dateModified = 1_712_000_000_000, mimeType = "application/pdf"),
        FolderItem(id = 13, name = "photo_2024.jpg", path = "/storage/emulated/0/Download/photo_2024.jpg", isDirectory = false, fileSize = 3_200_000, dateModified = 1_714_000_000_000, mimeType = "image/jpeg"),
    )

    // ── Network Servers ─────────────────────────────────────────────

    val servers: List<ServerConfig> = listOf(
        ServerConfig(id = 1, name = "Home NAS", protocol = NetworkProtocol.SMB, host = "192.168.1.100", port = 445, username = "user"),
        ServerConfig(id = 2, name = "Office FTP", protocol = NetworkProtocol.FTP, host = "ftp.office.com", port = 21, username = "admin"),
        ServerConfig(id = 3, name = "Dev Server", protocol = NetworkProtocol.SFTP, host = "dev.example.com", port = 22, username = "deploy"),
    )

    // ── Stream History ──────────────────────────────────────────────

    val streamHistory: List<StreamHistoryItem> = listOf(
        StreamHistoryItem(id = 1, url = "https://example.com/live/stream.m3u8", title = "Live Stream", isFavorite = true, lastPlayedAt = System.currentTimeMillis() - 3_600_000),
        StreamHistoryItem(id = 2, url = "rtsp://camera.local/feed", title = "Security Camera", isFavorite = false, lastPlayedAt = System.currentTimeMillis() - 86_400_000),
    )
}
