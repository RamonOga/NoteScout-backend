package com.notescout.common

import org.springframework.data.domain.Page

/**
 * Универсальный ответ со страницей.
 *
 * Отдаём свой формат, а не сырой `Page` из Spring Data: его JSON-структура
 * меняется между версиями и неудобна для мобильного клиента.
 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
) {
    companion object {
        fun <T> of(source: Page<*>, items: List<T>): PageResponse<T> = PageResponse(
            items = items,
            page = source.number,
            size = source.size,
            totalElements = source.totalElements,
            totalPages = source.totalPages,
            hasNext = source.hasNext(),
        )
    }
}
