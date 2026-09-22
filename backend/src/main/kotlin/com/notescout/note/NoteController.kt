package com.notescout.note

import com.notescout.common.PageResponse
import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notes")
@Validated
@Tag(name = "Заметки", description = "Текстовые заметки и ссылки")
class NoteController(
    private val noteService: NoteService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать заметку или ссылку")
    fun create(
        @AuthenticationPrincipal principal: UserPrincipal,
        @Valid @RequestBody request: NoteCreateRequest,
    ): NoteResponse = noteService.create(principal.id, request)

    @GetMapping
    @Operation(
        summary = "Список заметок с фильтрами по тегам, тексту и типу",
        description = "`deletedOnly=true` возвращает корзину — только удалённые заметки, " +
            "которые можно восстановить через `POST /notes/{id}/restore`.",
    )
    fun list(
        @AuthenticationPrincipal principal: UserPrincipal,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) type: NoteType?,
        @RequestParam(required = false, name = "tag") tag: List<String>?,
        @RequestParam(defaultValue = "ANY") tagsMode: TagsMode,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
        @RequestParam(defaultValue = "false") deletedOnly: Boolean,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<NoteResponse> = noteService.search(
        userId = principal.id,
        filter = NoteFilter(
            q = q,
            type = type,
            tags = tag ?: emptyList(),
            tagsMode = tagsMode,
            includeArchived = includeArchived,
            deletedOnly = deletedOnly,
            page = page,
            size = size,
        ),
    )

    @GetMapping("/{id}")
    @Operation(summary = "Получить заметку по идентификатору")
    fun get(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
    ): NoteResponse = noteService.get(principal.id, id)

    @PatchMapping("/{id}")
    @Operation(summary = "Частично обновить заметку")
    fun update(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: NoteUpdateRequest,
    ): NoteResponse = noteService.update(principal.id, id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить заметку (мягкое удаление)")
    fun delete(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = noteService.delete(principal.id, id)

    @PostMapping("/{id}/restore")
    @Operation(summary = "Восстановить удалённую заметку")
    fun restore(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
    ): NoteResponse = noteService.restore(principal.id, id)
}
