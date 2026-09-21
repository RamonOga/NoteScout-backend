package com.notescout.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Часы выносим в бин: сроки жизни токенов и «мягкое» время тестируются
 * подменой Clock, без ожидания реального времени.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
