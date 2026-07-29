package com.beauty.db

import com.beauty.config.AppSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: ApplicationConfig) = init(AppSettings(config))

    fun init(settings: AppSettings) {
        try {
            Database.connect(hikariDataSource(settings))
            createSchema()
            log.info("Database connected: {}", settings.dbUrl)
        } catch (e: Exception) {
            if (!settings.allowH2Fallback) {
                // In production this is the correct behaviour: crash the
                // container so Docker restarts it and the deploy is visibly
                // broken, instead of serving an in-memory database that
                // discards every write on the next restart.
                throw IllegalStateException(
                    "Cannot connect to the database at ${settings.dbUrl} and H2 fallback is disabled. " +
                        "Check DB_URL / DB_USER / DB_PASSWORD and that the database is reachable.",
                    e
                )
            }
            log.warn(
                "Primary database unreachable ({}). Falling back to IN-MEMORY H2 — all data is lost on restart.",
                e.message
            )
            Database.connect("jdbc:h2:mem:beautydb;DB_CLOSE_DELAY=-1", "org.h2.Driver")
            createSchema()
        }

        if (!settings.uploadDir.exists() && !settings.uploadDir.mkdirs()) {
            throw IllegalStateException("Cannot create upload directory: ${settings.uploadDir.absolutePath}")
        }
    }

    /**
     * A pooled DataSource, rather than Exposed's bare `Database.connect(url, ...)`,
     * which opens a fresh JDBC connection per transaction. Under concurrent
     * traffic that exhausts Postgres' connection limit and adds full TCP +
     * authentication latency to every single request.
     */
    private fun hikariDataSource(settings: AppSettings): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = settings.dbDriver
            jdbcUrl = settings.dbUrl
            username = settings.dbUser
            password = settings.dbPassword
            maximumPoolSize = settings.dbMaxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // Fail during startup instead of hanging the first request.
            initializationFailTimeout = 10_000L
            validate()
        }
        return HikariDataSource(config)
    }

    private fun createSchema() = transaction {
        SchemaUtils.create(UsersTable, RefreshTokensTable, ClientsTable, VisitsTable, AttachmentsTable)
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction { block() }
}
