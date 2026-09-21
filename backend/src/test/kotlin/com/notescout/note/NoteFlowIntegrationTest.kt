package com.notescout.note

import com.notescout.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
class NoteFlowIntegrationTest : IntegrationTestBase() {

    private fun createNote(
        token: String,
        title: String,
        type: String? = null,
        content: String? = null,
        url: String? = null,
        tags: List<String> = emptyList(),
    ) = mockMvc.post("/api/v1/notes") {
        header("Authorization", bearer(token))
        contentType = MediaType.APPLICATION_JSON
        content = json(
            buildMap {
                put("title", title)
                type?.let { put("type", it) }
                content?.let { put("content", it) }
                url?.let { put("url", it) }
                put("tags", tags)
            }
        )
    }

    private fun noteIdOf(result: org.springframework.test.web.servlet.MvcResult): String =
        objectMapper.readTree(result.response.contentAsString).get("id").asText()

    @Test
    fun `создание текстовой заметки с тегами`() {
        val tokens = registerUser()

        createNote(
            token = tokens.accessToken,
            title = "  Планы на неделю  ",
            content = "  Написать план разработки  ",
            // "Работа" и " работа " должны схлопнуться в один тег
            tags = listOf("Работа", "  работа ", "идеи"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("TEXT"))
            .andExpect(jsonPath("$.title").value("Планы на неделю"))
            .andExpect(jsonPath("$.content").value("Написать план разработки"))
            .andExpect(jsonPath("$.tags.length()").value(2))
            .andExpect(jsonPath("$.tags[0]").value("Работа"))
            .andExpect(jsonPath("$.tags[1]").value("идеи"))
    }

    @Test
    fun `ссылка без url отклоняется, а с url создаётся`() {
        val tokens = registerUser()

        createNote(token = tokens.accessToken, title = "Spring", type = "LINK")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))

        createNote(
            token = tokens.accessToken,
            title = "Spring Framework",
            type = "LINK",
            url = "https://spring.io",
            tags = listOf("java"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("LINK"))
            .andExpect(jsonPath("$.url").value("https://spring.io"))
    }

    @Test
    fun `пустой заголовок не проходит валидацию`() {
        val tokens = registerUser()

        createNote(token = tokens.accessToken, title = "   ")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `поиск по тегу в режимах ANY и ALL`() {
        val tokens = registerUser()

        createNote(tokens.accessToken, "Заметка про работу", tags = listOf("работа"))
        createNote(tokens.accessToken, "Заметка про работу и java", tags = listOf("работа", "java"))
        createNote(tokens.accessToken, "Заметка про отдых", tags = listOf("отдых"))

        // Регистр в теге не важен: "Работа" находит "работа".
        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("tag", "Работа")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("tag", "работа")
            param("tag", "java")
            param("tagsMode", "ALL")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Заметка про работу и java"))

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("tag", "java")
            param("tag", "отдых")
            param("tagsMode", "ANY")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    @Test
    fun `поиск по тексту находит по слову из заголовка и содержимого`() {
        val tokens = registerUser()

        createNote(tokens.accessToken, "Документация Spring", content = "описание фреймворка")
        createNote(tokens.accessToken, "Рецепт борща", content = "свёкла, капуста")

        mockMvc.get("/api/v1/search") {
            header("Authorization", bearer(tokens.accessToken))
            param("q", "документация")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Документация Spring"))

        mockMvc.get("/api/v1/search") {
            header("Authorization", bearer(tokens.accessToken))
            param("q", "свёкла")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Рецепт борща"))

        // Поиск по части слова работает за счёт триграмм на заголовке.
        mockMvc.get("/api/v1/search") {
            header("Authorization", bearer(tokens.accessToken))
            param("q", "докум")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    @Test
    fun `поиск по тексту и тегу одновременно`() {
        val tokens = registerUser()

        createNote(tokens.accessToken, "Документация Spring", content = "фреймворк", tags = listOf("java"))
        createNote(tokens.accessToken, "Документация Kotlin", content = "язык", tags = listOf("kotlin"))

        mockMvc.get("/api/v1/search") {
            header("Authorization", bearer(tokens.accessToken))
            param("q", "документация")
            param("tags", "kotlin")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Документация Kotlin"))
    }

    @Test
    fun `фильтр по типу записи`() {
        val tokens = registerUser()

        createNote(tokens.accessToken, "Текстовая", type = "TEXT", content = "текст")
        createNote(tokens.accessToken, "Ссылка", type = "LINK", url = "https://example.com")

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("type", "LINK")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Ссылка"))
    }

    @Test
    fun `обновление заметки меняет поля и набор тегов, архивирование скрывает из списка`() {
        val tokens = registerUser()

        val noteId = noteIdOf(
            createNote(
                tokens.accessToken,
                "Черновик",
                content = "старый текст",
                tags = listOf("старый"),
            ).andReturn()
        )

        mockMvc.patch("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = json(
                mapOf(
                    "title" to "Готово",
                    "content" to "новый текст",
                    "tags" to listOf("новый", "ещё"),
                    "archived" to true,
                )
            )
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Готово"))
            .andExpect(jsonPath("$.content").value("новый текст"))
            .andExpect(jsonPath("$.tags.length()").value(2))
            .andExpect(jsonPath("$.archivedAt").isNotEmpty)

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("includeArchived", "true")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    @Test
    fun `очистка поля пустой строкой`() {
        val tokens = registerUser()

        val noteId = noteIdOf(
            createNote(tokens.accessToken, "С текстом", content = "что-то было").andReturn()
        )

        mockMvc.patch("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("content" to ""))
        }
            .andExpect(status().isOk)
            // default-property-inclusion=non_null: null-поля в JSON не попадают
            .andExpect(jsonPath("$.content").doesNotExist())
    }

    @Test
    fun `удаление мягкое, заметка восстанавливается`() {
        val tokens = registerUser()

        val noteId = noteIdOf(
            createNote(tokens.accessToken, "Временная", tags = listOf("temp")).andReturn()
        )

        mockMvc.delete("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isNoContent)

        mockMvc.get("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isNotFound)

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))

        mockMvc.post("/api/v1/notes/$noteId/restore") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Временная"))
            .andExpect(jsonPath("$.tags.length()").value(1))
    }

    @Test
    fun `чужая заметка неотличима от несуществующей`() {
        val owner = registerUser()
        val stranger = registerUser()

        val noteId = noteIdOf(createNote(owner.accessToken, "Секрет").andReturn())

        mockMvc.get("/api/v1/notes/$noteId") {
            header("Authorization", bearer(stranger.accessToken))
        }
            .andExpect(status().isNotFound)

        mockMvc.patch("/api/v1/notes/$noteId") {
            header("Authorization", bearer(stranger.accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("title" to "Взлом"))
        }
            .andExpect(status().isNotFound)

        mockMvc.delete("/api/v1/notes/$noteId") {
            header("Authorization", bearer(stranger.accessToken))
        }
            .andExpect(status().isNotFound)

        // У владельца заметка осталась нетронутой.
        mockMvc.get("/api/v1/notes/$noteId") {
            header("Authorization", bearer(owner.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Секрет"))
    }

    @Test
    fun `теги пользователя изолированы и отдаются со счётчиком заметок`() {
        val first = registerUser()
        val second = registerUser()

        createNote(first.accessToken, "Первая", tags = listOf("общий"))
        createNote(first.accessToken, "Вторая", tags = listOf("общий"))
        createNote(second.accessToken, "Чужая", tags = listOf("общий"))

        mockMvc.get("/api/v1/tags") {
            header("Authorization", bearer(first.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("общий"))
            .andExpect(jsonPath("$[0].noteCount").value(2))
    }

    @Test
    fun `несуществующая заметка возвращает 404`() {
        val tokens = registerUser()

        mockMvc.get("/api/v1/notes/${UUID.randomUUID()}") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `пагинация отдаёт корректные метаданные`() {
        val tokens = registerUser()
        repeat(3) { index -> createNote(tokens.accessToken, "Заметка $index") }

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("page", "0")
            param("size", "2")
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
    }

    @Test
    fun `слишком большой размер страницы отклоняется`() {
        val tokens = registerUser()

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("size", "5000")
        }
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `неизвестный тип записи отклоняется`() {
        val tokens = registerUser()

        mockMvc.get("/api/v1/notes") {
            header("Authorization", bearer(tokens.accessToken))
            param("type", "VIDEO")
        }
            .andExpect(status().isBadRequest)
    }
}
