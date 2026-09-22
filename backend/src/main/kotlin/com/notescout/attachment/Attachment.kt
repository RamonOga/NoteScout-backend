package com.notescout.attachment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Файл, приложенный к заметке.
 *
 * Самого файла здесь нет: в базе только описание и ключ, по которому файл
 * лежит в хранилище. Так переезд с локального тома на S3 не требует правки
 * схемы — меняется только реализация хранилища.
 */
@Entity
@Table(name = "attachments")
class Attachment(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "note_id", nullable = false, updatable = false)
    var noteId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    /** Имя, как его видел пользователь. В хранилище имя другое. */
    @Column(name = "file_name", nullable = false, length = 255)
    var fileName: String,

    @Column(name = "content_type", nullable = false, length = 255)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    var storageKey: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Attachment(id=$id, fileName=$fileName, size=$sizeBytes)"
}
