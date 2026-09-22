package com.notescout.attachment

import com.notescout.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post

/**
 * Вложения: загрузка, выдача, удаление и пределы.
 *
 * Пределы в тестовом профиле уменьшены до килобайтов (application-test.yml):
 * проверять отказ по размеру на десяти мегабайтах пришлось бы, заливая эти
 * десять мегабайт.
 */
@SpringBootTest
class AttachmentFlowIntegrationTest : IntegrationTestBase() {

    private fun createNote(token: String, title: String = "Заметка"): String {
        val result = mockMvc.post("/api/v1/notes") {
            header("Authorization", bearer(token))
            contentType = MediaType.APPLICATION_JSON
            this.content = json(mapOf("title" to title))
        }
            .andExpect { status { isCreated() } }
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString).get("id").asText()
    }

    private fun upload(
        token: String,
        noteId: String,
        name: String = "файл.txt",
        bytes: ByteArray = "привет".toByteArray(),
        contentType: String = MediaType.TEXT_PLAIN_VALUE,
    ): ResultActionsDsl = mockMvc.multipart("/api/v1/notes/$noteId/attachments") {
        header("Authorization", bearer(token))
        file(MockMultipartFile("file", name, contentType, bytes))
    }

    private fun attachmentIdOf(result: org.springframework.test.web.servlet.MvcResult): String =
        objectMapper.readTree(result.response.contentAsString).get("id").asText()

    @Test
    fun `вложение загружается, скачивается и удаляется`() {
        val tokens = registerUser()
        val noteId = createNote(tokens.accessToken, "С вложением")
        val payload = "содержимое вложения".toByteArray()

        val created = upload(tokens.accessToken, noteId, "заметка.txt", payload)
            .andExpect {
                status { isCreated() }
                jsonPath("$.fileName") { value("заметка.txt") }
                jsonPath("$.sizeBytes") { value(payload.size) }
            }
            .andReturn()
        val attachmentId = attachmentIdOf(created)

        mockMvc.get("/api/v1/notes/$noteId/attachments") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].fileName") { value("заметка.txt") }
            }

        val downloaded = mockMvc.get("/api/v1/attachments/$attachmentId/content") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect {
                status { isOk() }
                header { string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")) }
            }
            .andReturn()

        // Байты должны совпасть: проверка только на код ответа пропустила бы
        // обрезанный или перепутанный файл.
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, downloaded.response.contentAsByteArray)

        mockMvc.delete("/api/v1/attachments/$attachmentId") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/notes/$noteId/attachments") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }

        mockMvc.get("/api/v1/attachments/$attachmentId/content") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `файл больше предела отклоняется`() {
        val tokens = registerUser()
        val noteId = createNote(tokens.accessToken)

        // Предел в тестовом профиле — 1 КБ.
        upload(tokens.accessToken, noteId, "большой.bin", ByteArray(2048))
            .andExpect {
                status { isPayloadTooLarge() }
                jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") }
            }
    }

    @Test
    fun `квота пользователя не даёт залить лишнее`() {
        val tokens = registerUser()
        val first = createNote(tokens.accessToken, "Первая")
        val second = createNote(tokens.accessToken, "Вторая")

        // Квота в тестовом профиле — 3 КБ, файл — ровно 1 КБ.
        repeat(3) { index ->
            upload(
                tokens.accessToken,
                if (index == 2) second else first,
                "часть-$index.bin",
                ByteArray(1024),
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
            ).andExpect { status { isCreated() } }
        }

        upload(tokens.accessToken, second, "лишний.bin", ByteArray(1024))
            .andExpect {
                status { isPayloadTooLarge() }
                jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") }
            }

        mockMvc.get("/api/v1/attachments/usage") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.usedBytes") { value(3072) }
                jsonPath("$.limitBytes") { value(3072) }
            }
    }

    @Test
    fun `чужое вложение неотличимо от несуществующего`() {
        val owner = registerUser()
        val stranger = registerUser()
        val noteId = createNote(owner.accessToken)

        val created = upload(owner.accessToken, noteId)
            .andExpect { status { isCreated() } }
            .andReturn()
        val attachmentId = attachmentIdOf(created)

        // Список вложений чужой заметки — 404, как и у самой заметки.
        mockMvc.get("/api/v1/notes/$noteId/attachments") {
            header("Authorization", bearer(stranger.accessToken))
        }
            .andExpect { status { isNotFound() } }

        mockMvc.get("/api/v1/attachments/$attachmentId/content") {
            header("Authorization", bearer(stranger.accessToken))
        }
            .andExpect { status { isNotFound() } }

        mockMvc.delete("/api/v1/attachments/$attachmentId") {
            header("Authorization", bearer(stranger.accessToken))
        }
            .andExpect { status { isNotFound() } }

        // И приложить файл к чужой заметке тоже нельзя.
        upload(stranger.accessToken, noteId).andExpect { status { isNotFound() } }
    }

    @Test
    fun `к удалённой заметке вложение не приложить`() {
        val tokens = registerUser()
        val noteId = createNote(tokens.accessToken)

        mockMvc.delete("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect { status { isNoContent() } }

        upload(tokens.accessToken, noteId).andExpect { status { isNotFound() } }
    }

    @Test
    fun `вложение переживает удаление и восстановление заметки`() {
        val tokens = registerUser()
        val noteId = createNote(tokens.accessToken, "С файлом")
        upload(tokens.accessToken, noteId).andExpect { status { isCreated() } }

        mockMvc.delete("/api/v1/notes/$noteId") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect { status { isNoContent() } }

        // Удаление заметки мягкое, поэтому вложение обязано дождаться её
        // возвращения: иначе восстановление вернуло бы заметку без файлов.
        mockMvc.post("/api/v1/notes/$noteId/restore") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect { status { isOk() } }

        mockMvc.get("/api/v1/notes/$noteId/attachments") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
            }
    }
}
