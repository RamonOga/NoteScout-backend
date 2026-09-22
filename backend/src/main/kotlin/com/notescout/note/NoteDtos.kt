package com.notescout.note

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class NoteCreateRequest(
    @field:Schema(description = "TEXT — заметка, LINK — ссылка. Если не указан, используется TEXT")
    val type: NoteType? = null,

    @field:NotBlank(message = "Заголовок обязателен")
    @field:Size(max = 255, message = "Заголовок не длиннее 255 символов")
    val title: String,

    @field:Size(max = 100_000, message = "Текст заметки слишком длинный")
    val content: String? = null,

    @field:Size(max = 2048, message = "Ссылка слишком длинная")
    val url: String? = null,

    @field:Size(max = 20, message = "Не более 20 тегов на запись")
    val tags: List<@Size(max = 64, message = "Тег не длиннее 64 символов") String> = emptyList(),
)

/**
 * Частичное обновление: меняются только переданные поля.
 *
 * Чтобы очистить поле, передайте пустую строку — NULL и «поле отсутствует»
 * на уровне JSON неразличимы. Заменить набор тегов можно, передав `tags`
 * (в том числе пустой список — тогда теги снимаются).
 */
data class NoteUpdateRequest(
    val type: NoteType? = null,

    @field:Size(max = 255, message = "Заголовок не длиннее 255 символов")
    val title: String? = null,

    @field:Size(max = 100_000, message = "Текст заметки слишком длинный")
    val content: String? = null,

    @field:Size(max = 2048, message = "Ссылка слишком длинная")
    val url: String? = null,

    @field:Size(max = 20, message = "Не более 20 тегов на запись")
    val tags: List<@Size(max = 64, message = "Тег не длиннее 64 символов") String>? = null,

    @field:Schema(description = "true — в архив, false — вернуть из архива")
    val archived: Boolean? = null,
)

data class NoteResponse(
    val id: UUID,
    val type: NoteType,
    val title: String,
    val content: String?,
    val url: String?,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
    /** Момент мягкого удаления; null у активной заметки. */
    val deletedAt: Instant?,
) {
    companion object {
        fun from(note: Note): NoteResponse = NoteResponse(
            id = note.id,
            type = note.type,
            title = note.title,
            content = note.content,
            url = note.url,
            tags = note.tags.map { it.name }.sorted(),
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            archivedAt = note.archivedAt,
            deletedAt = note.deletedAt,
        )
    }
}
