package `in`.porter.archival.data.psql

import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.model.PlannedMember
import `in`.porter.archival.domain.model.SourceObject
import `in`.porter.archival.domain.port.ArchiveMemberRepo
import `in`.porter.archival.domain.port.StoredMember
import java.sql.ResultSet

/**
 * One write per member for the whole run. The offset is a pure function of
 * (sorted order, exact sizes), the size comes from object metadata and is exact,
 * and the read is generation-pinned - so the position is known before anything
 * is read and goes in with the INSERT.
 */
class PsqlArchiveMemberRepo(private val db: Db) : ArchiveMemberRepo {

    private val columns = """
        cohort_date, source_object, source_generation, crn, doc_kind, doc_format,
        bundle_id, offset_bytes, length_bytes, crc32c
    """.trimIndent()

    override fun insertPlanned(cohortDate: CohortDate, bundleId: Long, members: List<PlannedMember>): Int =
        db.tx { c ->
            // ON CONFLICT DO NOTHING on (cohort_date, source_object, source_generation):
            // a rerun costs one metadata call and no read.
            c.prepareStatement(
                """
                INSERT INTO invoice_archive_members ($columns)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cohort_date, source_object, source_generation) DO NOTHING
                """
            ).use { st ->
                members.forEach { m ->
                    st.setObject(1, cohortDate.value)
                    st.setString(2, m.source.path)
                    st.setLong(3, m.source.generation)
                    st.setString(4, m.crn)
                    st.setString(5, m.docKind.name)
                    st.setString(6, m.docFormat.extension)
                    st.setLong(7, bundleId)
                    st.setLong(8, m.offsetBytes)
                    st.setLong(9, m.source.sizeBytes)
                    st.setString(10, m.source.crc32c)
                    st.addBatch()
                }
                st.executeBatch().sum()
            }
        }

    override fun listForBundle(bundleId: Long): List<StoredMember> = db.read { c ->
        c.prepareStatement(
            "SELECT $columns FROM invoice_archive_members WHERE bundle_id = ? ORDER BY offset_bytes"
        ).use { st ->
            st.setLong(1, bundleId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMember()) } }
        }
    }

    /**
     * The failure path, and the one place member rows are updated after insert.
     * Skipping a file would shift every later offset, so the whole bundle is
     * re-planned without it and the survivors get a new bundle and new offsets.
     */
    override fun reassign(cohortDate: CohortDate, members: List<Pair<StoredMember, Long>>, newBundleId: Long) =
        db.tx { c ->
            c.prepareStatement(
                """
                UPDATE invoice_archive_members
                   SET bundle_id = ?, offset_bytes = ?
                 WHERE cohort_date = ? AND source_object = ? AND source_generation = ?
                """
            ).use { st ->
                members.forEach { (member, newOffset) ->
                    st.setLong(1, newBundleId)
                    st.setLong(2, newOffset)
                    st.setObject(3, cohortDate.value)
                    st.setString(4, member.source.path)
                    st.setLong(5, member.source.generation)
                    st.addBatch()
                }
                st.executeBatch()
            }
            Unit
        }

    override fun recordMemberFailure(
        cohortDate: CohortDate,
        sourceObject: String,
        sourceGeneration: Long,
        error: String,
    ) = db.tx { c ->
        c.prepareStatement(
            """
            UPDATE invoice_archive_members
               SET attempts = attempts + 1, last_error = ?
             WHERE cohort_date = ? AND source_object = ? AND source_generation = ?
            """
        ).use { st ->
            st.setString(1, error.take(4000))
            st.setObject(2, cohortDate.value)
            st.setString(3, sourceObject)
            st.setLong(4, sourceGeneration)
            st.executeUpdate()
        }
        Unit
    }

    /** Served by the (crn, doc_kind, doc_format) index - one seek, never a scan. */
    override fun findForRetrieval(crn: String, docKind: String, docFormat: String): StoredMember? =
        db.read { c ->
            c.prepareStatement(
                "SELECT $columns FROM invoice_archive_members WHERE crn = ? AND doc_kind = ? AND doc_format = ? LIMIT 1"
            ).use { st ->
                st.setString(1, crn); st.setString(2, docKind); st.setString(3, docFormat)
                st.executeQuery().use { rs -> if (rs.next()) rs.toMember() else null }
            }
        }

    override fun countForBundle(bundleId: Long): Long = db.read { c ->
        c.prepareStatement("SELECT count(*) FROM invoice_archive_members WHERE bundle_id = ?").use { st ->
            st.setLong(1, bundleId)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    override fun alreadyPlanned(cohortDate: CohortDate): Long = db.read { c ->
        c.prepareStatement("SELECT count(*) FROM invoice_archive_members WHERE cohort_date = ?").use { st ->
            st.setObject(1, cohortDate.value)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun ResultSet.toMember() = StoredMember(
        cohortDate = CohortDate(getObject("cohort_date", java.time.LocalDate::class.java)),
        source = SourceObject(
            bucket = "",
            path = getString("source_object"),
            generation = getLong("source_generation"),
            sizeBytes = getLong("length_bytes"),
            crc32c = getString("crc32c"),
        ),
        crn = getString("crn"),
        docKind = getString("doc_kind"),
        docFormat = getString("doc_format"),
        bundleId = getLong("bundle_id"),
        offsetBytes = getLong("offset_bytes"),
        lengthBytes = getLong("length_bytes"),
        crc32c = getString("crc32c"),
    )
}
