package com.notescout.tag

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TagRepository : JpaRepository<Tag, UUID> {

    fun findByUserIdAndNormalizedNameIn(userId: UUID, normalizedNames: Collection<String>): List<Tag>

    fun findByIdAndUserId(id: UUID, userId: UUID): Tag?

    /**
     * Создаёт тег, если такого у пользователя ещё нет.
     *
     * `on conflict do nothing` опирается на уникальный индекс
     * tags_user_normalized_idx (user_id, normalized_name) и делает операцию
     * безопасной при одновременных запросах: второй INSERT просто ничего не делает,
     * вместо падения с нарушением уникальности.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            insert into tags (user_id, name, normalized_name)
            values (cast(:userId as uuid), cast(:name as text), cast(:normalizedName as text))
            on conflict do nothing
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: UUID,
        @Param("name") name: String,
        @Param("normalizedName") normalizedName: String,
    ): Int

    /**
     * Теги пользователя вместе с числом его активных заметок.
     *
     * Возвращаем сырые строки, а не проекцию: у native-запроса с интерфейсной
     * проекцией сопоставление алиасов хрупкое, а здесь всё явно.
     * Строка = [id: UUID, name: String, noteCount: Long].
     */
    @Query(
        value = """
            select t.id, t.name, count(n.id) as note_count
            from tags t
                     left join note_tags nt on nt.tag_id = t.id
                     left join notes n
                               on n.id = nt.note_id
                                   and n.deleted_at is null
                                   and n.archived_at is null
            where t.user_id = cast(:userId as uuid)
            group by t.id, t.name
            order by lower(t.name)
            """,
        nativeQuery = true,
    )
    fun findAllWithNoteCounts(@Param("userId") userId: UUID): List<Array<Any>>
}
