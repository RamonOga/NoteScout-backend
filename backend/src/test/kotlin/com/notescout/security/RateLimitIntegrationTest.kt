package com.notescout.security

import com.notescout.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Лимит частоты включается только для этого класса: свойство создаёт
 * отдельный контекст Spring, остальные тесты работают без ограничений.
 */
@SpringBootTest
@TestPropertySource(
    properties = [
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-capacity=3",
        "app.rate-limit.auth-window=5m",
    ]
)
class RateLimitIntegrationTest : IntegrationTestBase() {

    @Test
    fun `после исчерпания лимита вход отвечает 429`() {
        val email = uniqueEmail()
        val body = json(mapOf("email" to email, "password" to "любой-пароль"))

        // Ёмкость ведра — 3 запроса: первые три доходят до логики, дальше 429.
        repeat(3) {
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
                .andExpect(status().isUnauthorized)
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
    }
}
