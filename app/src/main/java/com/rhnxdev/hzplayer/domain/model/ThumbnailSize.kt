package com.rhnxdev.hzplayer.domain.model

/**
 * Preview size for media thumbnails — grid density on grids, leading share of a
 * list row elsewhere. Larger size always means bigger previews.
 *
 * `LARGE` reproduces the pre-feature appearance on every surface, so the default
 * changes nothing and the smaller steps only densify.
 *
 * @property columns fixed column count when the size drives a thumbnail grid
 */
enum class ThumbnailSize(val columns: Int) {
    SMALL(4),
    MEDIUM(3),
    LARGE(2),
}
