package `in`.porter.archival.app

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Three sources, kept apart on purpose.
 *
 *   archival.properties  the `archive:` block from LLD 4.1 - design decisions.
 *                        Changing one changes what the archive contains, or when
 *   engine.properties    tuning only - how hard the job pushes
 *   environment          where this particular process happens to run
 *
 * Key names match the LLD so the two documents can be read side by side. Any
 * key can still be overridden by an environment variable - CUTOFF_DAYS,
 * TARGET_BUNDLE_BYTES, FETCH_CONCURRENCY - which is how the kind manifests
 * point one image at a different database or a smaller bundle.
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

/** Design configuration. Each field maps to a line in the LLD's archive: block. */
data class ArchivalConfig(
    val sourceBucket: String,
    val sourcePrefix: String,
    val archiveBucket: String,
    val cutoffDays: Int,
    val targetBundleBytes: Long,
    val maxBundleMembers: Int,
    val tarLayoutVersion: Int,
    val partitionKey: String,
    val partitionInterval: String,
    val livePartitions: Int,
    val partitionPremake: Int,
    val dropPolicy: String,
    val docKinds: List<String>,
    val docFormats: List<String>,
    /** Prefixes that are not tax documents and must never be archived. */
    val excludedPrefixes: List<String>,
) {
    companion object {
        fun from(p: Properties) = ArchivalConfig(
            sourceBucket = p.required("sourceBucket"),
            sourcePrefix = p.required("sourcePrefix"),
            archiveBucket = p.required("archiveBucket"),
            cutoffDays = p.required("cutoffDays").toInt(),
            targetBundleBytes = p.required("targetBundleBytes").toLong(),
            maxBundleMembers = p.required("maxBundleMembers").toInt(),
            tarLayoutVersion = p.required("tarLayoutVersion").toInt(),
            partitionKey = p.required("partitioning.key"),
            partitionInterval = p.required("partitioning.interval"),
            livePartitions = p.required("partitioning.livePartitions").toInt(),
            partitionPremake = p.required("partitioning.premake").toInt(),
            dropPolicy = p.required("partitioning.dropPolicy"),
            docKinds = p.required("docKinds").splitList(),
            docFormats = p.required("docFormats").splitList(),
            excludedPrefixes = p.required("excludedPrefixes").splitList(),
        )
    }
}

/** Engineering configuration. Tuning only; none of it changes the archive. */
data class EngineConfig(
    val fetchConcurrency: Int,
    val queueBytes: Long,
    val uploadChunkBytes: Int,
    val runBudgetMinutes: Int,
    val leaseMinutes: Int,
    val maxAttempts: Int,
    val verifySampleRatio: Double,
    val dbPoolSize: Int,
    val dbStatementTimeout: String,
    val listerPageSize: Int,
) {
    init {
        // Firm, and an API constraint rather than a preference: GCS resumable
        // upload rejects a chunk that is not a multiple of 256 KiB.
        require(uploadChunkBytes % (256 * 1024) == 0) {
            "uploadChunkBytes must be a multiple of 256 KiB, got $uploadChunkBytes"
        }
        require(verifySampleRatio in 0.0..1.0) {
            "verifySampleRatio must be between 0 and 1, got $verifySampleRatio"
        }
        require(queueBytes >= uploadChunkBytes) {
            "queueBytes ($queueBytes) must not be smaller than uploadChunkBytes ($uploadChunkBytes)"
        }
    }

    companion object {
        fun from(p: Properties) = EngineConfig(
            fetchConcurrency = p.required("fetchConcurrency").toInt(),
            queueBytes = p.required("queueBytes").toLong(),
            uploadChunkBytes = p.required("uploadChunkBytes").toInt(),
            runBudgetMinutes = p.required("runBudgetMinutes").toInt(),
            leaseMinutes = p.required("leaseMinutes").toInt(),
            maxAttempts = p.required("maxAttempts").toInt(),
            verifySampleRatio = p.required("verifySampleRatio").toDouble(),
            dbPoolSize = p.required("db.poolSize").toInt(),
            dbStatementTimeout = p.required("db.statementTimeout"),
            listerPageSize = p.required("listerPageSize").toInt(),
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

/** Properties file first, environment second: FETCH_CONCURRENCY beats fetchConcurrency. */
private fun Properties.required(key: String): String {
    val envKey = key.replace(".", "_")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .uppercase()
    System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { return it }
    return getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("missing configuration key: $key")
}

private fun String.splitList(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun env(key: String, default: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
