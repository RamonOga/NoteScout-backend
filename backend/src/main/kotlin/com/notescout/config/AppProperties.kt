package com.notescout.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
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
    val attachments: Attachments = Attachments(),
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
        /**
         * Сколько запросов к эндпоинтам аутентификации разрешено с одного IP за окно.
         *
         * Пути здесь намеренно не пишутся: последовательность из слэша и звёздочки
         * внутри комментария Kotlin открывает вложенный блочный комментарий —
         * комментарии в этом языке вложенные.
         */
        val authCapacity: Int = 10,
        val authWindow: Duration = Duration.ofMinutes(1),
    )

    /**
     * Вложения к заметкам.
     *
     * Размеры ограничены, потому что диск сервера конечен: без квоты телефон
     * с фотографиями забьёт его за месяц. Значения по умолчанию рассчитаны на
     * личное использование, а не на сервис с тысячами пользователей.
     */
    data class Attachments(
        /** Каталог хранения файлов. В контейнере это том. */
        val directory: String = "/var/lib/notescout/attachments",
        val maxFileSize: DataSize = DataSize.ofMegabytes(10),
        val maxTotalPerUser: DataSize = DataSize.ofMegabytes(1024),
    )
}
