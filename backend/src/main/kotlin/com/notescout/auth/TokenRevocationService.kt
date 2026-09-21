package com.notescout.auth

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Отзыв refresh-токенов в отдельной транзакции.
 *
 * Зачем отдельный бин: аннотация @Transactional работает через прокси, поэтому
 * вызов `@Transactional(REQUIRES_NEW)` из метода того же класса её бы не применил.
 *
 * Зачем REQUIRES_NEW: отзыв всех сессий выполняется в ветке, которая сразу после
 * этого бросает UnauthorizedException. Если отзыв делать в общей транзакции, он
 * откатится вместе с исключением — и обнаруженная кража токена останется
 * незамеченной, а украденный токен продолжит работать.
 */
@Service
class TokenRevocationService(
    private val refreshTokenRepository: RefreshTokenRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeAllInNewTransaction(userId: UUID, now: Instant): Int =
        refreshTokenRepository.revokeAllForUser(userId, now)
}
