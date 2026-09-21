package com.notescout.tag

import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "Теги", description = "Теги текущего пользователя")
class TagController(
    private val tagService: TagService,
) {

    @GetMapping
    @Operation(summary = "Список тегов с числом активных заметок")
    fun list(@AuthenticationPrincipal principal: UserPrincipal): List<TagResponse> =
        tagService.list(principal.id)
}
