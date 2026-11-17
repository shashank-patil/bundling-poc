package `in`.porter.archival.domain.usecase

import `in`.porter.archival.domain.model.BundleState
import `in`.porter.archival.domain.port.ArchiveBundleRepo
import `in`.porter.archival.domain.port.ArchiveMemberRepo
import `in`.porter.archival.domain.port.ObjectStore
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.zip.CRC32C

/**
 * Retrieval. One index seek, one ranged read, one checksum - and the size of the
 * tar never enters the bill, because retrieval is charged on bytes accessed and
 * not on the size of the object they came from.
 *
 * Which answer the caller gets, and why:
 *   no member row        -> 404, we never archived it
 *   bundle not VERIFIED  -> 409, it exists but is not yet trustworthy
 *   checksum mismatch    -> 502, never a wrong document
 *   otherwise            -> 200 with exactly length_bytes bytes
 */
class ResolveDocument(
    private val store: ObjectStore,
    private val bundles: ArchiveBundleRepo,
    private val members: ArchiveMemberRepo,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface Outcome {
        data class Found(val bytes: ByteArray, val contentType: String, val source: String) : Outcome
        data object NotArchived : Outcome
        data class NotReady(val state: BundleState) : Outcome
        data class Corrupt(val detail: String) : Outcome
    }

    fun resolve(crn: String, docKind: String, docFormat: String): Outcome {
        val member = members.findForRetrieval(crn, docKind, docFormat) ?: return Outcome.NotArchived
        val bundle = bundles.find(member.bundleId) ?: return Outcome.NotArchived

        if (bundle.state != BundleState.VERIFIED) return Outcome.NotReady(bundle.state)

        val generation = bundle.generation ?: return Outcome.Corrupt("bundle has no generation")

        val bytes = runCatching {
            store.openRange(
                bundle.bucket,
                bundle.objectPath,
                generation,
                member.offsetBytes,
                member.lengthBytes,
            ).use { it.readBytes() }
        }.getOrElse { return Outcome.Corrupt("ranged read failed: ${it.message}") }

        if (bytes.size.toLong() != member.lengthBytes) {
            return Outcome.Corrupt("read ${bytes.size} bytes, member row says ${member.lengthBytes}")
        }

        val crc = CRC32C().apply { update(bytes) }
        val actual = encode(crc.value)
        if (actual != member.crc32c) {
            log.error("crc mismatch for {} in bundle {}", crn, bundle.id)
            return Outcome.Corrupt("crc32c mismatch: expected ${member.crc32c}, got $actual")
        }

        return Outcome.Found(
            bytes = bytes,
            contentType = if (docFormat == "pdf") "application/pdf" else "text/html",
            source = "${bundle.objectPath}@${member.offsetBytes}+${member.lengthBytes}",
        )
    }

    private fun encode(value: Long): String {
        val b = ByteArray(4)
        b[0] = (value ushr 24).toByte(); b[1] = (value ushr 16).toByte()
        b[2] = (value ushr 8).toByte(); b[3] = value.toByte()
        return Base64.getEncoder().encodeToString(b)
    }
}
