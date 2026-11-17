package `in`.porter.archival.tools

import `in`.porter.archival.app.Wiring
import `in`.porter.archival.domain.model.BundleState
import `in`.porter.archival.domain.model.CohortDate
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * The two operations that make the demo convincing rather than just green.
 */
object DemoOps {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Phase 4. Deletes a source object only when its member row belongs to a
     * VERIFIED bundle, and only at the generation that was archived. Everything
     * the worklist never saw - the unreferenced CN/DN files - is left alone.
     */
    fun deleteVerifiedSources(wiring: Wiring, cohort: CohortDate): Int {
        var deleted = 0
        var skipped = 0

        wiring.db.read { c ->
            c.prepareStatement(
                """
                SELECT m.source_object, m.source_generation
                  FROM invoice_archive_members m
                  JOIN invoice_archive_bundles b ON b.id = m.bundle_id
                 WHERE m.cohort_date = ? AND b.state = 'VERIFIED'
                """
            ).use { st ->
                st.setObject(1, cohort.value)
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val path = rs.getString(1)
                        val generation = rs.getLong(2)
                        runCatching {
                            wiring.store.delete(wiring.config.archival.sourceBucket, path, generation)
                            deleted++
                        }.onFailure {
                            skipped++
                            log.warn("not deleting {}: {}", path, it.message)
                        }
                    }
                }
            }
        }
        log.info("delete phase: {} source objects removed, {} skipped", deleted, skipped)
        return deleted
    }

    /**
     * Forces the failure path: truncates one source object so the Bundler finds
     * it unreadable, fails the whole bundle and re-plans the survivors.
     */
    fun corruptOneSource(wiring: Wiring, cohort: CohortDate): String? {
        val target = wiring.db.read { c ->
            c.prepareStatement(
                """
                SELECT m.source_object
                  FROM invoice_archive_members m
                  JOIN invoice_archive_bundles b ON b.id = m.bundle_id
                 WHERE m.cohort_date = ? AND b.state = 'BUILDING'
                 ORDER BY m.offset_bytes
                 OFFSET 1 LIMIT 1
                """
            ).use { st ->
                st.setObject(1, cohort.value)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return null

        val file = wiring.config.runtime.sourceRoot.resolve(target)
        Files.newByteChannel(file, StandardOpenOption.WRITE).use { it.truncate(16) }
        log.warn("truncated {} to 16 bytes - the next bundler run must fail and re-plan", target)
        return target
    }

    fun describe(wiring: Wiring, cohort: CohortDate) {
        val states = wiring.bundles.countByState(cohort)
        val members = wiring.members.alreadyPlanned(cohort)
        log.info("cohort {}: {} member rows, bundles by state {}", cohort, members, states)
        BundleState.entries.forEach { s -> log.info("  {} = {}", s, states[s] ?: 0) }
    }
}
