package com.notescout.attachment

import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Вложения", description = "Файлы, приложенные к заметкам")
class AttachmentController(
    private val attachmentService: AttachmentService,
) {

    @PostMapping("/notes/{noteId}/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Приложить файл к заметке")
    fun upload(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable noteId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): AttachmentResponse = attachmentService.upload(principal.id, noteId, file)

    @GetMapping("/notes/{noteId}/attachments")
    @Operation(summary = "Вложения заметки")
    fun list(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable noteId: UUID,
    ): List<AttachmentResponse> = attachmentService.list(principal.id, noteId)

    /**
     * Скачивание отдаёт байты, а не JSON: файл отдаётся потоком, потому что
     * держать вложение целиком в памяти сервера незачем.
     *
     * Публичной ссылки у файла нет — только этот путь с токеном. Подписанные
     * ссылки завели бы второй механизм авторизации и риск утечки самой ссылки.
     */
    @GetMapping("/attachments/{id}/content")
    @Operation(summary = "Скачать файл вложения")
    fun content(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Resource> {
        val (attachment, stream) = attachmentService.download(principal.id, id)

        // Content-Type пришёл от клиента вместе с файлом: мог прийти чем угодно,
        // поэтому разбор с запасным вариантом, а не исключение наружу.
        val mediaType = runCatching { MediaType.parseMediaType(attachment.contentType) }
            .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)

        // filename(..., UTF_8) — имя уходит в формате RFC 5987, иначе русские
        // имена превращаются в кракозябры в браузере.
        val disposition = ContentDisposition.attachment()
            .filename(attachment.fileName, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(mediaType)
            .contentLength(attachment.sizeBytes)
            .body(InputStreamResource(stream))
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить вложение")
    fun delete(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = attachmentService.delete(principal.id, id)

    @GetMapping("/attachments/usage")
    @Operation(summary = "Сколько места занято вложениями")
    fun usage(
        @AuthenticationPrincipal principal: UserPrincipal,
    ): AttachmentUsageResponse = attachmentService.usage(principal.id)
}
