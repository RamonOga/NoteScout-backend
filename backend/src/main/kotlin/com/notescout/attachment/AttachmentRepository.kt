package com.notescout.attachment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AttachmentRepository : JpaRepository<Attachment, UUID> {

    /**
     * Вложение ищется вместе с владельцем.
     *
     * Чужое вложение неотличимо от несуществующего — отвечаем 404, а не 403:
     * иначе перебором идентификаторов можно выяснить, какие файлы существуют.
     */
    fun findByIdAndUserId(id: UUID, userId: UUID): Attachment?

    fun findAllByNoteIdAndUserIdOrderByCreatedAtAsc(noteId: UUID, userId: UUID): List<Attachment>

    /** Занятый объём пользователя — по нему считается квота. */
    @Query("select coalesce(sum(a.sizeBytes), 0) from Attachment a where a.userId = :userId")
    fun totalSizeByUserId(@Param("userId") userId: UUID): Long

    fun countByNoteId(noteId: UUID): Long
}
