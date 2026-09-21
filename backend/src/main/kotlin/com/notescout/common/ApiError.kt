package com.notescout.common

import java.time.Instant

/**
 * Единый формат ошибки для всех эндпоинтов.
 *
 * Клиент опирается на машиночитаемый `code`, а `message` показывает пользователю.
 */
data class ApiError(
    val code: String,
    val message: String,
    val details: List<FieldViolation> = emptyList(),
    val timestamp: Instant = Instant.now(),
)

data class FieldViolation(
    val field: String,
    val message: String,
)
