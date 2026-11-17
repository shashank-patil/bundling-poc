package `in`.porter.archival.domain.usecase

import `in`.porter.archival.app.Config
import `in`.porter.archival.domain.port.ArchiveBundleRepo
import `in`.porter.archival.domain.port.ArchiveMemberRepo
import `in`.porter.archival.domain.port.ClaimedBundle
import `in`.porter.archival.domain.port.ObjectMetadata
import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.port.ObjectUnreadable
import `in`.porter.archival.domain.port.StoredMember
import `in`.porter.archival.domain.tar.UstarFramer
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Cron 2 - the Bundler, write half.
 *
 * Reads run `fetchConcurrency` wide behind a queue bounded in bytes; the writer
 * is single-threaded and drains that queue in the exact order the Lister
 * planned. Nothing is buffered whole, so a multi-GiB bundle is written inside a
 * 512 MB heap.
 */
class FrameBundle(
    private val store: ObjectStore,
    private val bundles: ArchiveBundleRepo,
    private val members: ArchiveMemberRepo,
    private val config: Config,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface Outcome {
        data class Written(val bundleId: Long, val meta: ObjectMetadata, val memberCount: Long) : Outcome
        data class Replanned(val failedBundleId: Long, val newBundleId: Long?, val dropped: String) : Outcome
        data object Nothing : Outcome
    }

    fun runOnce(owner: String): Outcome {
        val claimed = bundles.claimNext(
            config.engine.leaseMinutes,
            config.engine.maxAttempts,
            owner,
        ) ?: return Outcome.Nothing

        val rows = members.listForBundle(claimed.id)
        if (rows.isEmpty()) {
            bundles.markFailed(claimed.id, "claimed a bundle with no members")
            return Outcome.Replanned(claimed.id, null, "no members")
        }

        // Metadata-only pre-flight. Catches "object gone" or "generation moved"
        // before a single payload byte is read.
        rows.firstOrNull { row ->
            val meta = store.stat(config.archival.sourceBucket, row.source.path)
            meta == null || meta.generation != row.source.generation || meta.sizeBytes != row.lengthBytes
        }?.let { broken ->
            return replan(claimed, rows, broken, "pre-flight: metadata moved or object missing")
        }

        return try {
            val manifest = buildManifest(rows)
            val meta = store.createOnly(claimed.bucket, claimed.objectPath) { out ->
                writeArchive(out, rows, manifest)
            }
            val plan = UstarFramer.plan(rows.map { it.lengthBytes })

            bundles.markWritten(
                id = claimed.id,
                generation = meta.generation,
                byteSize = meta.sizeBytes,
                md5 = meta.md5,
                memberCount = rows.size.toLong(),
                manifestOffset = plan.cursorAfter + UstarFramer.BLOCK,
                manifestLength = manifest.size.toLong(),
            )
            log.info("bundle {} written: {} members, {} bytes", claimed.id, rows.size, meta.sizeBytes)
            Outcome.Written(claimed.id, meta, rows.size.toLong())
        } catch (e: ObjectUnreadable) {
            // A partial object must never be left behind to be mistaken for a bundle.
            discardPartial(claimed)
            val broken = rows.firstOrNull { it.source.path == e.path } ?: rows.first()
            replan(claimed, rows, broken, e.message ?: "unreadable member")
        }
    }

    /**
     * Offsets are pre-planned, so skipping one file would shift every later
     * offset in the tar. The whole bundle fails and is re-planned without it:
     * never written with a gap, never written short.
     */
    private fun replan(
        claimed: ClaimedBundle,
        rows: List<StoredMember>,
        broken: StoredMember,
        reason: String,
    ): Outcome.Replanned {
        log.warn("bundle {} failing: {} ({})", claimed.id, reason, broken.source.path)
        members.recordMemberFailure(claimed.cohortDate, broken.source.path, broken.source.generation, reason)
        bundles.markFailed(claimed.id, reason)

        val survivors = rows.filterNot { it.source.path == broken.source.path }
        if (survivors.isEmpty()) return Outcome.Replanned(claimed.id, null, broken.source.path)

        val newObjectPath = claimed.objectPath.replace(".tar", "-r${claimed.attempts}.tar")
        val newBundleId = bundles.create(claimed.cohortDate, claimed.bucket, newObjectPath, config.archival.tarLayoutVersion)
        val plan = UstarFramer.plan(survivors.map { it.lengthBytes })
        members.reassign(
            claimed.cohortDate,
            survivors.mapIndexed { i, m -> m to plan.offsets[i] },
            newBundleId,
        )
        log.info("re-planned {} survivors into bundle {}", survivors.size, newBundleId)
        return Outcome.Replanned(claimed.id, newBundleId, broken.source.path)
    }

    private fun discardPartial(claimed: ClaimedBundle) {
        runCatching {
            store.stat(claimed.bucket, claimed.objectPath)?.let {
                store.delete(claimed.bucket, claimed.objectPath, it.generation)
            }
        }
    }

    private fun writeArchive(raw: OutputStream, rows: List<StoredMember>, manifest: ByteArray) {
        // The upload chunk is the writer's buffer: whole chunks go out at once,
        // which is what a GCS resumable upload wants.
        val out = BufferedOutputStream(raw, config.engine.uploadChunkBytes)
        var position = 0L

        ReadAheadPipeline(
            store = store,
            bucket = config.archival.sourceBucket,
            fetchConcurrency = config.engine.fetchConcurrency,
            queueBytes = config.engine.queueBytes,
        ).use { pipeline ->
            pipeline.stream(rows) { member, payload ->
                out.write(UstarFramer.header(
                    ObjectNaming.entryNameFor(member.source.path),
                    member.lengthBytes,
                    member.source.generation / 1_000_000,
                ))
                position += UstarFramer.BLOCK

                // The invariant the whole index depends on, asserted as we go.
                check(position == member.offsetBytes) {
                    "offset drift: writing ${member.source.path} at $position, " +
                        "member row says ${member.offsetBytes}"
                }

                out.write(payload)
                position += payload.size

                val padding = UstarFramer.paddingAfter(member.lengthBytes)
                out.write(padding)
                position += padding.size
            }
        }

        // The manifest is the last member, so the index can be rebuilt from the
        // tar alone if Postgres is ever lost.
        out.write(UstarFramer.header("MANIFEST.json", manifest.size.toLong(), 0))
        out.write(manifest)
        out.write(UstarFramer.paddingAfter(manifest.size.toLong()))
        out.write(UstarFramer.endOfArchive())
        out.flush()
    }

    /** One JSON object per line, so the manifest can be counted without a parser. */
    private fun buildManifest(rows: List<StoredMember>): ByteArray {
        val sb = StringBuilder(rows.size * 160)
        sb.append("[\n")
        rows.forEachIndexed { i, r ->
            sb.append("""{"name":"${r.source.path}","crn":"${r.crn}","kind":"${r.docKind}",""")
            sb.append(""""format":"${r.docFormat}","offset":${r.offsetBytes},""")
            sb.append(""""length":${r.lengthBytes},"crc32c":"${r.crc32c}"}""")
            if (i < rows.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("]\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
