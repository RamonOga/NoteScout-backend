package com.notescout.auth

import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Аутентификация", description = "Регистрация, вход, обновление токенов и выход")
class AuthController(
    private val authService: AuthService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Регистрация нового пользователя")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        @RequestHeader(value = "User-Agent", required = false) userAgent: String?,
    ): TokenResponse = authService.register(request, userAgent)

    @PostMapping("/login")
    @Operation(summary = "Вход по email и паролю")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        @RequestHeader(value = "User-Agent", required = false) userAgent: String?,
    ): TokenResponse = authService.login(request, userAgent)

    @PostMapping("/refresh")
    @Operation(summary = "Обмен refresh-токена на новую пару токенов")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
        @RequestHeader(value = "User-Agent", required = false) userAgent: String?,
    ): TokenResponse = authService.refresh(request.refreshToken, userAgent)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отзыв refresh-токена")
    fun logout(@Valid @RequestBody request: LogoutRequest) {
        authService.logout(request.refreshToken)
    }

    /** Выход со всех устройств: отзывает все действующие refresh-токены пользователя. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Выход со всех устройств")
    fun logoutAll(@AuthenticationPrincipal principal: UserPrincipal) {
        authService.logoutAll(principal.id)
    }
}
