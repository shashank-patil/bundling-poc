package `in`.porter.archival.app

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Every value the LLD pins as configuration, read from the environment so the
 * same image runs under docker-compose and under kind without a rebuild.
 *
 * The defaults are the LLD's *staging* column, not production: a 1 GiB bundle
 * target and 20,000 members, which is what a laptop can actually demonstrate.
 */
data class Config(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,

    /** Stands in for the GCS bucket holding the source objects. */
    val sourceRoot: Path,
    /** Stands in for the archive bucket the tars are written to. */
    val archiveRoot: Path,
    val sourceBucket: String,
    val archiveBucket: String,
    val sourcePrefix: String,

    val cutoffDays: Int,
    val targetBundleBytes: Long,
    val maxBundleMembers: Int,
    val readers: Int,
    val queueBytes: Long,
    val uploadChunkBytes: Int,
    /** Keyset page size for the OMS worklist query. */
    val listerPageSize: Int,
    val leaseMinutes: Int,
) {
    companion object {
        fun fromEnv(): Config = Config(
            dbUrl = env("DB_URL", "jdbc:postgresql://localhost:55432/archival"),
            dbUser = env("DB_USER", "archival"),
            dbPassword = env("DB_PASSWORD", "archival"),

            sourceRoot = Paths.get(env("SOURCE_ROOT", "var/sources")).toAbsolutePath(),
            archiveRoot = Paths.get(env("ARCHIVE_ROOT", "var/archive")).toAbsolutePath(),
            sourceBucket = env("SOURCE_BUCKET", "dom-ordersystem-prod-gcs-completed-orders"),
            archiveBucket = env("ARCHIVE_BUCKET", "dom-invoice-archive-poc"),
            sourcePrefix = env("SOURCE_PREFIX", "order-invoices/"),

            cutoffDays = env("CUTOFF_DAYS", "90").toInt(),
            targetBundleBytes = env("TARGET_BUNDLE_BYTES", "1073741824").toLong(),
            maxBundleMembers = env("MAX_BUNDLE_MEMBERS", "20000").toInt(),
            readers = env("READERS", "32").toInt(),
            queueBytes = env("QUEUE_BYTES", "67108864").toLong(),
            uploadChunkBytes = env("UPLOAD_CHUNK_BYTES", "16777216").toInt(),
            listerPageSize = env("LISTER_PAGE_SIZE", "20000").toInt(),
            leaseMinutes = env("LEASE_MINUTES", "120").toInt(),
        )

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}
