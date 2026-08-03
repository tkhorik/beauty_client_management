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

    /**
     * Creates any missing tables.
     *
     * `SchemaUtils.create` only ever creates whole tables — it does **not**
     * alter existing ones. So `OneTimeTokensTable` appears automatically on a
     * database that has never seen it, but `users.email_verified_at` does not,
     * because `users` already exists. That column ships as a hand-written
     * migration in `backend/migrations/`, which must be run before deploying
     * this version against an existing database.
     *
     * The same applies to `clients.organization_id`, `visits.organization_id`
     * and `users.global_role`: `organizations` and `user_organizations` appear
     * here automatically on a fresh database, but the new columns on the three
     * pre-existing tables need `002_multi_tenant_rbac.sql`. `users.suspended_at`
     * needs `003_admin_panel_and_org_creation_links.sql` for the same reason;
     * `OrganizationCreationTokensTable` itself is a brand-new table, so it
     * appears here automatically like `OneTimeTokensTable` did originally.
     */
    private fun createSchema() = transaction {
        SchemaUtils.create(
            UsersTable,
            OrganizationsTable,
            UserOrganizationsTable,
            RefreshTokensTable,
            OneTimeTokensTable,
            OrganizationCreationTokensTable,
            ClientsTable,
            VisitsTable,
            AttachmentsTable
        )
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction { block() }
}
