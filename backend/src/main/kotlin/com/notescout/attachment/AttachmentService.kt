package com.notescout.attachment

import com.notescout.common.BadRequestException
import com.notescout.common.NotFoundException
import com.notescout.common.PayloadTooLargeException
import com.notescout.config.AppProperties
import com.notescout.note.NoteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.util.UUID

/** Вложение вместе с его содержимым: нужно только на время отдачи файла. */
data class AttachmentContent(
    val attachment: Attachment,
    val stream: InputStream,
)

@Service
class AttachmentService(
    private val attachmentRepository: AttachmentRepository,
    private val noteService: NoteService,
    private val storage: AttachmentStorage,
    private val properties: AppProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val maxFileSize: Long get() = properties.attachments.maxFileSize.toBytes()
    private val maxTotalPerUser: Long get() = properties.attachments.maxTotalPerUser.toBytes()

    @Transactional(readOnly = true)
    fun list(userId: UUID, noteId: UUID): List<AttachmentResponse> {
        // Заметка чужая или удалённая — 404, как и у самой заметки.
        noteService.requireActiveNote(userId, noteId)
        return attachmentRepository.findAllByNoteIdAndUserIdOrderByCreatedAtAsc(noteId, userId)
            .map(AttachmentResponse::from)
    }

    @Transactional
    fun upload(userId: UUID, noteId: UUID, file: MultipartFile): AttachmentResponse {
        noteService.requireActiveNote(userId, noteId)

        val size = file.size
        if (size <= 0L) throw BadRequestException("Файл пустой")

        if (size > maxFileSize) {
            throw PayloadTooLargeException(
                "Файл больше ${properties.attachments.maxFileSize} — предел для одного файла"
            )
        }

        val used = attachmentRepository.totalSizeByUserId(userId)
        if (used + size > maxTotalPerUser) {
            throw PayloadTooLargeException(
                "Занято ${used / MEGABYTE} МБ из ${maxTotalPerUser / MEGABYTE} МБ. " +
                    "Удалите ненужные вложения, чтобы загрузить новые"
            )
        }

        val key = storage.newKey(userId)
        storage.store(key, file.inputStream)

        val attachment = Attachment(
            noteId = noteId,
            userId = userId,
            fileName = safeFileName(file.originalFilename),
            contentType = file.contentType ?: DEFAULT_CONTENT_TYPE,
            sizeBytes = size,
            storageKey = key,
            createdAt = clock.instant(),
        )

        return AttachmentResponse.from(attachmentRepository.save(attachment))
    }

    @Transactional(readOnly = true)
    fun download(userId: UUID, attachmentId: UUID): AttachmentContent {
        val attachment = requireOwned(userId, attachmentId)

        val stream = try {
            storage.load(attachment.storageKey)
        } catch (_: NoSuchFileException) {
            // Строка есть, файла нет: так бывает после сбоя или ручной чистки
            // хранилища. Честнее сказать «не найдено», чем отдать пустоту.
            throw NotFoundException("Файл не найден")
        }

        return AttachmentContent(attachment, stream)
    }

    @Transactional
    fun delete(userId: UUID, attachmentId: UUID) {
        val attachment = requireOwned(userId, attachmentId)
        attachmentRepository.delete(attachment)

        // Файл удаляем только после фиксации транзакции. Если она откатится,
        // строка останется — и файл обязан остаться вместе с ней. Обратный
        // порядок оставил бы вложение, которое нечем открыть.
        afterCommit {
            runCatching { storage.delete(attachment.storageKey) }
                .onFailure { error ->
                    // Файл осиротел, но данные целы. Это утечка места, а не
                    // потеря: сообщаем в лог, а не ломаем ответ пользователю.
                    log.warn(
                        "Не удалось удалить файл {}: {}",
                        attachment.storageKey,
                        error.message,
                    )
                }
        }
    }

    @Transactional(readOnly = true)
    fun usage(userId: UUID): AttachmentUsageResponse = AttachmentUsageResponse(
        usedBytes = attachmentRepository.totalSizeByUserId(userId),
        limitBytes = maxTotalPerUser,
    )

    private fun requireOwned(userId: UUID, attachmentId: UUID): Attachment =
        attachmentRepository.findByIdAndUserId(attachmentId, userId)
            ?: throw NotFoundException("Вложение не найдено")

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            }
        )
    }

    /**
     * Имя файла приходит от клиента и в путь хранилища не попадает, но его
     * всё равно нужно привести в порядок: оно уходит в заголовок ответа и
     * в интерфейс, а браузеры любят присылать полный путь вместо имени.
     */
    private fun safeFileName(raw: String?): String {
        val name = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        return name.ifEmpty { "файл" }.take(MAX_FILE_NAME_LENGTH)
    }

    private companion object {
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        const val MAX_FILE_NAME_LENGTH = 255
        const val MEGABYTE = 1024L * 1024L
    }
}
