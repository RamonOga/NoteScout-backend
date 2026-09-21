package com.notescout.auth

import com.notescout.security.UserPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Пользователь", description = "Профиль текущего пользователя")
class UserController(
    private val authService: AuthService,
) {

    @GetMapping("/me")
    @Operation(summary = "Профиль текущего пользователя")
    fun me(@AuthenticationPrincipal principal: UserPrincipal): UserResponse = authService.me(principal.id)
}
