package com.notescout.common

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException

/**
 * Превращает исключения в единый JSON-формат [ApiError].
 *
 * Важно: наружу никогда не уходят стектрейсы и внутренние детали — они пишутся в лог.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<ApiError> =
        build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message ?: "Ресурс не найден")

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ApiError> =
        build(HttpStatus.CONFLICT, "CONFLICT", ex.message ?: "Конфликт данных")

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<ApiError> =
        build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.message ?: "Некорректный запрос")

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<ApiError> =
        build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.message ?: "Требуется авторизация")

    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimit(ex: RateLimitExceededException): ResponseEntity<ApiError> =
        build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", ex.message!!)

    @ExceptionHandler(PayloadTooLargeException::class)
    fun handlePayloadTooLarge(ex: PayloadTooLargeException): ResponseEntity<ApiError> =
        build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", ex.message!!)

    /**
     * Файл не прошёл ограничение multipart-разбора.
     *
     * Spring обрывает чтение раньше, чем запрос дойдёт до контроллера, поэтому
     * до собственной проверки в сервисе дело не доходит. Отвечаем тем же кодом,
     * что и на превышение квоты: для клиента это одно и то же событие.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException): ResponseEntity<ApiError> {
        log.warn("Загрузка отклонена по размеру: {}", ex.message)
        return build(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "PAYLOAD_TOO_LARGE",
            "Файл слишком большой",
        )
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException): ResponseEntity<ApiError> =
        build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Требуется авторизация")

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ApiError> =
        build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Доступ запрещён")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val violations = ex.bindingResult.fieldErrors.map {
            FieldViolation(field = it.field, message = it.defaultMessage ?: "Некорректное значение")
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = "Запрос не прошёл валидацию",
                details = violations,
            )
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiError> {
        val violations = ex.constraintViolations.map {
            FieldViolation(field = it.propertyPath.toString(), message = it.message)
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = "Запрос не прошёл валидацию",
                details = violations,
            )
        )
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleMalformedRequest(ex: Exception): ResponseEntity<ApiError> =
        build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Тело или параметры запроса некорректны")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiError> {
        log.warn("Нарушено ограничение целостности БД: {}", ex.mostSpecificCause.message)
        return build(HttpStatus.CONFLICT, "CONFLICT", "Операция нарушает целостность данных")
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        log.error("Необработанная ошибка", ex)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера")
    }

    private fun build(status: HttpStatus, code: String, message: String): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(ApiError(code = code, message = message))
}
