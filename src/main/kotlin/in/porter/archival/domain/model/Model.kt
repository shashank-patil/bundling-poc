package `in`.porter.archival.domain.model

import java.time.LocalDate

/** BUILDING -> WRITTEN -> VERIFIED. A bundle is created already BUILDING; only a file ever waits. */
enum class BundleState { BUILDING, WRITTEN, VERIFIED, FAILED }

/**
 * Provisional, exactly as the LLD says: these two cover every filename shape in
 * order-invoices/, and the column is a VARCHAR so adding a kind is an insert.
 */
enum class DocKind { INVOICE, CN_DN }

enum class DocFormat(val extension: String) {
    PDF("pdf"), HTML("html");

    companion object {
        fun ofExtension(ext: String): DocFormat =
            entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
                ?: error("unsupported document format: $ext")
    }
}

/** The single day one tar holds. One cron run touches exactly one partition. */
@JvmInline
value class CohortDate(val value: LocalDate) {
    override fun toString(): String = value.toString()
}

/**
 * What the Lister learns about a source object before anything is read: its path,
 * the generation reads will be pinned to, and the exact size the offset maths needs.
 */
data class SourceObject(
    val bucket: String,
    val path: String,
    val generation: Long,
    val sizeBytes: Long,
    val crc32c: String,
)

/** A member whose position in the tar is already decided, before a single byte is read. */
data class PlannedMember(
    val source: SourceObject,
    val crn: String,
    val docKind: DocKind,
    val docFormat: DocFormat,
    val bundleOrdinal: Int,
    val offsetBytes: Long,
)
