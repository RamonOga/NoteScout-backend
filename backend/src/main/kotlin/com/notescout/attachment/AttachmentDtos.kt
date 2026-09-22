package com.notescout.attachment

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Описание вложенного файла. Самого файла здесь нет — только метаданные. */
data class AttachmentResponse(
    val id: UUID,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(attachment: Attachment): AttachmentResponse = AttachmentResponse(
            id = attachment.id,
            fileName = attachment.fileName,
            contentType = attachment.contentType,
            sizeBytes = attachment.sizeBytes,
            createdAt = attachment.createdAt,
        )
    }
}

/** Занятый объём пользователя: по нему видно, далеко ли до квоты. */
data class AttachmentUsageResponse(
    @field:Schema(description = "Сколько занято вложениями")
    val usedBytes: Long,
    @field:Schema(description = "Предел, дальше которого загрузка отклоняется")
    val limitBytes: Long,
)
