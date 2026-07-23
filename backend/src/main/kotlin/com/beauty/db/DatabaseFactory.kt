package com.beauty.db

import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val driverClassName = config.propertyOrNull("db.driver")?.getString() ?: "org.h2.Driver"
        val jdbcUrl = config.propertyOrNull("db.url")?.getString() ?: "jdbc:h2:mem:beautydb;DB_CLOSE_DELAY=-1"
        val user = config.propertyOrNull("db.user")?.getString() ?: "root"
        val password = config.propertyOrNull("db.password")?.getString() ?: ""

        try {
            Database.connect(jdbcUrl, driverClassName, user, password)
            transaction {
                SchemaUtils.create(UsersTable, ClientsTable, VisitsTable, AttachmentsTable)
            }
            println("Database connected successfully: $jdbcUrl")
        } catch (e: Exception) {
            println("Failed to connect to primary DB, falling back to H2 in-memory DB: ${e.message}")
            Database.connect("jdbc:h2:mem:beautydb;DB_CLOSE_DELAY=-1", "org.h2.Driver")
            transaction {
                SchemaUtils.create(UsersTable, ClientsTable, VisitsTable, AttachmentsTable)
            }
        }

        // Ensure upload directory exists
        val uploadDir = File("uploads")
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction { block() }
}
