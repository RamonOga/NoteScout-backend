package com.notescout.tag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Тег приватный: он принадлежит пользователю, а не является общим словарём.
 *
 * `name` — то, что ввёл пользователь (для отображения),
 * `normalizedName` — lower + сжатые пробелы (для уникальности и поиска).
 */
@Entity
@Table(name = "tags")
class Tag(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "name", nullable = false, length = 64)
    var name: String,

    @Column(name = "normalized_name", nullable = false, length = 64)
    var normalizedName: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tag) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Tag(id=$id, name=$name)"
}
