package com.notescout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class NotesApplication

fun main(args: Array<String>) {
    runApplication<NotesApplication>(*args)
}
