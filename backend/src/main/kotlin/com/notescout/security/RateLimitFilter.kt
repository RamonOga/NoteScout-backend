package com.notescout.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.notescout.common.ApiError
import com.notescout.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Простое ограничение частоты запросов к эндпоинтам аутентификации.
 *
 * Смысл: без подтверждения email перебор пароля и массовые регистрации —
 * основной вектор злоупотребления. Лимит по IP закрывает базовый сценарий.
 *
 * Счётчики хранятся в памяти процесса: при переходе на несколько инстансов
 * API их нужно вынести в Redis (интерфейс Bucket4j это позволяет без
 * переписывания логики).
 */
@Component
class RateLimitFilter(
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!properties.rateLimit.enabled) return true
        return !(request.method == HttpMethod_POST && request.requestURI in LIMITED_PATHS)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Защита от неограниченного роста карты при распределённой атаке.
        if (buckets.size > MAX_TRACKED_CLIENTS) {
            buckets.clear()
        }

        val bucket = buckets.computeIfAbsent(clientKey(request)) { newBucket() }

        if (!bucket.tryConsume(1)) {
            response.status = HttpServletResponse.SC_TOO_MANY_REQUESTS
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.setHeader("Retry-After", properties.rateLimit.authWindow.seconds.toString())
            objectMapper.writeValue(
                response.writer,
                ApiError(
                    code = "RATE_LIMIT_EXCEEDED",
                    message = "Слишком много попыток. Повторите запрос позже.",
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun newBucket(): Bucket {
        val capacity = properties.rateLimit.authCapacity.toLong()
        return Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(capacity)
                    .refillGreedy(capacity, properties.rateLimit.authWindow)
                    .build()
            )
            .build()
    }

    /** За Caddy реальный IP приходит в X-Forwarded-For; берём первый адрес в цепочке. */
    private fun clientKey(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"

    private companion object {
        const val HttpMethod_POST = "POST"
        const val MAX_TRACKED_CLIENTS = 10_000
        val LIMITED_PATHS = setOf(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
        )
    }
}
