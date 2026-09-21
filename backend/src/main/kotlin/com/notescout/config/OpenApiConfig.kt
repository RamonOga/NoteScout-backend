package com.notescout.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI-описание — контракт для мобильного клиента.
 * Доступно на /swagger-ui.html, JSON-спецификация — на /v3/api-docs.
 */
@Configuration
class OpenApiConfig {

    private companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }

    @Bean
    fun notesOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("NoteScout API")
                .version("v1")
                .description(
                    """
                    API для приложения заметок и ссылок.

                    Авторизация: получите пару токенов через /api/v1/auth/login
                    и передавайте access-токен в заголовке `Authorization: Bearer <token>`.
                    """.trimIndent()
                )
                .contact(Contact().name("NoteScout"))
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
            )
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
}
