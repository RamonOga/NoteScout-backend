package com.notescout.common

/**
 * Доменные исключения приложения.
 *
 * Осознанно не используем голые IllegalArgumentException/IllegalStateException:
 * каждое исключение однозначно отображается на HTTP-код в GlobalExceptionHandler.
 */

/** Ресурс не найден или принадлежит другому пользователю (не раскрываем факт существования). */
class NotFoundException(message: String) : RuntimeException(message)

/** Нарушение уникальности: email уже занят и т.п. */
class ConflictException(message: String) : RuntimeException(message)

/** Некорректные данные, которые не ловятся Bean Validation. */
class BadRequestException(message: String) : RuntimeException(message)

/** Не прошла аутентификация: неверный пароль, истёкший или отозванный токен. */
class UnauthorizedException(message: String) : RuntimeException(message)

/** Превышен лимит частоты запросов. */
class RateLimitExceededException(message: String = "Слишком много запросов. Попробуйте позже.") :
    RuntimeException(message)
