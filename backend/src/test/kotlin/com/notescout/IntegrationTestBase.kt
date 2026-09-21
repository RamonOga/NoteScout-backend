package com.notescout

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * База интеграционных тестов.
 *
 * PostgreSQL поднимается через Testcontainers, поэтому проверяются реальные
 * SQL-запросы (нативный поиск, `on conflict do nothing`, генерируемая колонка),
 * а не эмуляция на H2. Контейнер стартует один раз на JVM и переиспользуется
 * всеми тестовыми классами.
 *
 * Аннотация @SpringBootTest намеренно не стоит здесь: её ставит каждый
 * конкретный тест, чтобы можно было точечно менять набор свойств.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    protected fun json(value: Any): String = objectMapper.writeValueAsString(value)

    protected fun uniqueEmail(): String = "user-${UUID.randomUUID()}@example.com"

    /** Регистрирует нового пользователя и возвращает пару токенов. */
    protected fun registerUser(
        email: String = uniqueEmail(),
        password: String = DEFAULT_PASSWORD,
        displayName: String = "Тестовый пользователь",
    ): TokenPair {
        val result = mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json(
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to displayName,
                )
            )
        }
            .andExpect(status().isCreated)
            .andReturn()

        val node = objectMapper.readTree(result.response.contentAsString)
        return TokenPair(
            accessToken = node.get("accessToken").asText(),
            refreshToken = node.get("refreshToken").asText(),
            email = email,
        )
    }

    protected fun bearer(accessToken: String): String = "Bearer $accessToken"

    protected data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val email: String,
    )

    companion object {
        const val DEFAULT_PASSWORD = "secret-password"

        /**
         * Имя базы, пользователь и пароль берутся из значений по умолчанию
         * Testcontainers (test/test/test). Цепочку `withDatabaseName(...)`
         * здесь построить нельзя: у `PostgreSQLContainer<Nothing>` методы
         * конфигурации возвращают тип-параметр SELF, то есть Nothing.
         */
        private val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

        /**
         * Контейнер стартует вручную, а свойства передаются через
         * @DynamicPropertySource: так надёжнее, чем полагаться на сканирование
         * полей companion-объекта расширением Testcontainers. Метод вызывается
         * до создания бинов, поэтому DataSource получает уже готовый URL.
         */
        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
