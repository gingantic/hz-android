package com.rhnxdev.hzplayer.domain.model

/** Video/audio decoder selection preference. */
enum class DecoderMode {
    /** Use the device default decoder order. */
    AUTO,
    /** Prefer hardware-accelerated decoders; skip software-only. */
    HARDWARE,
    /** Prefer software decoders (Android software codecs). */
    SOFTWARE,
}
