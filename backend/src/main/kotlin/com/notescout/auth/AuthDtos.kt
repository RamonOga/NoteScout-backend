package com.notescout.auth

import com.notescout.user.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank(message = "Email обязателен")
    @field:Email(message = "Некорректный email")
    @field:Size(max = 255, message = "Email слишком длинный")
    val email: String,

    @field:NotBlank(message = "Пароль обязателен")
    @field:Size(min = 8, max = 128, message = "Пароль должен быть от 8 до 128 символов")
    val password: String,

    @field:NotBlank(message = "Имя обязательно")
    @field:Size(max = 100, message = "Имя не длиннее 100 символов")
    val displayName: String,
)

data class LoginRequest(
    @field:NotBlank(message = "Email обязателен")
    @field:Email(message = "Некорректный email")
    val email: String,

    @field:NotBlank(message = "Пароль обязателен")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "Токен обновления обязателен")
    val refreshToken: String,
)

data class LogoutRequest(
    @field:NotBlank(message = "Токен обновления обязателен")
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    /** Время жизни access-токена в секундах. */
    val expiresIn: Long,
    val user: UserResponse,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            createdAt = user.createdAt,
        )
    }
}
