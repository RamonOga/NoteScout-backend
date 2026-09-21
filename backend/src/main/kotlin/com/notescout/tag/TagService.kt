package com.notescout.tag

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.regex.Pattern

@Service
class TagService(
    private val tagRepository: TagRepository,
) {

    /**
     * Нормализация имени тега: обрезаем края, приводим к нижнему регистру,
     * схлопываем повторные пробелы. «  Работа », «работа» и «РАБОТА  » — один тег.
     */
    fun normalize(name: String): String =
        WHITESPACE.matcher(name.trim().lowercase()).replaceAll(" ")

    /**
     * Превращает список имён тегов в управляемые сущности, создавая отсутствующие.
     *
     * Вставка идёт через `insert ... on conflict do nothing`: два одновременных
     * запроса с одним и тем же новым тегом не упадут на уникальном индексе
     * и не потребуют ретраев на уровне приложения.
     */
    @Transactional
    fun resolveTags(userId: UUID, rawNames: Collection<String>): Set<Tag> {
        if (rawNames.isEmpty()) return emptySet()

        val ordered = LinkedHashMap<String, String>() // normalized -> отображаемое имя
        rawNames.forEach { raw ->
            val display = raw.trim()
            if (display.isEmpty()) return@forEach
            val normalized = normalize(display)
            if (normalized.isNotEmpty()) ordered.putIfAbsent(normalized, display)
        }
        if (ordered.isEmpty()) return emptySet()

        ordered.forEach { (normalized, display) ->
            tagRepository.insertIfAbsent(userId = userId, name = display, normalizedName = normalized)
        }

        val stored = tagRepository.findByUserIdAndNormalizedNameIn(userId, ordered.keys)
        val byNormalized = stored.associateBy { it.normalizedName }

        // Найденное может оказаться короче запрошенного только при экзотической
        // гонке — в этом случае тег потеряется молча, но заметка сохранится.
        return ordered.keys.mapNotNull { byNormalized[it] }.toCollection(linkedSetOf())
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<TagResponse> =
        tagRepository.findAllWithNoteCounts(userId).map { row ->
            TagResponse(
                id = row[0] as UUID,
                name = row[1] as String,
                noteCount = (row[2] as Number).toLong(),
            )
        }

    private companion object {
        val WHITESPACE: Pattern = Pattern.compile("\\s+")
    }
}
