package com.notescout.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    /** Email в БД хранится в нижнем регистре, поэтому ищем по нормализованному значению. */
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean
}
