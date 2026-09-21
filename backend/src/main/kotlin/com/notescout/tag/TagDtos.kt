package com.notescout.tag

import java.util.UUID

data class TagResponse(
    val id: UUID,
    val name: String,
    /** Число активных (не удалённых и не архивных) заметок с этим тегом. */
    val noteCount: Long,
)
