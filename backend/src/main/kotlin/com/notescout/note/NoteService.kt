package com.notescout.note

import com.notescout.common.BadRequestException
import com.notescout.common.NotFoundException
import com.notescout.common.PageResponse
import com.notescout.tag.TagService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Режим фильтрации по нескольким тегам. */
enum class TagsMode {
    /** Хотя бы один из указанных тегов. */
    ANY,

    /** Все указанные теги одновременно. */
    ALL,
}

data class NoteFilter(
    val q: String? = null,
    val type: NoteType? = null,
    val tags: List<String> = emptyList(),
    val tagsMode: TagsMode = TagsMode.ANY,
    val includeArchived: Boolean = false,
    val deletedOnly: Boolean = false,
    val page: Int = 0,
    val size: Int = 20,
)

@Service
class NoteService(
    private val noteRepository: NoteRepository,
    private val tagService: TagService,
    private val clock: Clock,
) {

    @Transactional
    fun create(userId: UUID, request: NoteCreateRequest): NoteResponse {
        val type = request.type ?: NoteType.TEXT
        val url = request.url.normalized()
        requireLinkHasUrl(type, url)

        val now = clock.instant()
        val note = Note(
            userId = userId,
            type = type,
            title = request.title.trim(),
            content = request.content.normalized(),
            url = url,
            createdAt = now,
            updatedAt = now,
        )
        note.tags.addAll(tagService.resolveTags(userId, request.tags))

        return NoteResponse.from(noteRepository.save(note))
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, noteId: UUID): NoteResponse =
        NoteResponse.from(requireOwned(userId, noteId))

    @Transactional(readOnly = true)
    fun search(userId: UUID, filter: NoteFilter): PageResponse<NoteResponse> {
        val normalizedTags = filter.tags
            .map { tagService.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()

        val page = noteRepository.search(
            userId = userId,
            q = filter.q?.trim()?.ifBlank { null },
            type = filter.type?.name,
            // Пустой `in ()` — синтаксическая ошибка Postgres, поэтому подставляем заглушку,
            // а фильтр отключаем счётчиком tagCount = 0.
            tags = normalizedTags.ifEmpty { listOf(NoteRepository.NO_TAGS_STUB) },
            tagCount = normalizedTags.size,
            tagsMode = filter.tagsMode.name,
            includeArchived = filter.includeArchived,
            deletedOnly = filter.deletedOnly,
            pageable = PageRequest.of(filter.page, filter.size),
        )

        return PageResponse.of(page, page.content.map { NoteResponse.from(it) })
    }

    @Transactional
    fun update(userId: UUID, noteId: UUID, request: NoteUpdateRequest): NoteResponse {
        val note = requireOwned(userId, noteId)

        request.title?.let {
            if (it.isBlank()) throw BadRequestException("Заголовок не может быть пустым")
            note.title = it.trim()
        }
        request.content?.let { note.content = it.normalized() }
        request.url?.let { note.url = it.normalized() }
        request.type?.let { note.type = it }
        request.archived?.let { archived ->
            note.archivedAt = if (archived) note.archivedAt ?: clock.instant() else null
        }
        request.tags?.let { tags ->
            val resolved = tagService.resolveTags(userId, tags)
            // Меняем коллекцию на месте: Hibernate отслеживает именно её содержимое.
            note.tags.clear()
            note.tags.addAll(resolved)
        }

        requireLinkHasUrl(note.type, note.url)
        note.updatedAt = clock.instant()

        return NoteResponse.from(note)
    }

    /** Мягкое удаление: запись остаётся в БД и может быть восстановлена. */
    @Transactional
    fun delete(userId: UUID, noteId: UUID) {
        val note = requireOwned(userId, noteId)
        val now = clock.instant()
        note.deletedAt = now
        note.updatedAt = now
    }

    @Transactional
    fun restore(userId: UUID, noteId: UUID): NoteResponse {
        val note = requireOwned(userId, noteId, includeDeleted = true)
        if (note.deletedAt != null) {
            note.deletedAt = null
            note.updatedAt = clock.instant()
        }
        return NoteResponse.from(note)
    }

    /**
     * Заметка другого пользователя неотличима от несуществующей — отвечаем 404,
     * а не 403: так нельзя перебором выяснить, какие id существуют.
     */
    private fun requireOwned(userId: UUID, noteId: UUID, includeDeleted: Boolean = false): Note {
        val note = noteRepository.findByIdAndUserId(noteId, userId)
            ?: throw NotFoundException("Заметка не найдена")
        if (!includeDeleted && note.isDeleted) {
            throw NotFoundException("Заметка не найдена")
        }
        return note
    }

    private fun requireLinkHasUrl(type: NoteType, url: String?) {
        if (type == NoteType.LINK && url.isNullOrBlank()) {
            throw BadRequestException("Для записи типа LINK обязательно поле url")
        }
    }

    /** Пустая строка приводится к null — «поля нет» и «поле пустое» означают одно и то же. */
    private fun String?.normalized(): String? = this?.trim()?.ifEmpty { null }
}
