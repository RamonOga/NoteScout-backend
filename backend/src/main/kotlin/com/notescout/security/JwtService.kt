package com.notescout.security

import com.notescout.config.AppProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

data class AccessTokenData(
    val userId: UUID,
    val email: String,
)

/**
 * Выпуск и проверка access-токенов (JWT, HS256).
 *
 * Секрет читается из окружения. Ключ инициализируется в момент создания бина:
 * при пустом или слишком коротком секрете приложение не стартует — так
 * продуктовая среда не поднимется со слабым ключом.
 */
@Service
class JwtService(
    private val properties: AppProperties,
    private val clock: Clock,
) {

    private val key: SecretKey = Keys.hmacShaKeyFor(properties.jwt.requireUsableSecret())

    fun issueAccessToken(userId: UUID, email: String): String {
        val now = clock.instant()
        return Jwts.builder()
            .issuer(properties.jwt.issuer)
            .subject(userId.toString())
            .claim(CLAIM_EMAIL, email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.jwt.accessTokenTtl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /** @return данные токена либо null, если подпись/срок/издатель не сошлись. */
    fun parseAccessToken(token: String): AccessTokenData? =
        try {
            val claims: Claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.jwt.issuer)
                .build()
                .parseSignedClaims(token)
                .payload

            val subject = claims.subject ?: return null
            AccessTokenData(
                userId = UUID.fromString(subject),
                email = claims.get(CLAIM_EMAIL, String::class.java) ?: "",
            )
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Время жизни access-токена в секундах — отдаётся клиенту в ответе на логин. */
    val accessTokenTtlSeconds: Long
        get() = properties.jwt.accessTokenTtl.seconds

    private companion object {
        const val CLAIM_EMAIL = "email"
    }
}
