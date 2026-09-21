package com.notescout.search

import com.notescout.common.PageResponse
import com.notescout.note.NoteFilter
import com.notescout.note.NoteResponse
import com.notescout.note.NoteService
import com.notescout.note.TagsMode
import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Отдельная точка входа для поиска: удобно вызывать из мобильного клиента,
 * когда нужно искать «по всему» — без привязки к экрану заметок.
 */
@RestController
@RequestMapping("/api/v1/search")
@Validated
@Tag(name = "Поиск", description = "Поиск по тексту и тегам")
class SearchController(
    private val noteService: NoteService,
) {

    @GetMapping
    @Operation(
        summary = "Поиск заметок",
        description = "Фильтрует по подстроке/полнотекстово (`q`) и по тегам (`tags` через запятую). " +
            "mode=ANY — хотя бы один тег, mode=ALL — все теги одновременно.",
    )
    fun search(
        @AuthenticationPrincipal principal: UserPrincipal,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tags: String?,
        @RequestParam(defaultValue = "ANY") mode: TagsMode,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<NoteResponse> = noteService.search(
        userId = principal.id,
        filter = NoteFilter(
            q = q,
            type = null,
            tags = tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            tagsMode = mode,
            includeArchived = includeArchived,
            page = page,
            size = size,
        ),
    )
}
