package com.notescout.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * Отзывает все действующие токены пользователя.
     * Используется при выходе «со всех устройств» и при обнаружении повторного
     * использования уже отозванного токена (признак кражи).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken rt set rt.revokedAt = :now where rt.userId = :userId and rt.revokedAt is null")
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    /** Чистка старых записей (вызывается вручную/по расписанию). */
    @Modifying
    @Query("delete from RefreshToken rt where rt.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
