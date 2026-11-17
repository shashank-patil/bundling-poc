package `in`.porter.archival.data.psql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import `in`.porter.archival.app.Config
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/**
 * Plain JDBC on a small pool. The LLD caps the Lister's connections so that a
 * backfill can never starve the source database's own traffic, and sets a
 * statement timeout so a page can never become a long-running statement.
 */
class Db(config: Config, poolSize: Int = config.engine.dbPoolSize) : AutoCloseable {

    private val ds: DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.runtime.dbUrl
            username = config.runtime.dbUser
            password = config.runtime.dbPassword
            maximumPoolSize = poolSize
            connectionInitSql = "SET statement_timeout = '${config.engine.dbStatementTimeout}'"
            poolName = "archival"
        }
    )

    fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)

    /** All-or-nothing. A member row and its bundle never disagree. */
    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    override fun close() {
        (ds as? HikariDataSource)?.close()
    }

    companion object {
        fun migrate(config: Config) {
            Flyway.configure()
                .dataSource(config.runtime.dbUrl, config.runtime.dbUser, config.runtime.dbPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}
