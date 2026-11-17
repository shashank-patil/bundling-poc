package `in`.porter.archival.domain.usecase

import `in`.porter.archival.app.Config
import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.model.PlannedMember
import `in`.porter.archival.domain.model.SourceObject
import `in`.porter.archival.domain.port.ArchiveBundleRepo
import `in`.porter.archival.domain.port.ArchiveMemberRepo
import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.port.WorklistSource
import `in`.porter.archival.domain.tar.UstarFramer
import org.slf4j.LoggerFactory

/**
 * Cron 1 - the Lister.
 *
 * Builds one day's worklist and plans it into bundles. It reads object metadata
 * and nothing else: no payload is fetched and no tar is written here. By the
 * time it finishes, every file already knows which tar it belongs to and the
 * exact byte at which it will sit.
 */
class BuildWorklist(
    private val worklist: WorklistSource,
    private val store: ObjectStore,
    private val bundles: ArchiveBundleRepo,
    private val members: ArchiveMemberRepo,
    private val config: Config,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val bundlesPlanned: Int, val membersPlanned: Int, val objectsSkipped: Int)

    fun run(cohortDate: CohortDate): Result {
        val bounds = worklist.idBoundsFor(cohortDate)
        if (bounds == null) {
            log.info("no orders for cohort {}", cohortDate)
            return Result(0, 0, 0)
        }
        log.info("cohort {} spans oms_orders ids {}..{}", cohortDate, bounds.first, bounds.last)

        val accumulator = BundleAccumulator(cohortDate)
        var cursor = bounds.first - 1
        var skipped = 0
        var pages = 0

        while (true) {
            val page = worklist.page(cursor, bounds.last, config.engine.listerPageSize)
            if (page.isEmpty()) break
            pages++

            for (row in page) {
                val parsed = ObjectNaming.parseUri(row.invoicePdfUri)
                if (parsed == null) {
                    skipped++
                    continue
                }
                // The PDF, then its HTML twin. Order is the worklist's own id order,
                // which is already deterministic and needs no global sort - so a
                // rerun re-packs byte-identically without holding a day in memory.
                for (path in listOf(parsed.path, ObjectNaming.htmlTwinOf(parsed.path))) {
                    // Route maps, marketing assets and report PDFs are not tax
                    // documents and are never archived, whatever points at them.
                    if (config.archival.excludedPrefixes.any { path.startsWith(it) || path.contains("/$it") }) {
                        skipped++
                        continue
                    }
                    val named = ObjectNaming.nameOf(path) ?: continue
                    if (named.kind.name !in config.archival.docKinds ||
                        named.format.extension !in config.archival.docFormats
                    ) {
                        skipped++
                        continue
                    }
                    val meta = store.stat(parsed.bucket, path)
                    if (meta == null) {
                        skipped++
                        continue
                    }
                    accumulator.add(
                        SourceObject(
                            bucket = parsed.bucket,
                            path = path,
                            generation = meta.generation,
                            sizeBytes = meta.sizeBytes,
                            crc32c = meta.crc32c,
                        ),
                        named,
                    )
                }
            }
            cursor = page.last().id
        }

        accumulator.flushRemaining()
        log.info(
            "cohort {}: {} pages, {} bundles, {} members, {} objects skipped",
            cohortDate, pages, accumulator.bundlesPlanned, accumulator.membersPlanned, skipped,
        )
        return Result(accumulator.bundlesPlanned, accumulator.membersPlanned, skipped)
    }

    /**
     * Running sum with a bundle-full decision. Only one bundle's worth of entries
     * is ever held, so the heap does not grow with the size of the day.
     */
    private inner class BundleAccumulator(private val cohortDate: CohortDate) {
        private val pending = ArrayList<Pair<SourceObject, ObjectNaming.Named>>()
        private var pendingBytes = 0L
        private var ordinalInDay = 0

        var bundlesPlanned = 0
            private set
        var membersPlanned = 0
            private set

        fun add(source: SourceObject, named: ObjectNaming.Named) {
            val costInTar = UstarFramer.BLOCK + UstarFramer.padTo512(source.sizeBytes)
            val wouldExceedBytes = pending.isNotEmpty() &&
                pendingBytes + costInTar > config.archival.targetBundleBytes
            val wouldExceedMembers = pending.size >= config.archival.maxBundleMembers
            if (wouldExceedBytes || wouldExceedMembers) flush()

            pending.add(source to named)
            pendingBytes += costInTar
        }

        fun flushRemaining() {
            if (pending.isNotEmpty()) flush()
        }

        private fun flush() {
            val objectPath = bundleObjectPath(cohortDate, ordinalInDay++)
            val bundleId = bundles.create(cohortDate, config.archival.archiveBucket, objectPath, config.archival.tarLayoutVersion)

            val plan = UstarFramer.plan(pending.map { it.first.sizeBytes })
            val planned = pending.mapIndexed { i, (source, named) ->
                PlannedMember(
                    source = source,
                    crn = named.crn,
                    docKind = named.kind,
                    docFormat = named.format,
                    bundleOrdinal = i,
                    offsetBytes = plan.offsets[i],
                )
            }

            val inserted = members.insertPlanned(cohortDate, bundleId, planned)
            bundlesPlanned++
            membersPlanned += inserted

            log.info(
                "planned bundle {} ({}) with {} members, {} payload bytes",
                bundleId, objectPath, planned.size, pendingBytes,
            )

            pending.clear()
            pendingBytes = 0
        }
    }

    companion object {
        /** Date-partitioned object names keep the archive browsable without the index. */
        fun bundleObjectPath(cohortDate: CohortDate, ordinal: Int): String {
            val d = cohortDate.value
            return "%04d/%02d/%02d/bundle-%05d.tar".format(d.year, d.monthValue, d.dayOfMonth, ordinal)
        }
    }
}
