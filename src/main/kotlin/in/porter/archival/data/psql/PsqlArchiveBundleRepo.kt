package `in`.porter.archival.data.psql

import `in`.porter.archival.domain.model.BundleState
import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.port.ArchiveBundleRepo
import `in`.porter.archival.domain.port.ClaimedBundle
import java.sql.ResultSet

/**
 * The bundle row is written three times across a run - created BUILDING, marked
 * WRITTEN with what was actually stored, then VERIFIED once the checks pass.
 * Three writes on ~30 rows a day is nothing; three writes per *member* was the
 * thing the design had to remove.
 */
class PsqlArchiveBundleRepo(private val db: Db) : ArchiveBundleRepo {

    private val columns = """
        id, cohort_date, bundle_bucket, bundle_object, state, generation,
        byte_size, md5, member_count, manifest_offset, manifest_length, attempts
    """.trimIndent()

    override fun create(cohortDate: CohortDate, bucket: String, objectPath: String): Long = db.tx { c ->
        c.prepareStatement(
            """
            INSERT INTO invoice_archive_bundles (bundle_bucket, bundle_object, cohort_date, state)
            VALUES (?, ?, ?, 'BUILDING')
            ON CONFLICT (bundle_bucket, bundle_object) DO UPDATE SET updated_at = now()
            RETURNING id
            """
        ).use { st ->
            st.setString(1, bucket)
            st.setString(2, objectPath)
            st.setObject(3, cohortDate.value)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /**
     * FOR UPDATE SKIP LOCKED with a lease. Two Bundlers never fight over the same
     * tar, and a crashed run becomes claimable again once its lease expires -
     * roughly 30 claims a day, not 1.88 M.
     */
    override fun claimNext(leaseMinutes: Int, owner: String): ClaimedBundle? = db.tx { c ->
        c.prepareStatement(
            """
            UPDATE invoice_archive_bundles
               SET claimed_at = now(), claimed_by = ?, attempts = attempts + 1, updated_at = now()
             WHERE id = (
                   SELECT id FROM invoice_archive_bundles
                    WHERE state = 'BUILDING'
                      AND (claimed_at IS NULL OR claimed_at < now() - make_interval(mins => ?))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
             )
            RETURNING $columns
            """
        ).use { st ->
            st.setString(1, owner)
            st.setInt(2, leaseMinutes)
            st.executeQuery().use { rs -> if (rs.next()) rs.toBundle() else null }
        }
    }

    override fun markWritten(
        id: Long,
        generation: Long,
        byteSize: Long,
        md5: String,
        memberCount: Long,
        manifestOffset: Long,
        manifestLength: Long,
    ) = db.tx { c ->
        c.prepareStatement(
            """
            UPDATE invoice_archive_bundles
               SET state = 'WRITTEN', generation = ?, byte_size = ?, md5 = ?, member_count = ?,
                   manifest_offset = ?, manifest_length = ?, last_error = NULL, updated_at = now()
             WHERE id = ?
            """
        ).use { st ->
            st.setLong(1, generation); st.setLong(2, byteSize); st.setString(3, md5)
            st.setLong(4, memberCount); st.setLong(5, manifestOffset); st.setLong(6, manifestLength)
            st.setLong(7, id)
            st.executeUpdate()
        }
        Unit
    }

    override fun markVerified(id: Long) = db.tx { c ->
        c.prepareStatement(
            "UPDATE invoice_archive_bundles SET state='VERIFIED', verified_at=now(), updated_at=now() WHERE id=?"
        ).use { it.setLong(1, id); it.executeUpdate() }
        Unit
    }

    override fun markFailed(id: Long, error: String) = db.tx { c ->
        c.prepareStatement(
            "UPDATE invoice_archive_bundles SET state='FAILED', last_error=?, claimed_at=NULL, updated_at=now() WHERE id=?"
        ).use { it.setString(1, error.take(4000)); it.setLong(2, id); it.executeUpdate() }
        Unit
    }

    override fun find(id: Long): ClaimedBundle? = db.read { c ->
        c.prepareStatement("SELECT $columns FROM invoice_archive_bundles WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toBundle() else null }
        }
    }

    override fun countByState(cohortDate: CohortDate): Map<BundleState, Long> = db.read { c ->
        c.prepareStatement(
            "SELECT state, count(*) FROM invoice_archive_bundles WHERE cohort_date = ? GROUP BY state"
        ).use { st ->
            st.setObject(1, cohortDate.value)
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(BundleState.valueOf(rs.getString(1)), rs.getLong(2)) }
            }
        }
    }

    private fun ResultSet.toBundle() = ClaimedBundle(
        id = getLong("id"),
        cohortDate = CohortDate(getObject("cohort_date", java.time.LocalDate::class.java)),
        bucket = getString("bundle_bucket"),
        objectPath = getString("bundle_object"),
        state = BundleState.valueOf(getString("state")),
        generation = getObject("generation") as Long?,
        byteSize = getObject("byte_size") as Long?,
        md5 = getString("md5"),
        memberCount = getObject("member_count") as Long?,
        manifestOffset = getObject("manifest_offset") as Long?,
        manifestLength = getObject("manifest_length") as Long?,
        attempts = getInt("attempts"),
    )
}
