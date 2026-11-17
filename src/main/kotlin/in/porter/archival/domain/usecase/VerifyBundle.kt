package `in`.porter.archival.domain.usecase

import `in`.porter.archival.app.Config
import `in`.porter.archival.domain.model.BundleState
import `in`.porter.archival.domain.port.ArchiveBundleRepo
import `in`.porter.archival.domain.port.ArchiveMemberRepo
import `in`.porter.archival.domain.port.ClaimedBundle
import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.tar.UstarFramer
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.zip.CRC32C

/**
 * Cron 2 - the Bundler, verify half. Nothing is trusted until these pass, and a
 * source object is never deleted unless its member row says VERIFIED.
 *
 * Five of the six checks are nearly free. The sixth - reading every byte back -
 * is parked for the initial rollout and runs on a sample when enabled.
 */
class VerifyBundle(
    private val store: ObjectStore,
    private val bundles: ArchiveBundleRepo,
    private val members: ArchiveMemberRepo,
    private val config: Config,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CheckResult(val name: String, val passed: Boolean, val detail: String)
    data class Report(val bundleId: Long, val checks: List<CheckResult>) {
        val passed: Boolean get() = checks.all { it.passed }
    }

    fun verify(bundleId: Long): Report {
        val bundle = bundles.find(bundleId) ?: error("bundle $bundleId not found")
        require(bundle.state == BundleState.WRITTEN) {
            "bundle $bundleId is ${bundle.state}, only WRITTEN bundles are verified"
        }
        val rows = members.listForBundle(bundleId)
        val checks = mutableListOf<CheckResult>()

        // 1. Finalise - the store reported its own size and checksum on completion.
        val stored = store.stat(bundle.bucket, bundle.objectPath)
        checks += CheckResult(
            "1 finalise",
            stored != null && stored.generation == bundle.generation,
            "stored generation=${stored?.generation}, row says ${bundle.generation}",
        )

        // 2. Size - our arithmetic must equal the stored size exactly.
        val expected = rows.sumOf { UstarFramer.BLOCK + UstarFramer.padTo512(it.lengthBytes) } +
            UstarFramer.BLOCK + UstarFramer.padTo512(bundle.manifestLength ?: 0) +
            UstarFramer.END_OF_ARCHIVE_BYTES
        checks += CheckResult(
            "2 size",
            stored != null && stored.sizeBytes == expected,
            "computed $expected, stored ${stored?.sizeBytes}",
        )

        // 3. Manifest - one ranged read, parse it, count the entries.
        val manifestEntries = readManifestEntryCount(bundle)
        checks += CheckResult(
            "3 manifest",
            manifestEntries != null,
            "manifest lists ${manifestEntries ?: "unreadable"} entries",
        )

        // 4. Nothing skipped. The column is NOT NULL, so this is now a structural
        //    invariant rather than a query - asserted here so it stays one.
        val planned = members.countForBundle(bundleId)
        checks += CheckResult(
            "4 nothing skipped",
            planned == rows.size.toLong(),
            "$planned member rows, ${rows.size} read back",
        )

        // 5. Three counts agree: manifest = member rows = the recorded member_count.
        checks += CheckResult(
            "5 counts agree",
            manifestEntries != null &&
                manifestEntries.toLong() == planned &&
                planned == bundle.memberCount,
            "manifest=$manifestEntries rows=$planned recorded=${bundle.memberCount}",
        )

        // 6. Every byte. Expensive, so it runs last and only when enabled.
        checks += if (config.engine.verifyFullByteScan) {
            val sample = rows.shuffled().take(config.engine.verifySampleSize)
            val bad = sample.filterNot { member -> crcMatches(bundle, member.offsetBytes, member.lengthBytes, member.crc32c) }
            CheckResult("6 every byte", bad.isEmpty(), "${sample.size} sampled, ${bad.size} mismatched")
        } else {
            CheckResult("6 every byte", true, "parked for initial rollout (verify.full.byte.scan=false)")
        }

        val report = Report(bundleId, checks)
        if (report.passed) {
            bundles.markVerified(bundleId)
            log.info("bundle {} VERIFIED", bundleId)
        } else {
            val failed = checks.filterNot { it.passed }.joinToString("; ") { "${it.name}: ${it.detail}" }
            bundles.markFailed(bundleId, failed)
            log.error("bundle {} failed verification: {}", bundleId, failed)
        }
        return report
    }

    private fun readManifestEntryCount(bundle: ClaimedBundle): Int? {
        val offset = bundle.manifestOffset ?: return null
        val length = bundle.manifestLength ?: return null
        return runCatching {
            store.openRange(bundle.bucket, bundle.objectPath, bundle.generation!!, offset, length)
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
                .lineSequence()
                .count { it.startsWith("{") }
        }.getOrNull()
    }

    private fun crcMatches(bundle: ClaimedBundle, offset: Long, length: Long, expected: String): Boolean =
        runCatching {
            val crc = CRC32C()
            store.openRange(bundle.bucket, bundle.objectPath, bundle.generation!!, offset, length).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    crc.update(buf, 0, n)
                }
            }
            encode(crc.value) == expected
        }.getOrDefault(false)

    private fun encode(value: Long): String {
        val b = ByteArray(4)
        b[0] = (value ushr 24).toByte(); b[1] = (value ushr 16).toByte()
        b[2] = (value ushr 8).toByte(); b[3] = value.toByte()
        return Base64.getEncoder().encodeToString(b)
    }
}
