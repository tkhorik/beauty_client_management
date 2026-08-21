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

    /**
     * The connection every query runs against, remembered explicitly.
     *
     * Exposed otherwise resolves the database per call from a thread-local
     * `TransactionManager`, falling back to "the most recently connected one".
     * A server process connects exactly once, so that is invisible in
     * production — but the test suite stands up a fresh in-memory database per
     * `testApplication`, and those H2 instances outlive their test
     * (`DB_CLOSE_DELAY=-1`). A request handler resuming on a pooled dispatcher
     * thread could then inherit a stale manager and run its query, perfectly
     * successfully, against a *previous* test's database — which is why a test
     * would occasionally fail to find a user it had just registered.
     *
     * Naming the database on every transaction removes the ambiguity: queries
     * go where this process connected, never where a leftover thread-local
     * points. `@Volatile` because the app that writes it and the threads that
     * read it are not the same.
     */
    @Volatile
    private var database: Database? = null

    fun init(config: ApplicationConfig) = init(AppSettings(config))

    fun init(settings: AppSettings) {
        try {
            database = Database.connect(hikariDataSource(settings))
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
            database = Database.connect("jdbc:h2:mem:beautydb;DB_CLOSE_DELAY=-1", "org.h2.Driver")
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
     * Finally, `005_attachment_integrity_and_indexes.sql` upgrades existing
     * visit/attachment foreign keys to cascade and adds the lookup indexes.
     */
    private fun createSchema() = transaction(database) {
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

    /**
     * Runs [block] in a suspended transaction against [database].
     *
     * The explicit `db` argument is the point — see the field's own note. A
     * null [database] (nothing has called [init]) falls back to Exposed's
     * default resolution, which is the pre-existing behaviour and still throws
     * a clear "no transaction manager" error rather than silently doing
     * something else.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction(db = database) { block() }
}
