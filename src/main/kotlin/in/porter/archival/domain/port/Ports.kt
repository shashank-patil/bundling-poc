package `in`.porter.archival.domain.port

import `in`.porter.archival.domain.model.BundleState
import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.model.PlannedMember
import `in`.porter.archival.domain.model.SourceObject
import java.io.InputStream
import java.io.OutputStream

/**
 * The seam that makes "no GCS" honest rather than a shortcut. Every call here
 * maps onto a real Cloud Storage operation; the local implementation is the only
 * thing that would change.
 */
interface ObjectStore {

    /** Metadata only - size, generation and checksum, with no payload read. */
    fun stat(bucket: String, path: String): ObjectMetadata?

    /** A generation-pinned ranged read. The bytes can never disagree with the recorded crc32c. */
    fun openRange(bucket: String, path: String, generation: Long, offset: Long, length: Long): InputStream

    /**
     * Create-only write, the equivalent of ifGenerationMatch=0: if the object is
     * already there this throws instead of silently overwriting a verified bundle.
     */
    fun createOnly(bucket: String, path: String, write: (OutputStream) -> Unit): ObjectMetadata

    fun delete(bucket: String, path: String, generation: Long)

    fun exists(bucket: String, path: String): Boolean
}

data class ObjectMetadata(
    val path: String,
    val generation: Long,
    val sizeBytes: Long,
    val crc32c: String,
    val md5: String,
)

/** Thrown when an object is gone, truncated or otherwise unreadable mid-bundle. */
class ObjectUnreadable(val bucket: String, val path: String, cause: Throwable? = null) :
    RuntimeException("unreadable object gs://$bucket/$path", cause)

interface ArchiveBundleRepo {
    fun create(cohortDate: CohortDate, bucket: String, objectPath: String): Long
    fun claimNext(leaseMinutes: Int, owner: String): ClaimedBundle?
    fun markWritten(id: Long, generation: Long, byteSize: Long, md5: String, memberCount: Long, manifestOffset: Long, manifestLength: Long)
    fun markVerified(id: Long)
    fun markFailed(id: Long, error: String)
    fun find(id: Long): ClaimedBundle?
    fun countByState(cohortDate: CohortDate): Map<BundleState, Long>
}

data class ClaimedBundle(
    val id: Long,
    val cohortDate: CohortDate,
    val bucket: String,
    val objectPath: String,
    val state: BundleState,
    val generation: Long?,
    val byteSize: Long?,
    val md5: String?,
    val memberCount: Long?,
    val manifestOffset: Long?,
    val manifestLength: Long?,
    val attempts: Int,
)

interface ArchiveMemberRepo {
    /** One write per member, with the position already decided. ON CONFLICT DO NOTHING makes reruns free. */
    fun insertPlanned(cohortDate: CohortDate, bundleId: Long, members: List<PlannedMember>): Int

    fun listForBundle(bundleId: Long): List<StoredMember>

    /** The failure path: survivors are re-pointed at a fresh bundle with fresh offsets. */
    fun reassign(cohortDate: CohortDate, members: List<Pair<StoredMember, Long>>, newBundleId: Long)

    fun recordMemberFailure(cohortDate: CohortDate, sourceObject: String, sourceGeneration: Long, error: String)

    fun findForRetrieval(crn: String, docKind: String, docFormat: String): StoredMember?

    fun countForBundle(bundleId: Long): Long

    fun alreadyPlanned(cohortDate: CohortDate): Long
}

data class StoredMember(
    val cohortDate: CohortDate,
    val source: SourceObject,
    val crn: String,
    val docKind: String,
    val docFormat: String,
    val bundleId: Long,
    val offsetBytes: Long,
    val lengthBytes: Long,
    val crc32c: String,
)

/** The worklist source - OMS in production, the oms_orders table here. */
interface WorklistSource {
    /** Resolves the id range covering one cohort day without relying on a created_at index. */
    fun idBoundsFor(cohortDate: CohortDate): LongRange?

    /** One keyset page. Never the whole day in a single statement. */
    fun page(afterId: Long, maxId: Long, limit: Int): List<WorklistRow>
}

data class WorklistRow(val id: Long, val orderId: String, val invoicePdfUri: String)
