package com.notescout.auth

import com.notescout.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
class AuthFlowIntegrationTest : IntegrationTestBase() {

    @Test
    fun `регистрация возвращает пару токенов и приводит email к нижнему регистру`() {
        val email = uniqueEmail().uppercase()

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json(
                mapOf(
                    "email" to email,
                    "password" to DEFAULT_PASSWORD,
                    "displayName" to "Иван",
                )
            )
        }
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").isNumber)
            .andExpect(jsonPath("$.user.email").value(email.lowercase()))
            .andExpect(jsonPath("$.user.displayName").value("Иван"))
    }

    @Test
    fun `повторная регистрация с тем же email отклоняется`() {
        val email = uniqueEmail()
        registerUser(email = email)

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json(
                mapOf(
                    "email" to email.uppercase(),
                    "password" to DEFAULT_PASSWORD,
                    "displayName" to "Двойник",
                )
            )
        }
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `короткий пароль и некорректный email не проходят валидацию`() {
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json(
                mapOf(
                    "email" to "не-email",
                    "password" to "123",
                    "displayName" to "",
                )
            )
        }
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details").isArray)
    }

    @Test
    fun `вход с верным паролем выдаёт токены, с неверным — 401`() {
        val email = uniqueEmail()
        registerUser(email = email)

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("email" to email, "password" to DEFAULT_PASSWORD))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("email" to email, "password" to "неверный-пароль"))
        }
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `вход с незарегистрированным email даёт ту же ошибку, что и неверный пароль`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("email" to uniqueEmail(), "password" to DEFAULT_PASSWORD))
        }
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.message").value("Неверный email или пароль"))
    }

    @Test
    fun `профиль доступен по access-токену и недоступен без него`() {
        val tokens = registerUser()

        mockMvc.get("/api/v1/users/me") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(tokens.email))

        mockMvc.get("/api/v1/users/me")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh ротирует токен, а повторное использование старого отзывает все токены`() {
        val tokens = registerUser()

        val refreshed = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("refreshToken" to tokens.refreshToken))
        }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andReturn()

        val newRefreshToken = objectMapper
            .readTree(refreshed.response.contentAsString)
            .get("refreshToken").asText()

        // Повторное использование уже отозванного токена — признак кражи.
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("refreshToken" to tokens.refreshToken))
        }
            .andExpect(status().isUnauthorized)

        // После этого отзываются ВСЕ токены пользователя, включая только что выданный.
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("refreshToken" to newRefreshToken))
        }
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout делает refresh-токен непригодным`() {
        val tokens = registerUser()

        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("refreshToken" to tokens.refreshToken))
        }
            .andExpect(status().isNoContent)

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("refreshToken" to tokens.refreshToken))
        }
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout-all отзывает все сессии пользователя`() {
        val tokens = registerUser()

        val secondSession = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("email" to tokens.email, "password" to DEFAULT_PASSWORD))
        }
            .andExpect(status().isOk)
            .andReturn()

        val secondRefresh = objectMapper
            .readTree(secondSession.response.contentAsString)
            .get("refreshToken").asText()

        mockMvc.post("/api/v1/auth/logout-all") {
            header("Authorization", bearer(tokens.accessToken))
        }
            .andExpect(status().isNoContent)

        listOf(tokens.refreshToken, secondRefresh).forEach { token ->
            mockMvc.post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = json(mapOf("refreshToken" to token))
            }
                .andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `неизвестный путь под защитой требует авторизации`() {
        mockMvc.get("/api/v1/notes")
            .andExpect(status().isUnauthorized)

        mockMvc.delete("/api/v1/notes/00000000-0000-0000-0000-000000000000")
            .andExpect(status().isUnauthorized)
    }
}
