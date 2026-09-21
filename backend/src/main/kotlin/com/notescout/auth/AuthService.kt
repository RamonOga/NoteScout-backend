package com.notescout.auth

import com.notescout.common.ConflictException
import com.notescout.common.UnauthorizedException
import com.notescout.config.AppProperties
import com.notescout.security.JwtService
import com.notescout.user.User
import com.notescout.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val tokenRevocationService: TokenRevocationService,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val properties: AppProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Хеш-заглушка для выравнивания времени ответа. Без неё запрос с
     * незарегистрированным email отвечает заметно быстрее, чем с существующим,
     * и по времени можно перебором выяснить, какие адреса в системе есть.
     */
    private val dummyPasswordHash: String = passwordEncoder.encode("timing-attack-equalizer")

    @Transactional
    fun register(request: RegisterRequest, deviceInfo: String?): TokenResponse {
        val email = normalizeEmail(request.email)

        if (userRepository.existsByEmail(email)) {
            throw ConflictException("Пользователь с таким email уже зарегистрирован")
        }

        val user = userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(request.password),
                displayName = request.displayName.trim(),
            )
        )

        log.info("Зарегистрирован новый пользователь id={}", user.id)
        return issueTokens(user, deviceInfo)
    }

    @Transactional
    fun login(request: LoginRequest, deviceInfo: String?): TokenResponse {
        val email = normalizeEmail(request.email)
        val user = userRepository.findByEmail(email)

        val passwordMatches = if (user != null) {
            passwordEncoder.matches(request.password, user.passwordHash)
        } else {
            passwordEncoder.matches(request.password, dummyPasswordHash)
            false
        }

        // Один и тот же текст ошибки для «нет такого пользователя» и «неверный пароль».
        if (user == null || !passwordMatches) {
            throw UnauthorizedException("Неверный email или пароль")
        }

        return issueTokens(user, deviceInfo)
    }

    /**
     * Обмен refresh-токена на новую пару с ротацией.
     *
     * Если предъявлен уже отозванный токен — это признак кражи: отзываем все
     * токены пользователя, чтобы злоумышленник и владелец одинаково потеряли доступ,
     * а владелец вошёл заново по паролю.
     *
     * Отзыв идёт в ОТДЕЛЬНОЙ транзакции: следом мы бросаем UnauthorizedException,
     * и отзыв в общей транзакции откатился бы вместе с ним.
     */
    @Transactional
    fun refresh(rawRefreshToken: String, deviceInfo: String?): TokenResponse {
        val now = clock.instant()
        val stored = refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
            ?: throw UnauthorizedException("Недействительный токен обновления")

        if (stored.revokedAt != null) {
            log.warn(
                "Повторное использование отозванного refresh-токена, userId={}. Отзываю все токены.",
                stored.userId,
            )
            tokenRevocationService.revokeAllInNewTransaction(stored.userId, now)
            throw UnauthorizedException("Токен обновления уже был использован")
        }

        if (!stored.expiresAt.isAfter(now)) {
            throw UnauthorizedException("Срок действия токена обновления истёк")
        }

        val user = userRepository.findById(stored.userId)
            .orElseThrow { UnauthorizedException("Пользователь не найден") }

        stored.revokedAt = now
        refreshTokenRepository.save(stored)

        return issueTokens(user, deviceInfo)
    }

    /** Идемпотентный выход: неизвестный токен не считается ошибкой. */
    @Transactional
    fun logout(rawRefreshToken: String) {
        val stored = refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken)) ?: return
        if (stored.revokedAt == null) {
            stored.revokedAt = clock.instant()
            refreshTokenRepository.save(stored)
        }
    }

    /** Выход со всех устройств: отзываются все действующие refresh-токены. */
    @Transactional
    fun logoutAll(userId: UUID) {
        val revoked = refreshTokenRepository.revokeAllForUser(userId, clock.instant())
        log.info("Отозвано refresh-токенов пользователя {}: {}", userId, revoked)
    }

    @Transactional(readOnly = true)
    fun me(userId: UUID): UserResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UnauthorizedException("Пользователь не найден") }
        return UserResponse.from(user)
    }

    private fun issueTokens(user: User, deviceInfo: String?): TokenResponse {
        val rawRefreshToken = generateRefreshToken()

        refreshTokenRepository.save(
            RefreshToken(
                userId = user.id,
                tokenHash = sha256(rawRefreshToken),
                deviceInfo = deviceInfo?.take(255),
                expiresAt = clock.instant().plus(properties.jwt.refreshTokenTtl),
            )
        )

        return TokenResponse(
            accessToken = jwtService.issueAccessToken(user.id, user.email),
            refreshToken = rawRefreshToken,
            expiresIn = jwtService.accessTokenTtlSeconds,
            user = UserResponse.from(user),
        )
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private companion object {
        const val REFRESH_TOKEN_BYTES = 48
        val SECURE_RANDOM = SecureRandom()
    }
}
