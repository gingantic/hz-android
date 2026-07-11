package com.rhnxdev.hzplayer.domain.model

/** Behaviour when opening media that has a saved resume position. */
enum class ResumeMode {
    /** Never resume — always start from the beginning. */
    NONE,
    /** Ask the user each time whether to resume. */
    ASK,
    /** Always resume automatically from the saved position. */
    ALWAYS,
}
