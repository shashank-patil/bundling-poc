package `in`.porter.archival.app

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Three sources, kept apart on purpose.
 *
 *   archival.yaml        a verbatim copy of the `archive:` block in LLD 4.1.
 *                        Design decisions - changing one changes what the
 *                        archive contains, or when
 *   engine.properties    everything the LLD does not specify. Internal tuning
 *   environment          where this particular process happens to run
 *
 * Any key can still be overridden by an environment variable - CUTOFF_DAYS,
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
            archival = ArchivalConfig.from(readYaml("archival.yaml")),
            engine = EngineConfig.from(readProperties("engine.properties")),
            runtime = RuntimeConfig.fromEnv(),
        )

        @Suppress("UNCHECKED_CAST")
        private fun readYaml(resource: String): Map<String, Any?> {
            val stream = Config::class.java.classLoader.getResourceAsStream(resource)
                ?: error("missing config resource: $resource")
            val root = stream.use { Yaml().load<Map<String, Any?>>(it) }
            return root["archive"] as? Map<String, Any?>
                ?: error("$resource has no top-level `archive:` block")
        }

        private fun readProperties(resource: String): Properties =
            Properties().apply {
                Config::class.java.classLoader.getResourceAsStream(resource)
                    ?.use { load(it) }
                    ?: error("missing config resource: $resource")
            }
    }
}

/** The LLD's archive: block, field for field. */
data class ArchivalConfig(
    val sourceBucket: String,
    val sourcePrefix: String,
    val archiveBucket: String,
    val cutoffDays: Int,
    val targetBundleBytes: Long,
    val maxBundleMembers: Int,
    val uploadChunkBytes: Int,
    val queueBytes: Long,
    val fetchConcurrency: Int,
    val runBudgetMinutes: Int,
    val leaseMinutes: Int,
    val maxAttempts: Int,
    val verifySampleRatio: Double,
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
    init {
        // Firm, and an API constraint rather than a preference: GCS resumable
        // upload rejects a chunk that is not a multiple of 256 KiB.
        require(uploadChunkBytes % (256 * 1024) == 0) {
            "uploadChunkBytes must be a multiple of 256KiB, got $uploadChunkBytes"
        }
        require(verifySampleRatio in 0.0..1.0) {
            "verifySampleRatio must be between 0 and 1, got $verifySampleRatio"
        }
        require(queueBytes >= uploadChunkBytes) {
            "queueBytes ($queueBytes) must not be smaller than uploadChunkBytes ($uploadChunkBytes)"
        }
        require(fetchConcurrency >= 1) { "fetchConcurrency must be at least 1" }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(y: Map<String, Any?>): ArchivalConfig {
            val partitioning = y.required("partitioning") as Map<String, Any?>
            return ArchivalConfig(
                sourceBucket = y.string("sourceBucket"),
                sourcePrefix = y.string("sourcePrefix"),
                archiveBucket = y.string("archiveBucket"),
                cutoffDays = y.int("cutoffDays"),
                targetBundleBytes = y.bytes("targetBundleBytes"),
                maxBundleMembers = y.int("maxBundleMembers"),
                uploadChunkBytes = y.bytes("uploadChunkBytes").toInt(),
                queueBytes = y.bytes("queueBytes"),
                fetchConcurrency = y.int("fetchConcurrency"),
                runBudgetMinutes = y.minutes("runBudget"),
                leaseMinutes = y.int("leaseMinutes"),
                maxAttempts = y.int("maxAttempts"),
                verifySampleRatio = y.string("verifySampleRatio").toDouble(),
                tarLayoutVersion = y.int("tarLayoutVersion"),
                partitionKey = partitioning.string("key"),
                partitionInterval = partitioning.string("interval"),
                livePartitions = partitioning.int("livePartitions"),
                partitionPremake = partitioning.int("premake"),
                dropPolicy = partitioning.string("dropPolicy"),
                docKinds = y.list("docKinds"),
                docFormats = y.list("docFormats"),
                excludedPrefixes = y.list("excludedPrefixes"),
            )
        }
    }
}

/** Everything the LLD does not specify. */
data class EngineConfig(
    val dbPoolSize: Int,
    val dbStatementTimeout: String,
    val listerPageSize: Int,
    val copyBufferBytes: Int,
) {
    companion object {
        fun from(p: Properties) = EngineConfig(
            dbPoolSize = p.required("db.poolSize").toInt(),
            dbStatementTimeout = p.required("db.statementTimeout"),
            listerPageSize = p.required("listerPageSize").toInt(),
            copyBufferBytes = p.required("io.copyBufferBytes").toInt(),
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

// --- lookup helpers: YAML value first, environment override second -----------

private fun envKeyFor(key: String): String =
    key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace('.', '_').uppercase()

private fun Map<String, Any?>.required(key: String): Any =
    System.getenv(envKeyFor(key))?.takeIf { it.isNotBlank() }
        ?: this[key]
        ?: error("missing configuration key: $key")

private fun Map<String, Any?>.string(key: String): String = required(key).toString().trim()

private fun Map<String, Any?>.int(key: String): Int = string(key).toInt()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<String> {
    val raw = required(key)
    return when (raw) {
        is List<*> -> raw.map { it.toString().trim() }
        else -> raw.toString().split(',').map { it.trim() }
    }.filter { it.isNotEmpty() }
}

/** Accepts the LLD's own notation: 6GiB, 16MiB, 64MiB - or a plain byte count. */
private fun Map<String, Any?>.bytes(key: String): Long = Units.toBytes(string(key), key)

/** Accepts 45m, 2h, or a plain number of minutes. */
private fun Map<String, Any?>.minutes(key: String): Int = Units.toMinutes(string(key), key)

private fun Properties.required(key: String): String {
    System.getenv(envKeyFor(key))?.takeIf { it.isNotBlank() }?.let { return it }
    return getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("missing configuration key: $key")
}

private fun env(key: String, default: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

/** So the config file can read the way the LLD writes it. */
internal object Units {
    private val SIZE = Regex("""^(\d+)\s*(B|KiB|MiB|GiB|TiB)?$""", RegexOption.IGNORE_CASE)
    private val DURATION = Regex("""^(\d+)\s*(s|m|h)?$""", RegexOption.IGNORE_CASE)

    fun toBytes(value: String, key: String = "value"): Long {
        val m = SIZE.matchEntire(value) ?: error("$key is not a byte size: $value")
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) {
            "", "b" -> n
            "kib" -> n shl 10
            "mib" -> n shl 20
            "gib" -> n shl 30
            "tib" -> n shl 40
            else -> error("$key has an unknown size unit: $value")
        }
    }

    fun toMinutes(value: String, key: String = "value"): Int {
        val m = DURATION.matchEntire(value) ?: error("$key is not a duration: $value")
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2].lowercase()) {
            "", "m" -> n
            "s" -> (n + 59) / 60
            "h" -> n * 60
            else -> error("$key has an unknown duration unit: $value")
        }
    }
}
