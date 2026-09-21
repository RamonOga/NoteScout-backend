package com.notescout.security

import com.notescout.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Читает `Authorization: Bearer <access>` и, если токен валиден,
 * кладёт аутентификацию в SecurityContext.
 *
 * Пользователь дополнительно перечитывается из БД: удалённый аккаунт теряет
 * доступ немедленно, а не по истечении access-токена.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)

        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            val data = jwtService.parseAccessToken(token)
            if (data != null) {
                val user = userRepository.findById(data.userId).orElse(null)
                if (user != null) {
                    val principal = UserPrincipal(id = user.id, email = user.email)
                    val authentication = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority(ROLE_USER)),
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val ROLE_USER = "ROLE_USER"
    }
}
