package com.notescout.note

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NoteRepository : JpaRepository<Note, UUID> {

    fun findByIdAndUserId(id: UUID, userId: UUID): Note?

    /**
     * Поиск и фильтрация заметок пользователя.
     *
     * Запрос написан на native SQL, потому что нужен доступ к `search_vector`,
     * `websearch_to_tsquery` и `ts_rank` — в JPQL их нет.
     *
     * Все параметры приведены явным `cast(...)`: иначе Postgres не может вывести
     * тип параметра и падает с «could not determine data type of parameter».
     * Параметр `:tags` всегда непустой: при отсутствии фильтра передаётся
     * технический список из одного несуществующего тега, а ветка отключается
     * условием `:tagCount = 0`. Пустой `in ()` — синтаксическая ошибка Postgres.
     *
     * @param tags нормализованные имена тегов (lower-case)
     * @param tagCount реальное число тегов в фильтре (0 — фильтра нет)
     * @param tagsMode ANY — хотя бы один тег, ALL — все теги одновременно
     */
    @Query(
        value = SELECT_QUERY,
        countQuery = COUNT_QUERY,
        nativeQuery = true,
    )
    fun search(
        @Param("userId") userId: UUID,
        @Param("q") q: String?,
        @Param("type") type: String?,
        @Param("tags") tags: Collection<String>,
        @Param("tagCount") tagCount: Int,
        @Param("tagsMode") tagsMode: String,
        @Param("includeArchived") includeArchived: Boolean,
        pageable: Pageable,
    ): Page<Note>

    companion object {
        /**
         * Технический «тег-заглушка»: подставляется, когда фильтра по тегам нет,
         * потому что `in ()` — синтаксическая ошибка Postgres. Значение никогда
         * не участвует в сравнении: при tagCount = 0 вся ветка отключена первым
         * условием. NUL-байт здесь использовать нельзя — Postgres не допускает
         * его в текстовых значениях.
         */
        const val NO_TAGS_STUB = "__no_tag_filter__"

        private const val TS_QUERY =
            "coalesce(websearch_to_tsquery('russian', cast(:q as text)), ''::tsquery)"

        private const val FILTER = """
            n.user_id = cast(:userId as uuid)
              and n.deleted_at is null
              and (cast(:includeArchived as boolean) = true or n.archived_at is null)
              and (cast(:type as text) is null or n.type = cast(:type as text))
              and (
                    cast(:q as text) is null
                    or n.search_vector @@ $TS_QUERY
                    or n.title ilike ('%' || cast(:q as text) || '%')
                  )
              and (
                    cast(:tagCount as integer) = 0
                    or (
                        cast(:tagsMode as text) = 'ALL'
                        and (
                            select count(distinct t.id)
                            from note_tags nt
                                     join tags t on t.id = nt.tag_id
                            where nt.note_id = n.id
                              and t.user_id = cast(:userId as uuid)
                              and t.normalized_name in (:tags)
                        ) = cast(:tagCount as integer)
                    )
                    or (
                        cast(:tagsMode as text) = 'ANY'
                        and exists (
                            select 1
                            from note_tags nt
                                     join tags t on t.id = nt.tag_id
                            where nt.note_id = n.id
                              and t.user_id = cast(:userId as uuid)
                              and t.normalized_name in (:tags)
                        )
                    )
                  )
        """

        private const val SELECT_QUERY = "select n.* from notes n where $FILTER " +
            "order by ts_rank(n.search_vector, $TS_QUERY) desc, n.updated_at desc, n.id"

        private const val COUNT_QUERY = "select count(*) from notes n where $FILTER"
    }
}
