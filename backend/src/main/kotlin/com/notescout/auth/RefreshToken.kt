package com.notescout.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Refresh-токен. В БД лежит только SHA-256 от значения токена:
 * утечка дампа не позволяет войти в аккаунт.
 *
 * Каждый вход с нового устройства создаёт отдельную запись — так работает
 * и отзыв «выйти везде», и список активных устройств в будущем.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    @Column(name = "device_info", length = 255)
    var deviceInfo: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RefreshToken) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
