package `in`.porter.archival.data.psql

import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.port.WorklistRow
import `in`.porter.archival.domain.port.WorklistSource
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reads the worklist the way the LLD specifies: never as one query.
 *
 * A cohort day is ~1.5 M rows and invoice_pdf_uri is a varchar(255) of ~205
 * character URIs, so a whole day is 400-500 MB of Java strings - past the 512 MB
 * heap on its own, and a statement that long on a standby gets cancelled by WAL
 * replay anyway. So we page on the primary key, which is always indexed and
 * rises with time, and never depend on an index over created_at.
 */
class OmsWorklistSource(private val db: Db) : WorklistSource {

    /**
     * Binary-searches the serial primary key for the day's boundaries.
     *
     * This leans on one property of a serial PK: id order is time order. About
     * 20 indexed point lookups per boundary, microseconds each - versus a range
     * scan over a column we have not confirmed is indexed.
     */
    override fun idBoundsFor(cohortDate: CohortDate): LongRange? = db.read { c ->
        val dayStart = cohortDate.value.atStartOfDay().atOffset(ZoneOffset.UTC)
        val dayEnd = cohortDate.value.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)

        val (globalMin, globalMax) = c.prepareStatement("SELECT min(id), max(id) FROM oms_orders")
            .use { st -> st.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) } }
        if (globalMax == 0L) return@read null

        fun createdAtAtOrAfter(id: Long): OffsetDateTime? =
            c.prepareStatement("SELECT created_at FROM oms_orders WHERE id >= ? ORDER BY id LIMIT 1").use { st ->
                st.setLong(1, id)
                st.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject(1, OffsetDateTime::class.java) else null
                }
            }

        // Lowest id whose row is at or after the start of the day.
        fun lowestIdAtOrAfter(at: OffsetDateTime): Long {
            var lo = globalMin
            var hi = globalMax + 1
            while (lo < hi) {
                val mid = lo + (hi - lo) / 2
                val created = createdAtAtOrAfter(mid)
                if (created != null && created < at) lo = mid + 1 else hi = mid
            }
            return lo
        }

        val first = lowestIdAtOrAfter(dayStart)
        val afterLast = lowestIdAtOrAfter(dayEnd)
        if (afterLast <= first) null else (first until afterLast)
    }

    /**
     * One keyset page. Each is an index range scan on the primary key returning a
     * few MB, holding no long snapshot, and a crash resumes from the cursor
     * instead of restarting the day.
     */
    override fun page(cohortDate: CohortDate, afterId: Long, maxId: Long, limit: Int): List<WorklistRow> =
        db.read { c ->
        c.prepareStatement(
            """
            SELECT id, order_id, invoice_pdf_uri
              FROM oms_orders
             WHERE id > ? AND id <= ?
               AND created_at >= ? AND created_at < ?
               AND invoice_pdf_uri IS NOT NULL
             ORDER BY id
             LIMIT ?
            """
        ).use { st ->
            st.setLong(1, afterId)
            st.setLong(2, maxId)
            st.setObject(3, cohortDate.value.atStartOfDay().atOffset(ZoneOffset.UTC))
            st.setObject(4, cohortDate.value.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
            st.setInt(5, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        WorklistRow(rs.getLong(1), rs.getString(2), rs.getString(3))
                    )
                }
            }
        }
    }

    /** Only used by the seed tool, so the demo can target a day that really has rows. */
    fun distinctDays(): List<LocalDate> = db.read { c ->
        c.prepareStatement("SELECT DISTINCT created_at::date AS d FROM oms_orders ORDER BY d").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getObject(1, LocalDate::class.java)) }
            }
        }
    }
}
