package com.notescout.security

import java.util.UUID

/**
 * Текущий аутентифицированный пользователь.
 * Достаётся в контроллерах через `@AuthenticationPrincipal`.
 */
data class UserPrincipal(
    val id: UUID,
    val email: String,
)
