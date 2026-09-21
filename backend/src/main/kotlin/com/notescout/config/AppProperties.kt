package com.notescout.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Настройки приложения из секции `app.*` файла application.yml.
 *
 * Секрет JWT обязателен: приложение намеренно не стартует с пустым значением,
 * чтобы продуктовая среда не поднялась со слабым или дефолтным ключом.
 */
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val jwt: Jwt,
    val rateLimit: RateLimit = RateLimit(),
) {

    data class Jwt(
        val secret: String = "",
        val issuer: String = "notescout-api",
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
        val refreshTokenTtl: Duration = Duration.ofDays(30),
    ) {
        /** Минимум 32 байта — требование алгоритма HS256. */
        fun requireUsableSecret(): ByteArray {
            val bytes = secret.toByteArray(Charsets.UTF_8)
            check(bytes.size >= MIN_SECRET_BYTES) {
                "Переменная окружения JWT_SECRET не задана или короче $MIN_SECRET_BYTES байт. " +
                    "Сгенерируйте секрет командой: openssl rand -base64 48"
            }
            return bytes
        }

        private companion object {
            const val MIN_SECRET_BYTES = 32
        }
    }

    data class RateLimit(
        val enabled: Boolean = true,
        /** Сколько запросов к /api/v1/auth/** разрешено с одного IP за окно. */
        val authCapacity: Int = 10,
        val authWindow: Duration = Duration.ofMinutes(1),
    )
}
