package `in`.porter.archival.app

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Three sources, kept apart on purpose.
 *
 *   archival.properties  design decisions from the LLD - changing one changes
 *                        what the archive contains, or when
 *   engine.properties    tuning only - how hard the job pushes
 *   environment          where this particular process happens to run
 *
 * Any key can still be overridden by an environment variable, which is how the
 * kind manifests point the same image at a different database or folder.
 */
data class Config(
    val archival: ArchivalConfig,
    val engine: EngineConfig,
    val runtime: RuntimeConfig,
) {
    companion object {
        fun load(): Config = Config(
            archival = ArchivalConfig.from(read("archival.properties")),
            engine = EngineConfig.from(read("engine.properties")),
            runtime = RuntimeConfig.fromEnv(),
        )

        private fun read(resource: String): Properties =
            Properties().apply {
                Config::class.java.classLoader.getResourceAsStream(resource)
                    ?.use { load(it) }
                    ?: error("missing config resource: $resource")
            }
    }
}

/** Design configuration. Each field maps to a decision in the LLD. */
data class ArchivalConfig(
    val sourceBucket: String,
    val sourcePrefix: String,
    val archiveBucket: String,
    val cutoffDays: Int,
    val bundleTargetBytes: Long,
    val bundleMaxMembers: Int,
    val membersRetentionMonths: Int,
    val tarLayoutVersion: Int,
    val docKinds: List<String>,
    val docFormats: List<String>,
) {
    companion object {
        fun from(p: Properties) = ArchivalConfig(
            sourceBucket = p.required("source.bucket"),
            sourcePrefix = p.required("source.prefix"),
            archiveBucket = p.required("archive.bucket"),
            cutoffDays = p.required("cutoff.days").toInt(),
            bundleTargetBytes = p.required("bundle.target.bytes").toLong(),
            bundleMaxMembers = p.required("bundle.max.members").toInt(),
            membersRetentionMonths = p.required("members.retention.months").toInt(),
            tarLayoutVersion = p.required("tar.layout.version").toInt(),
            docKinds = p.required("doc.kinds").split(',').map { it.trim() },
            docFormats = p.required("doc.formats").split(',').map { it.trim() },
        )
    }
}

/** Engineering configuration. Tuning only; none of it changes the archive. */
data class EngineConfig(
    val dbPoolSize: Int,
    val dbStatementTimeout: String,
    val listerPageSize: Int,
    val bundlerReaders: Int,
    val bundlerQueueBytes: Long,
    val bundlerChunkBytes: Int,
    val bundlerLeaseMinutes: Int,
    val verifyFullByteScan: Boolean,
    val verifySampleSize: Int,
) {
    companion object {
        fun from(p: Properties) = EngineConfig(
            dbPoolSize = p.required("db.pool.size").toInt(),
            dbStatementTimeout = p.required("db.statement.timeout"),
            listerPageSize = p.required("lister.page.size").toInt(),
            bundlerReaders = p.required("bundler.readers").toInt(),
            bundlerQueueBytes = p.required("bundler.queue.bytes").toLong(),
            bundlerChunkBytes = p.required("bundler.chunk.bytes").toInt(),
            bundlerLeaseMinutes = p.required("bundler.lease.minutes").toInt(),
            verifyFullByteScan = p.required("verify.full.byte.scan").toBoolean(),
            verifySampleSize = p.required("verify.sample.size").toInt(),
        )
    }
}

/**
 * Where this process happens to be running. Not a design decision and not a
 * tuning knob - just the address of the database and the two folders standing
 * in for the GCS buckets.
 */
data class RuntimeConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val sourceRoot: Path,
    val archiveRoot: Path,
) {
    companion object {
        fun fromEnv() = RuntimeConfig(
            dbUrl = env("DB_URL", "jdbc:postgresql://localhost:55432/archival"),
            dbUser = env("DB_USER", "archival"),
            dbPassword = env("DB_PASSWORD", "archival"),
            sourceRoot = Paths.get(env("SOURCE_ROOT", "var/sources")).toAbsolutePath(),
            archiveRoot = Paths.get(env("ARCHIVE_ROOT", "var/archive")).toAbsolutePath(),
        )
    }
}

/**
 * Properties file first, environment second: SOURCE_BUCKET overrides
 * source.bucket, BUNDLE_TARGET_BYTES overrides bundle.target.bytes, and so on.
 */
private fun Properties.required(key: String): String {
    val fromEnv = System.getenv(key.uppercase().replace('.', '_'))
    if (!fromEnv.isNullOrBlank()) return fromEnv
    return getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("missing configuration key: $key")
}

private fun env(key: String, default: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
