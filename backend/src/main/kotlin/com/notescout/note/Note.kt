package com.notescout.note

import com.notescout.tag.Tag
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notes")
class Note(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: NoteType = NoteType.TEXT,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    @Column(name = "content", columnDefinition = "text")
    var content: String? = null,

    @Column(name = "url", columnDefinition = "text")
    var url: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "archived_at")
    var archivedAt: Instant? = null,

    /** Мягкое удаление: запись остаётся в БД и может быть восстановлена. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "note_tags",
        joinColumns = [JoinColumn(name = "note_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")],
    )
    // Загружает теги пачками — избавляет от N+1 при выдаче списка заметок.
    @BatchSize(size = 50)
    var tags: MutableSet<Tag> = linkedSetOf(),
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isArchived: Boolean get() = archivedAt != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Note) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Note(id=$id, type=$type, title=$title)"
}
