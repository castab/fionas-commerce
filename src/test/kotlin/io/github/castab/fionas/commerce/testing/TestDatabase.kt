package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * A throwaway PostgreSQL database on the server Gradle provides to the test JVM (a Docker
 * container started by build.gradle.kts, or TEST_DATABASE_JDBC_URL).
 *
 * Each spec creates its own database, lets commerce-runtime apply the real commerce and
 * Fiona migrations, and drops the database when closed. There is no separate test schema.
 */
class TestDatabase private constructor(
    private val name: String,
    val configuration: CommerceRuntimeConfiguration.Database,
) : AutoCloseable {
    /**
     * Runs [block] on a separate, auto-committing connection, outside commerce-runtime:
     * what it sees is what other clients of the database see.
     */
    fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection(configuration.jdbcUrl, configuration.username, configuration.password).use(block)

    /** The rows of [table] visible to other clients, that is, committed rows. */
    fun count(table: String): Int =
        connect { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** The values of the first column of [sql], one per row. */
    fun strings(sql: String): List<String> =
        connect { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    override fun close() {
        admin { it.createStatement().use { statement -> statement.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    companion object {
        private fun property(name: String): String =
            System.getProperty("fionas.test.database.$name")
                ?: error("fionas.test.database.$name is not set; run the tests through Gradle")

        private val serverUrl by lazy { property("jdbcUrl") }
        private val username by lazy { property("username") }
        private val password by lazy { property("password") }

        private fun <T> admin(block: (Connection) -> T): T = DriverManager.getConnection(serverUrl, username, password).use(block)

        fun create(): TestDatabase {
            val name = "fionas_test_${UUID.randomUUID().toString().replace("-", "")}"
            admin { it.createStatement().use { statement -> statement.execute("CREATE DATABASE $name") } }
            val base = serverUrl.substringBefore('?')
            val query = serverUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
            val jdbcUrl = base.substringBeforeLast('/') + "/" + name + query
            return TestDatabase(
                name,
                CommerceRuntimeConfiguration.Database(
                    jdbcUrl = jdbcUrl,
                    username = username,
                    password = password,
                    maximumPoolSize = 4,
                    minimumIdle = 0,
                    connectionTimeoutMs = 5_000,
                ),
            )
        }
    }
}
