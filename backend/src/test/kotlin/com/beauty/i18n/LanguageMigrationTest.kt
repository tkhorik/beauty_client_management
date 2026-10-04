package com.beauty.i18n

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

class LanguageMigrationTest {
    @Test fun `upgrade preserves existing accounts and defaults safely to system`() {
        DriverManager.getConnection("jdbc:h2:mem:migration-${UUID.randomUUID()};MODE=PostgreSQL", "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE users (id VARCHAR(64) PRIMARY KEY, full_name VARCHAR(255) NOT NULL)")
                statement.execute("INSERT INTO users (id, full_name) VALUES ('existing', 'Existing User')")
                val sql = Files.readString(Path.of("migrations/007_account_language.sql"))
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                statement.executeQuery("SELECT full_name, language_preference, language_revision FROM users WHERE id = 'existing'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Existing User", rows.getString(1))
                    assertEquals("system", rows.getString(2))
                    assertEquals(0L, rows.getLong(3))
                }
                assertFailsWith<SQLException> { statement.execute("UPDATE users SET language_preference = 'de'") }
                assertFailsWith<SQLException> { statement.execute("UPDATE users SET language_revision = -1") }
            }
        }
    }
}
